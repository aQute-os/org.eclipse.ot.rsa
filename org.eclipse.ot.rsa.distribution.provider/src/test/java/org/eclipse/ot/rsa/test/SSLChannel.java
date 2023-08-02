package org.eclipse.ot.rsa.test;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.ot.rsa.distribution.util.Utils;

import io.netty.handler.ssl.SslHandler;

public class SSLChannel {
	final KeyManagerFactory		keyManagerFactory;
	final TrustManagerFactory	trustManagerFactory;

	static {
		System.setProperty("javax.net.debug", "al" + "" + "" + "" + "" + "" + "555555555555555555555");
	}

	public SSLChannel(String keyStorePath, char[] keyStorePw, String tustStorePath, char[] trustStorePw) {
		try {
			keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

			KeyStore keystore = KeyStore.getInstance("JKS");
			keystore.load(new FileInputStream(keyStorePath), keyStorePw);
			keyManagerFactory.init(keystore, "paremus".toCharArray());

			trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

			KeyStore truststore = KeyStore.getInstance("JKS");
			truststore.load(new FileInputStream(tustStorePath), trustStorePw);
			trustManagerFactory.init(truststore);
		} catch (Exception e) {
			throw Utils.duck(e);
		}

	}

	public SslHandler getServer() {
		try {
			SSLContext instance = SSLContext.getInstance("TLSv1.2");
			instance.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(),
				new SecureRandom());
			SSLEngine engine = instance.createSSLEngine();
			engine.setUseClientMode(false);
			return new SslHandler(engine);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Function<URI, ByteChannel> getClientChannel(boolean auth) {
		final AtomicBoolean closed = new AtomicBoolean();
		return uri -> {
			try {
				SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
				sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(),
					new SecureRandom());

				SSLSocketFactory factory = sslContext.getSocketFactory();
				SSLSocket socket = (SSLSocket) factory.createSocket(uri.getHost(), uri.getPort());
				socket.startHandshake();

				// Strangely enough, it is really hard to get an SSL Channel in
				// Java :-)
				// this is an inefficient wrapper but the older code had a lot
				// of complicate handling that was error prone. This is
				// inefficient but lets the Java code do the SSL work. And this
				// is only used in testing.

				return new ByteChannel() {

					@Override
					public int write(ByteBuffer src) throws IOException {
						try {
							byte[] array = new byte[src.remaining()];
							src.get(array);
							socket.getOutputStream()
								.write(array);
							socket.getOutputStream()
								.flush();
							return array.length;
						} catch (IOException e) {
							if (closed.get())
								return -1;
							throw e;
						}
					}

					@Override
					public boolean isOpen() {
						return socket.isConnected();
					}

					@Override
					public void close() throws IOException {
						if (closed.getAndSet(true) == false)
							socket.close();
					}

					@Override
					public int read(ByteBuffer dst) throws IOException {
						try {
							byte[] data = new byte[60_000];
							int read = socket.getInputStream()
								.read(data, 0, dst.remaining());
							if (read != -1) {
								dst.put(data, 0, read);
							}
							return read;
						} catch (IOException e) {
							if (closed.get())
								return -1;
							throw e;
						}
					}
				};
			} catch (Exception e) {
				throw Utils.duck(e);
			}
		};
	}

}
