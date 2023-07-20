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
package org.eclipse.ot.rsa.distribution.provider.client;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CANCEL;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_NO_METHOD;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_NO_SERVICE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_DESERIALIZE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.SUCCESS_RESPONSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.VERSION;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_DATA;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.eclipse.ot.rsa.distribution.provider.impl.ImportRegistrationImpl;
import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage;
import org.eclipse.ot.rsa.distribution.provider.promise.PromiseFactory;
import org.eclipse.ot.rsa.distribution.provider.serialize.CompletedPromise;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializerFactory;
import org.eclipse.ot.rsa.distribution.provider.test.AbstractLeakCheckingTest;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;
import org.eclipse.ot.rsa.tls.netty.provider.tls.ParemusNettyTLS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceException;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.util.converter.Converters;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promises;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Promise;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class AbstractClientConnectionManagerTest extends AbstractLeakCheckingTest {

	public static class ClientException extends Exception {

		private static final long serialVersionUID = -1273488084147564822L;

		public ClientException(String message) {
			super(message);
		}
	}

	ClientConnectionManager		clientConnectionManager;

	@Mock
	ParemusNettyTLS				tls;
	@Mock
	ImportRegistrationImpl		ir;
	@Mock
	EndpointDescription			ed;
	@Mock
	Bundle						classSpace;

	EventLoopGroup				ioWorker;
	EventExecutorGroup			executor;

	Timer						timer;

	Supplier<Promise<Object>>	nettyPromiseSupplier;

	@BeforeEach
	public final void setUp() throws Exception {

		Map<String, Object> config = getConfig();

		Mockito.when(ed.getId())
			.thenReturn(new UUID(12, 34).toString());
		Mockito.when(ir.getId())
			.thenReturn(new UUID(12, 34));
		ioWorker = new NioEventLoopGroup(1);
		executor = new DefaultEventExecutorGroup(1);
		timer = new HashedWheelTimer();

		nettyPromiseSupplier = () -> executor.next()
			.newPromise();

		clientConnectionManager = new ClientConnectionManager(Converters.standardConverter()
			.convert(config)
			.to(TransportConfig.class), tls, PooledByteBufAllocator.DEFAULT, ioWorker, executor, timer);
	}

	@AfterEach
	public final void tearDown() throws IOException {
		clientConnectionManager.close();

		ioWorker.shutdownGracefully();
		executor.shutdownGracefully();
		timer.stop();
	}

	protected abstract Map<String, Object> getConfig();

	@Test
	public void testSimpleNoArgsCallVoidReturnTCP() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			return new byte[] {
				VERSION, 0, 0, 22, SUCCESS_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
				(byte) callId, -1
			};
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		assertNull(p.get());
	}

	@Test
	public void testSimpleNoArgsCallExceptionReturnTCP() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			out.writeBytes(new byte[] {
				VERSION, 0, 0, 0, Protocol_V1.FAILURE_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0,
				0, (byte) callId
			});

			try {
				new VanillaRMISerializerFactory().create(classSpace)
					.serializeReturn(out, new ClientException("bang!"));
			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}

			out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);
			byte[] b3 = new byte[out.readableBytes()];
			out.readBytes(b3);
			return b3;
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		assertEquals("bang!", p.await()
			.cause()
			.getMessage());
	}

	@Test
	public void testWithArgsCallAndReturnTCP() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(8, buf.readShort());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object[] args = serializer.deserializeArgs(buf);

				assertEquals(0, buf.readableBytes());

				assertEquals(3, args.length);
				assertEquals(Integer.valueOf(1), args[0]);
				assertEquals(Long.valueOf(7), args[1]);
				assertEquals("forty-two", args[2]);

				ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
				out.writeBytes(new byte[] {
					VERSION, 0, 0, 0, SUCCESS_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
					(byte) callId
				});

				serializer.serializeReturn(out, new URL("http://www.example.com"));

				out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);
				byte[] b2 = new byte[out.readableBytes()];
				out.readBytes(b2);
				return b2;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 8, 0, new Object[] {
			1, 7L, "forty-two"
		}, new int[0], new int[0], new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000),
			"testing"));

		assertEquals(new URL("http://www.example.com"), p.get());
	}

	@Test
	public void testFireAndForgetCallWithArgs() throws Exception {

		Semaphore sem = new Semaphore(0);

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITHOUT_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(8, buf.readShort());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object[] args = serializer.deserializeArgs(buf);

				assertEquals(0, buf.readableBytes());

				assertEquals(3, args.length);
				assertEquals(Integer.valueOf(1), args[0]);
				assertEquals(Long.valueOf(7), args[1]);
				assertEquals("forty-two", args[2]);

				sem.release();

				return null;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(false, UUID.fromString(ed.getId()), 8, 0, new Object[] {
			1, 7L, "forty-two"
		}, new int[0], new int[0], new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000),
			"testing"));

		assertTrue(sem.tryAcquire(1, 2, TimeUnit.SECONDS));
	}

	@Test
	public void testCancelCallWithArgs() throws Exception {

		Semaphore sem = new Semaphore(0);

		AtomicInteger callIdSent = new AtomicInteger();

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			callIdSent.set(callId);
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(8, buf.readShort());

			return null;
		}, b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CANCEL, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(buf.readBoolean());
			assertEquals(0, buf.readableBytes());
			assertEquals(callIdSent.get(), callId);
			sem.release();
			return null;
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 8, 0, new Object[] {
			1, 7L, "forty-two"
		}, new int[0], new int[0], new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000),
			"testing"));

		p.cancel(true);
		assertTrue(sem.tryAcquire(1, 2, TimeUnit.SECONDS));
	}

	@Test
	public void testTimeout() throws Exception {

		String uri = runTCPServer(b -> {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException ie) {}
			return null;
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		Throwable t = p.await()
			.cause();

		assertTrue(t instanceof ServiceException, String.valueOf(t));
		assertEquals(ServiceException.REMOTE, ((ServiceException) t).getType(), "Not a remote ServiceException");
		assertTrue(t.getCause() instanceof TimeoutException, String.valueOf(t.getCause()));
	}

	@Test
	public void testMissingService() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			return new byte[] {
				VERSION, 0, 0, 21, FAILURE_NO_SERVICE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
				(byte) callId
			};
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		Throwable failure = p.await()
			.cause();
		assertTrue(failure instanceof ServiceException, failure.getMessage());
		assertEquals(ServiceException.REMOTE, ((ServiceException) failure).getType());
		assertTrue(failure.getCause() instanceof MissingServiceException, failure.getCause()
			.getMessage());

		verify(ir, timeout(200)).asyncFail(argThat(isRemoteException(MissingServiceException.class)));
	}

	@Test
	public void testMissingMethod() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			return new byte[] {
				VERSION, 0, 0, 21, FAILURE_NO_METHOD, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
				(byte) callId
			};
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "touch[]"));

		Throwable failure = p.await()
			.cause();
		assertTrue(failure instanceof ServiceException, failure.getMessage());
		assertEquals(ServiceException.REMOTE, ((ServiceException) failure).getType());
		assertTrue(failure.getCause() instanceof MissingMethodException, failure.getCause()
			.getMessage());
		assertTrue(failure.getCause()
			.getMessage()
			.contains("touch[]"),
			failure.getCause()
				.getMessage());

		verify(ir, timeout(200)).asyncFail(argThat(isRemoteException(MissingMethodException.class)));
	}

	@Test
	public void testFailureToDeserialize() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			return new byte[] {
				VERSION, 0, 0, 28, FAILURE_TO_DESERIALIZE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
				(byte) callId,
				// Bang!
				0, 5, 66, 97, 110, 103, 33
			};
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		Throwable failure = p.await()
			.cause();
		assertTrue(failure instanceof ServiceException, failure.getMessage());
		assertEquals(ServiceException.REMOTE, ((ServiceException) failure).getType());
		assertTrue(failure.getCause() instanceof IllegalArgumentException, failure.getCause()
			.getClass()
			.getName());
		assertEquals("Bang!", failure.getCause()
			.getMessage());
	}

	@Test
	public void testFailureToSerializeSuccess() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			return new byte[] {
				VERSION, 0, 0, 28, FAILURE_TO_SERIALIZE_SUCCESS, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0,
				0, (byte) callId,
				// Bang!
				0, 5, 66, 97, 110, 103, 33
			};
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		Throwable failure = p.await()
			.cause();
		assertTrue(failure instanceof ServiceException, failure.getMessage());
		assertEquals(ServiceException.REMOTE, ((ServiceException) failure).getType());
		assertTrue(failure.getMessage()
			.contains("succeeded"), failure.getMessage());
		assertTrue(failure.getCause() instanceof IllegalArgumentException, failure.getCause()
			.getClass()
			.getName());
		assertEquals("Bang!", failure.getCause()
			.getMessage());
	}

	@Test
	public void testFailureToSerializeFailure() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			return new byte[] {
				VERSION, 0, 0, 28, FAILURE_TO_SERIALIZE_FAILURE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0,
				0, (byte) callId,
				// Bang!
				0, 5, 66, 97, 110, 103, 33
			};
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		Throwable failure = p.await()
			.cause();
		assertTrue(failure instanceof ServiceException, failure.getMessage());
		assertEquals(ServiceException.REMOTE, ((ServiceException) failure).getType());
		assertTrue(failure.getMessage()
			.contains("failed"), failure.getMessage());
		assertTrue(failure.getCause() instanceof IllegalArgumentException, failure.getCause()
			.getClass()
			.getName());
		assertEquals("Bang!", failure.getCause()
			.getMessage());
	}

	@Test
	public void testTwoCallsWithDisconnection() throws Exception {

		Function<byte[], byte[]> doSimpleVoidCall = b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(7, buf.readShort());
			assertEquals(0, buf.readByte());
			assertEquals(0, buf.readableBytes());

			return new byte[] {
				VERSION, 0, 0, 22, SUCCESS_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
				(byte) callId, -1
			};
		};
		String uri = runTCPServer(true, doSimpleVoidCall, doSimpleVoidCall);

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p, new AtomicLong(3000), "testing"));

		assertNull(p.get());

		// Trigger an error then Topology manager close
		verify(ir, timeout(500)).asyncFail(any(ServiceException.class));
		clientConnectionManager.notifyClosing(ir);
		assertTrue(ch.closeFuture()
			.await(500));

		Channel ch2 = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch2);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p2 = nettyPromiseSupplier.get();

		// It should now work a second time
		ch2.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 7, 0, null, new int[0], new int[0],
			new VanillaRMISerializerFactory().create(classSpace), null, p2, new AtomicLong(3000), "testing"));

		assertNull(p2.get());

	}

	@Test
	public void testCallWithCompletedPromiseArgCallAndReturnTCP() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(8, buf.readShort());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object[] args = serializer.deserializeArgs(buf);

				assertEquals(0, buf.readableBytes());

				assertEquals(1, args.length);
				assertTrue(args[0] instanceof CompletedPromise);
				assertEquals(CompletedPromise.State.SUCCEEDED, ((CompletedPromise) args[0]).state);
				assertEquals(Integer.valueOf(1), ((CompletedPromise) args[0]).value);

				ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
				out.writeBytes(new byte[] {
					VERSION, 0, 0, 0, SUCCESS_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
					(byte) callId
				});

				serializer.serializeReturn(out, new URL("http://www.example.com"));

				out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);
				byte[] b2 = new byte[out.readableBytes()];
				out.readBytes(b2);
				return b2;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 8, 0, new Object[] {
			Promises.resolved(1)
		}, new int[] {
			0
		}, new int[0], new VanillaRMISerializerFactory().create(classSpace),
			PromiseFactory.osgiToNetty(org.osgi.util.promise.Promise.class), p, new AtomicLong(3000),
			"testing"));

		assertEquals(new URL("http://www.example.com"), p.get());
	}

	@Test
	public void testCallWithFailedPromiseArgCallAndReturnTCP() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(8, buf.readShort());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object[] args = serializer.deserializeArgs(buf);

				assertEquals(0, buf.readableBytes());

				assertEquals(1, args.length);
				assertTrue(args[0] instanceof CompletedPromise);
				assertEquals(CompletedPromise.State.FAILED, ((CompletedPromise) args[0]).state);
				assertTrue(((CompletedPromise) args[0]).value instanceof IOException);

				ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
				out.writeBytes(new byte[] {
					VERSION, 0, 0, 0, SUCCESS_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
					(byte) callId
				});

				serializer.serializeReturn(out, new URL("http://www.example.com"));

				out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);
				byte[] b2 = new byte[out.readableBytes()];
				out.readBytes(b2);
				return b2;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 8, 0, new Object[] {
			Promises.failed(new IOException())
		}, new int[] {
			0
		}, new int[0], new VanillaRMISerializerFactory().create(classSpace),
			PromiseFactory.osgiToNetty(org.osgi.util.promise.Promise.class), p, new AtomicLong(3000),
			"testing"));

		assertEquals(new URL("http://www.example.com"), p.get());
	}

	@Test
	public void testCallWithPendingPromiseArgLaterSuccessCallAndReturnTCP() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(8, buf.readShort());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object[] args = serializer.deserializeArgs(buf);

				assertEquals(0, buf.readableBytes());

				assertEquals(1, args.length);
				assertNull(args[0]);
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}, b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(ASYNC_METHOD_PARAM_DATA, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			// Param Index 0
			assertEquals(0, buf.readByte());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object value = serializer.deserializeReturn(buf);

				assertEquals(0, buf.readableBytes());

				assertEquals(Integer.valueOf(1), value);

				ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
				out.writeBytes(new byte[] {
					VERSION, 0, 0, 0, SUCCESS_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
					(byte) callId
				});

				serializer.serializeReturn(out, new URL("http://www.example.com"));

				out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);
				byte[] b2 = new byte[out.readableBytes()];
				out.readBytes(b2);
				return b2;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		Deferred<Integer> d = new Deferred<>();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 8, 0, new Object[] {
			d.getPromise()
		}, new int[] {
			0
		}, new int[0], new VanillaRMISerializerFactory().create(classSpace),
			PromiseFactory.osgiToNetty(org.osgi.util.promise.Promise.class), p, new AtomicLong(3000),
			"testing"));

		assertFalse(p.await(300));

		d.resolve(1);

		assertEquals(new URL("http://www.example.com"), p.get());
	}

	@Test
	public void testCallWithPendingPromiseArgLaterFailureCallAndReturnTCP() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(CALL_WITH_RETURN, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			assertEquals(8, buf.readShort());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object[] args = serializer.deserializeArgs(buf);

				assertEquals(0, buf.readableBytes());

				assertEquals(1, args.length);
				assertNull(args[0]);
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}, b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(ASYNC_METHOD_PARAM_FAILURE, buf.readByte());
			assertEquals(12, buf.readLong());
			assertEquals(34, buf.readLong());
			int callId = buf.readInt();
			assertTrue(callId < Byte.MAX_VALUE);
			// Param Index 0
			assertEquals(0, buf.readByte());

			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				Object value = serializer.deserializeReturn(buf);

				assertEquals(0, buf.readableBytes());

				assertTrue(value instanceof IOException);

				ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
				out.writeBytes(new byte[] {
					VERSION, 0, 0, 0, SUCCESS_RESPONSE, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 34, 0, 0, 0,
					(byte) callId
				});

				serializer.serializeReturn(out, new URL("http://www.example.com"));

				out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);
				byte[] b2 = new byte[out.readableBytes()];
				out.readBytes(b2);
				return b2;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		Promise<Object> p = nettyPromiseSupplier.get();

		Deferred<Integer> d = new Deferred<>();

		ch.writeAndFlush(new ClientInvocation(true, UUID.fromString(ed.getId()), 8, 0, new Object[] {
			d.getPromise()
		}, new int[] {
			0
		}, new int[0], new VanillaRMISerializerFactory().create(classSpace),
			PromiseFactory.osgiToNetty(org.osgi.util.promise.Promise.class), p, new AtomicLong(3000),
			"testing"));

		assertFalse(p.await(300));

		d.fail(new IOException());

		assertEquals(new URL("http://www.example.com"), p.get());
	}

	@Test
	public void testCallWithPushStreamReturnServerCloses() throws Exception {

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(Protocol_V2.CLIENT_OPEN, buf.readByte());
			assertEquals(123, buf.readLong());
			assertEquals(45, buf.readLong());
			assertEquals(-1, buf.readInt());
			assertEquals(0, buf.readableBytes());

			// Send four messages (3 data + close) expecting no response (zero
			// backpressure)
			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				byte[] header = new byte[] {
					2, 0, 0, 0, Protocol_V2.SERVER_DATA_EVENT, 0, 0, 0, 0, 0, 0, 0, 123, 0, 0, 0, 0, 0, 0, 0, 45, -1,
					-1, -1, -1
				};

				ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);

				out.writeBytes(header);
				serializer.serializeReturn(out, Long.valueOf(1));

				int writtenBytes = out.readableBytes();
				out.setMedium(out.readerIndex() + 1, writtenBytes - 4);

				out.writeBytes(header);
				serializer.serializeReturn(out, Long.valueOf(2));
				out.setMedium(out.readerIndex() + writtenBytes + 1, writtenBytes - 4);

				out.writeBytes(header);
				serializer.serializeReturn(out, Long.valueOf(3));
				out.setMedium(out.readerIndex() + 2 * writtenBytes + 1, writtenBytes - 4);

				out.writeBytes(header);
				out.setMedium(out.readerIndex() + 3 * writtenBytes + 1, out.readableBytes() - 3 * writtenBytes - 4);
				out.setByte(out.readerIndex() + 3 * writtenBytes + 4, Protocol_V2.SERVER_CLOSE_EVENT);

				byte[] b2 = new byte[out.readableBytes()];
				out.readBytes(b2);
				return b2;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		PushEventSource<Long> pes = pec -> {
			UUID streamId = new UUID(123, 45);
			ch.writeAndFlush(new BeginStreamingInvocation(streamId, -1,
				new VanillaRMISerializerFactory().create(classSpace), executor.next(), l -> {
					try {
						pec.accept(PushEvent.data((Long) l));
					} catch (Exception e) {}
				}, e -> {
					try {
						if (e == null) {
							pec.accept(PushEvent.close());
						} else {
							PushEvent.error(e);
						}
					} catch (Exception e2) {}
				}, null));

			return () -> ch.writeAndFlush(new EndStreamingInvocation(streamId, -1));
		};

		PushStream<Long> stream = new PushStreamProvider().createStream(pes);

		org.osgi.util.promise.Promise<List<Long>> collect = stream.collect(toList());

		assertEquals(Arrays.asList(1L, 2L, 3L), collect.getValue());
	}

	@Test
	public void testCallWithPushStreamReturnBackPressureAndServerCloses() throws Exception {

		Semaphore closeReceived = new Semaphore(0);

		BiFunction<Long, Long, Function<byte[], byte[]>> generator = (expected, returned) -> {
			return b -> {
				ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
				buf.writeBytes(b);
				assertEquals(Protocol_V2.CLIENT_BACK_PRESSURE, buf.readByte());
				assertEquals(123, buf.readLong());
				assertEquals(45, buf.readLong());
				assertEquals(-1, buf.readInt());
				long bp = buf.readLong();
				assertEquals(expected.longValue(), bp);
				assertEquals(0, buf.readableBytes());

				try {
					Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

					byte[] header = new byte[] {
						2, 0, 0, 0, Protocol_V2.SERVER_DATA_EVENT, 0, 0, 0, 0, 0, 0, 0, 123, 0, 0, 0, 0, 0, 0, 0, 45,
						-1, -1, -1, -1
					};

					ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);

					out.writeBytes(header);
					serializer.serializeReturn(out, returned);

					out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);

					byte[] b2 = new byte[out.readableBytes()];
					out.readBytes(b2);
					return b2;

				} catch (Exception e) {
					e.printStackTrace();
					return null;
				}
			};
		};

		String uri = runTCPServer(b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(Protocol_V2.CLIENT_OPEN, buf.readByte());
			assertEquals(123, buf.readLong());
			assertEquals(45, buf.readLong());
			assertEquals(-1, buf.readInt());
			assertEquals(0, buf.readableBytes());

			// Send first message
			try {
				Serializer serializer = new VanillaRMISerializerFactory().create(classSpace);

				byte[] header = new byte[] {
					2, 0, 0, 0, Protocol_V2.SERVER_DATA_EVENT, 0, 0, 0, 0, 0, 0, 0, 123, 0, 0, 0, 0, 0, 0, 0, 45, -1,
					-1, -1, -1
				};

				ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);

				out.writeBytes(header);
				serializer.serializeReturn(out, Long.valueOf(1));

				out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);

				byte[] b2 = new byte[out.readableBytes()];
				out.readBytes(b2);
				return b2;

			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		}, generator.apply(1L, 2L), generator.apply(2L, 3L), generator.apply(3L, -1L), b -> {
			ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
			buf.writeBytes(b);
			assertEquals(Protocol_V2.CLIENT_CLOSE, buf.readByte());
			assertEquals(123, buf.readLong());
			assertEquals(45, buf.readLong());
			assertEquals(-1, buf.readInt());
			assertEquals(0, buf.readableBytes());

			closeReceived.release();

			return null;
		});

		Channel ch = clientConnectionManager.getChannelFor(new URI(uri), ed);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);

		PushEventSource<Long> pes = pec -> {
			UUID streamId = new UUID(123, 45);
			ch.writeAndFlush(new BeginStreamingInvocation(streamId, -1,
				new VanillaRMISerializerFactory().create(classSpace), executor.next(), l -> {
					try {
						long backPressure = pec.accept(PushEvent.data((Long) l));
						AbstractRSAMessage<ClientMessageType> message;
						if (backPressure < 0) {
							message = new EndStreamingInvocation(streamId, -1);
							pec.accept(PushEvent.close());
						} else {
							message = new ClientBackPressure(streamId, -1, backPressure);
						}
						ch.writeAndFlush(message);
					} catch (Exception e) {}
				}, e -> {
					try {
						if (e == null) {
							pec.accept(PushEvent.close());
						} else {
							PushEvent.error(e);
						}
					} catch (Exception e2) {}
				}, null));

			return () -> ch.writeAndFlush(new EndStreamingInvocation(streamId, -1));
		};

		PushStream<Long> stream = new PushStreamProvider().buildStream(pes)
			.unbuffered()
			.build();

		List<Long> list = new Vector<>();

		org.osgi.util.promise.Promise<Long> count = stream.forEachEvent(e -> {
			if (e.isTerminal()) {
				return -1;
			}
			Long data = e.getData();
			list.add(data);
			return data.longValue();
		});

		assertEquals(5, count.getValue()
			.intValue());

		assertEquals(Arrays.asList(1L, 2L, 3L, -1L), list);

		assertTrue(closeReceived.tryAcquire(1, 500, MILLISECONDS));
	}

	@SafeVarargs
	protected final String runTCPServer(Function<byte[], byte[]>... validators) throws Exception {
		return runTCPServer(false, validators);
	}

	@SuppressWarnings("resource")
	@SafeVarargs
	protected final String runTCPServer(boolean closeInbetween, Function<byte[], byte[]>... validators)
		throws Exception {

		Semaphore sem = new Semaphore(0);
		new Thread(() -> {

			try (ServerSocket ss = getConfiguredSocket()) {
				sem.release(ss.getLocalPort());
				ss.setSoTimeout(10000000);

				Socket s = ss.accept();
				InputStream is = null;
				try {
					is = s.getInputStream();

					for (Function<byte[], byte[]> validator : validators) {
						int version = is.read();
						assertTrue(version > 0 && version < 3);
						int len = (is.read() << 16) + (is.read() << 8) + is.read();
						byte[] b = new byte[len];
						int read = 0;
						while ((read += is.read(b, read, len - read)) < len)
							;
						b = validator.apply(b);
						if (b != null) {
							s.getOutputStream()
								.write(b);
							s.getOutputStream()
								.flush();
						}

						if (closeInbetween) {
							// Wait a little before closing to avoid racing the
							// return
							Thread.sleep(100);
							is.close();
							s.close();
							s = ss.accept();
							is = s.getInputStream();
						}
					}
				} finally {
					// Wait a little before closing to avoid racing the return
					Thread.sleep(500);
					if (is != null)
						is.close();
					s.close();
				}
			} catch (Exception ioe) {
				ioe.printStackTrace();
			}
		}).start();
		assertTrue(sem.tryAcquire(1, 500, MILLISECONDS));
		return getPrefix() + (sem.drainPermits() + 1);
	}

	private ArgumentMatcher<Throwable> isRemoteException(Class<? extends Throwable> clazz) {
		return new ArgumentMatcher<Throwable>() {

			@Override
			public boolean matches(Throwable o) {
				return (o instanceof ServiceException) && ((ServiceException) o).getType() == ServiceException.REMOTE
					&& clazz.isInstance(((Exception) o).getCause());
			}
		};
	}

	protected abstract String getPrefix();

	protected abstract ServerSocket getConfiguredSocket() throws Exception;
}
