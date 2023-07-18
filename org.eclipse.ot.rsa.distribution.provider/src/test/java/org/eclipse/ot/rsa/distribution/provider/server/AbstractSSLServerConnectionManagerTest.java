/**
 * Copyright (c) 2012 - 2021 Paremus Ltd., Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * 		Paremus Ltd. - initial API and implementation
 *      Data In Motion
 */
package org.eclipse.ot.rsa.distribution.provider.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.TrustManagerFactory;

import org.mockito.Mockito;

import io.netty.handler.ssl.SslHandler;

public abstract class AbstractSSLServerConnectionManagerTest extends AbstractServerConnectionManagerTest {

	protected KeyManagerFactory		keyManagerFactory;
	protected TrustManagerFactory	trustManagerFactory;

	@Override
	protected void childSetUp() throws Exception {
		keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		keyManagerFactory.init(ks, "paremus".toCharArray());

		trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

		ks = KeyStore.getInstance("JKS");
		ks.load(new FileInputStream("test-resources/fabric.truststore"), "paremus".toCharArray());
		trustManagerFactory.init(ks);

		Mockito.when(tls.hasCertificate())
			.thenReturn(true);
		Mockito.when(tls.getTLSServerHandler())
			.then(i -> {
				SSLContext instance = SSLContext.getInstance("TLSv1.2");
				instance.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(),
					new SecureRandom());
				SSLEngine engine = instance.createSSLEngine();
				engine.setUseClientMode(false);
				return new SslHandler(engine);
			});
	}

	@Override
	protected ByteChannel getCommsChannel(URI uri) {

		try {
			SSLEngine sslEngine = getConfiguredSSLEngine();

			SocketChannel sc = SocketChannel.open();
			sc.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
			sc.configureBlocking(false);

			sslEngine.beginHandshake();

			ByteBuffer tmpA = ByteBuffer.allocate(1 << 16);
			ByteBuffer tmpB = ByteBuffer.allocate(1 << 18);

			outer: for (int i = 0; i < 100; i++) {
				switch (sslEngine.getHandshakeStatus()) {
					case FINISHED :
					case NOT_HANDSHAKING :
						break outer;
					case NEED_TASK :
						sslEngine.getDelegatedTask()
							.run();
						break;
					case NEED_UNWRAP :
						read: for (; i < 100;) {
							switch (sc.read(tmpA)) {
								case -1 :
									throw new IOException("Unexpected end of stream");
								default :
									tmpA.flip();
									SSLEngineResult unwrap = sslEngine.unwrap(tmpA, tmpB);
									switch (unwrap.getStatus()) {
										case BUFFER_UNDERFLOW :
											tmpA.position(tmpA.limit());
											tmpA.limit(tmpA.capacity());
											Thread.sleep(1000);
											continue read;
										case BUFFER_OVERFLOW :
											throw new IOException("tmpB should always be big enough!");
										case OK :
											tmpA.compact();
											continue outer;
										case CLOSED :
											throw new IOException("Stream unexpectedly closed");
									}
							}
							i++;
						}
						break;
					case NEED_WRAP :
						SSLEngineResult wrap = sslEngine.wrap(tmpA, tmpB);
						switch (wrap.getStatus()) {
							case BUFFER_UNDERFLOW :
								throw new IllegalStateException("No bytes from A should be needed");
							case BUFFER_OVERFLOW :
								throw new IOException("tmpB should always be big enough!");
							case OK :
								tmpB.flip();
								write: for (; i < 100;) {
									sc.write(tmpB);
									if (!tmpB.hasRemaining()) {
										tmpB.compact();
										break write;
									}
									i++;
								}
								continue outer;
							case CLOSED :
								throw new IOException("Engine is closed");
						}

						break;
					default :
						throw new IllegalStateException("Unknown status " + sslEngine.getHandshakeStatus());

				}

			}

			HandshakeStatus status = sslEngine.getHandshakeStatus();
			if (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING)
				throw new IllegalStateException(sslEngine.getHandshakeStatus()
					.toString());

			return new Wrapper(sc, sslEngine);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected abstract SSLEngine getConfiguredSSLEngine() throws NoSuchAlgorithmException, KeyManagementException;

	private static class Wrapper implements ByteChannel {

		private final SocketChannel	sc;

		private final SSLEngine		engine;

		public Wrapper(SocketChannel sc, SSLEngine engine) {
			this.sc = sc;
			this.engine = engine;
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			ByteBuffer buffer = ByteBuffer.allocate(1 << 18);
			int read = sc.read(buffer);
			if (read == 0)
				return 0;

			buffer.flip();
			SSLEngineResult unwrap = engine.unwrap(buffer, dst);

			if (unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				buffer.position(buffer.limit());
				buffer.limit(buffer.capacity());
				sc.read(buffer);
				buffer.flip();
				unwrap = engine.unwrap(buffer, dst);
			}

			if (unwrap.getStatus() != Status.OK) {
				throw new RuntimeException(unwrap.toString());
			}
			return unwrap.bytesProduced();
		}

		@Override
		public boolean isOpen() {
			return sc.isOpen();
		}

		@Override
		public void close() throws IOException {
			engine.closeOutbound();
			ByteBuffer src = ByteBuffer.allocate(1024);
			ByteBuffer dst = ByteBuffer.allocate(1024);

			engine.wrap(src, dst);
			sc.write(dst);

			sc.read(src);
			engine.unwrap(src, dst);
			sc.close();
			engine.closeInbound();
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			ByteBuffer buffer = ByteBuffer.allocate(src.remaining() + 1 << 18);
			SSLEngineResult wrap = engine.wrap(src, buffer);
			if (wrap.getStatus() != Status.OK) {
				throw new RuntimeException(wrap.toString());
			}
			buffer.flip();
			sc.write(buffer);
			if (buffer.hasRemaining()) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
				sc.write(buffer);
			}

			if (buffer.hasRemaining())
				throw new IOException("Unable to send the buffered data");

			return wrap.bytesConsumed();
		}

	}
}
