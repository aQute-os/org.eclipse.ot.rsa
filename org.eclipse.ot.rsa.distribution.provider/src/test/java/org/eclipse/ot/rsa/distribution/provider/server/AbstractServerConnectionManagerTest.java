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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_NO_METHOD;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_NO_SERVICE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_RESPONSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_DESERIALIZE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.SIZE_WIDTH_IN_BYTES;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.SUCCESS_RESPONSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;
import static org.osgi.util.promise.Promises.failed;
import static org.osgi.util.promise.Promises.resolved;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;

import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.eclipse.ot.rsa.distribution.provider.serialize.CompletedPromise;
import org.eclipse.ot.rsa.distribution.provider.serialize.SerializationType;
import org.eclipse.ot.rsa.distribution.provider.serialize.CompletedPromise.State;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.test.AbstractLeakCheckingTest;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;
import org.eclipse.ot.rsa.tls.netty.provider.tls.ParemusNettyTLS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.util.converter.Converters;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class AbstractServerConnectionManagerTest extends AbstractLeakCheckingTest {

	private static final String TEST_STRING = "Hello World!";

	protected static final UUID SERVICE_ID = new UUID(123, 456);
	@Mock
	protected ParemusNettyTLS tls;
	@Mock
	protected Bundle hostBundle;

	protected ServerTestService serviceObject;
	protected ServerTestService mockServiceObject;

	protected Serializer serializer;

	protected ServerConnectionManager scm;
	protected RemotingProvider rp;
	protected URI serviceUri;
	protected EventLoopGroup ioWorker;
	protected DefaultEventLoop worker;
	protected Timer timer;
	protected Method[] methodMappings;

	@BeforeEach
	public final void setUp() throws Exception {
		System.setProperty("java.net.preferIPv4Stack", "true");

		childSetUp();
		ioWorker = new NioEventLoopGroup(1);
		worker = new DefaultEventLoop(Executors.newSingleThreadExecutor());
		timer = new HashedWheelTimer();

		scm = new ServerConnectionManager(Converters.standardConverter()
				.convert(getConfig()).to(TransportConfig.class), tls, PooledByteBufAllocator.DEFAULT, ioWorker, timer);
		rp = scm.getConfiguredProviders().get(0);

		serializer = Mockito.spy(SerializationType.FAST_BINARY.getFactory().create(hostBundle));

		serviceObject = new ServerTestServiceImpl(TEST_STRING);
		mockServiceObject = Mockito.spy(serviceObject);


		methodMappings = new Method[5];
		methodMappings[0] = CharSequence.class.getMethod("length");
		methodMappings[1] = CharSequence.class.getMethod("subSequence", int.class, int.class);
		methodMappings[2] = ServerTestService.class.getMethod("subSequence", Promise.class, CompletionStage.class);
		methodMappings[3] = ServerTestService.class.getMethod("streamOfCharacters", int.class);
		methodMappings[4] = ServerTestService.class.getMethod("reusableStreamOfCharacters", int.class);

		ServiceInvoker invoker = new ServiceInvoker(rp, SERVICE_ID, serializer, serviceObject, methodMappings, worker, timer);

		serviceUri = rp.registerService(SERVICE_ID, invoker).iterator().next();
	}

	@AfterEach
	public final void tearDown() throws InterruptedException {
		long start = System.currentTimeMillis();
		scm.close();
		timer.stop();
		ioWorker.shutdownGracefully(100, 500, MILLISECONDS);
		worker.shutdownGracefully(100, 500, MILLISECONDS);
		ioWorker.awaitTermination(500, MILLISECONDS);
		worker.awaitTermination(500, MILLISECONDS);
		System.out.println("Took: " + (System.currentTimeMillis() - start));
	}

	private Map<String, Object> getConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put("server.bind.address", "127.0.0.1");
		config.putAll(
				getExtraConfig());
		return config;
	}

	protected void childSetUp() throws Exception {}

	protected abstract Map<String, Object> getExtraConfig();


	protected abstract ByteChannel getCommsChannel(URI uri);

	@Test
	public void testSimpleCall() throws IOException {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)0);
		buffer.put((byte)0);
		buffer.flip();

		sendData(channel, buffer);

 		ByteBuffer returned = doRead(channel);

 		assertEquals(SUCCESS_RESPONSE, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());
 		assertEquals(serviceObject.length(), returned.get());
	}

	@Test
	public void testSimpleNoMethod() throws IOException {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)9);
		buffer.put((byte)0);
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_NO_METHOD, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
	}

	@Test
	public void testSimpleNoService() throws IOException {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(654);
		buffer.putInt(789);
		buffer.putShort((short)1);
		buffer.put((byte)0);
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_NO_SERVICE, returned.get());
		assertEquals(new UUID(123, 654), new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
	}

	@Test
	public void testComplexCall() throws IOException, ClassNotFoundException {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)1);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {1, 11});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

 		ByteBuffer returned = doRead(channel);

 		assertEquals(SUCCESS_RESPONSE, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());

 		assertEquals("ello World", serializer.deserializeReturn(Unpooled.wrappedBuffer(returned)));
	}

	@Test
	public void testComplexFailingCall() throws IOException, ClassNotFoundException {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)1);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {-1, 11});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());

		Exception failure = (Exception) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
		//We have to get the cause as the mock service throws
		assertTrue(failure instanceof StringIndexOutOfBoundsException, failure.getClass().getName());
	}

	@Test
	public void testFailureToDeserialize() throws IOException, ClassNotFoundException {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)1);
		buffer.put((byte)42);
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_TO_DESERIALIZE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
	}

	@Test
	public void testFailureToSerializeSuccess() throws IOException, ClassNotFoundException {

		Mockito.doThrow(IOException.class).when(serializer).serializeReturn(ArgumentMatchers.any(), ArgumentMatchers.any());

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)1);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {1, 11});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_TO_SERIALIZE_SUCCESS, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		ByteBuf buf = Unpooled.wrappedBuffer(returned);
		CharSequence message = buf.readCharSequence(buf.readUnsignedShort(), StandardCharsets.UTF_8);
		assertNotNull(message);
	}

	@Test
	public void testFailureToSerializeFailure() throws IOException, ClassNotFoundException {

		Mockito.doThrow(IOException.class).when(serializer).serializeReturn(ArgumentMatchers.any(), ArgumentMatchers.any());

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)1);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {-1, 11});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_TO_SERIALIZE_FAILURE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		ByteBuf buf = Unpooled.wrappedBuffer(returned);
		CharSequence message = buf.readCharSequence(buf.readUnsignedShort(), StandardCharsets.UTF_8);
		assertNotNull(message);
	}

	@Test
	public void testComplexCallFireAndForget() throws IOException, ClassNotFoundException {

		rp.registerService(SERVICE_ID,
				new ServiceInvoker(rp, SERVICE_ID, serializer, mockServiceObject, methodMappings, worker, timer));

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITHOUT_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)1);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {1, 11});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

 		Mockito.verify(mockServiceObject, timeout(1000)).subSequence(1, 11);
	}

	public interface TestService {
		Promise<Integer> length();
	}

	@Test
	public void testSimpleCallReturnsSuccessPromise() throws Exception {

		TestService serviceToUse = mock(TestService.class);
		when(serviceToUse.length()).thenReturn(resolved(42));
		methodMappings = new Method[1];
		methodMappings[0] = serviceToUse.getClass().getMethod("length");

		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID,
				new ServiceInvoker(rp, SERVICE_ID, serializer, serviceToUse, methodMappings, worker, timer)).iterator().next());

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)0);
		buffer.put((byte)0);
		buffer.flip();

		sendData(channel, buffer);

 		ByteBuffer returned = doRead(channel);

 		assertEquals(SUCCESS_RESPONSE, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());
 		assertEquals(42, returned.get());
	}

	@Test
	public void testSimpleCallReturnsFailedPromise() throws Exception {

		TestService serviceToUse = mock(TestService.class);
		when(serviceToUse.length()).thenReturn(failed(new StringIndexOutOfBoundsException()));
		methodMappings = new Method[1];
		methodMappings[0] = serviceToUse.getClass().getMethod("length");

		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID,
				new ServiceInvoker(rp, SERVICE_ID, serializer, serviceToUse, methodMappings, worker, timer)).iterator().next());

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)0);
		buffer.put((byte)0);
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());

		Exception failure = (Exception) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
		//We have to get the cause as the mock service throws
		assertTrue(failure instanceof StringIndexOutOfBoundsException, failure.getClass().getName());
	}

	@Test
	public void testSimpleCallReturnsSuccessPromiseDifferentClassSpace() throws Exception {

		ClassLoader classLoader = getSeparateClassLoader();
		Class<?> testServiceClass = classLoader.loadClass(TestService.class.getName());

		Object serviceToUse = Proxy.newProxyInstance(classLoader, new Class[] {testServiceClass},
				(o,m,a) -> {
					if(m.getName().equals("length")) {
						return classLoader.loadClass(Promises.class.getName())
								.getMethod("resolved", Object.class).invoke(null, 42);
					} else {
						throw new UnsupportedOperationException(m.toGenericString());
					}

				});

		serializer = Mockito.spy(SerializationType.FAST_BINARY.getFactory().create(hostBundle));

		methodMappings = new Method[1];
		methodMappings[0] = testServiceClass.getMethod("length");

		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID,
				new ServiceInvoker(rp, SERVICE_ID, serializer, serviceToUse, methodMappings, worker, timer)).iterator().next());

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)0);
		buffer.put((byte)0);
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(SUCCESS_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		assertEquals(42, returned.get());
	}

	@Test
	public void testSimpleCallReturnsFailedPromiseDifferentClassSpace() throws Exception {

		ClassLoader classLoader = getSeparateClassLoader();
		Class<?> testServiceClass = classLoader.loadClass(TestService.class.getName());

		Object serviceToUse = Proxy.newProxyInstance(classLoader, new Class[] {testServiceClass},
				(o,m,a) -> {
					if(m.getName().equals("length")) {
						return classLoader.loadClass(Promises.class.getName())
								.getMethod("failed", Throwable.class).invoke(null, new StringIndexOutOfBoundsException());
					} else {
						throw new UnsupportedOperationException(m.toGenericString());
					}

				});

		serializer = Mockito.spy(SerializationType.FAST_BINARY.getFactory().create(hostBundle));
		methodMappings = new Method[1];
		methodMappings[0] = testServiceClass.getMethod("length");

		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID,
				new ServiceInvoker(rp, SERVICE_ID, serializer, serviceToUse, methodMappings, worker, timer)).iterator().next());

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)0);
		buffer.put((byte)0);
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(FAILURE_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());

		Exception failure = (Exception) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
		//We have to get the cause as the mock service throws
		assertTrue(failure instanceof StringIndexOutOfBoundsException, failure.getClass().getName());
	}

	@Test
	public void testComplexCallReturnsFutureAndAcceptsCompletedAsyncArgs() throws Exception {

		CompletedPromise start = new CompletedPromise();
		start.state = State.SUCCEEDED;
		start.value = 1;

		CompletedPromise end = new CompletedPromise();
		end.state = State.SUCCEEDED;
		end.value = 11;

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(256);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)2);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()),
				new Object[] {start, end});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

 		ByteBuffer returned = doRead(channel);

 		assertEquals(SUCCESS_RESPONSE, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());
 		assertEquals("ello World", serializer.deserializeReturn(Unpooled.wrappedBuffer(returned)));
	}

	@Test
	public void testComplexCallReturnsFutureAndAcceptsUnCompletedAsyncArgs() throws Exception {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort((short)2);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()),
				new Object[] {null, null});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned;
		try {
			returned = doRead(channel);
			fail("Should not get a response");
		} catch (Exception e) {
			assertEquals("No response received", e.getMessage());
		}

		buffer = ByteBuffer.allocate(64);
		buffer.put(Protocol_V2.VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(Protocol_V2.ASYNC_METHOD_PARAM_DATA);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.put((byte) 0);
		buffer.put((byte) 1);
		buffer.flip();

		sendData(channel, buffer);

		try {
			returned = doRead(channel);
			fail("Should not get a response");
		} catch (Exception e) {
			assertEquals("No response received", e.getMessage());
		}

		buffer = ByteBuffer.allocate(64);
		buffer.put(Protocol_V2.VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(Protocol_V2.ASYNC_METHOD_PARAM_DATA);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.put((byte) 1);
		buffer.put((byte) 11);
		buffer.flip();

		sendData(channel, buffer);

		returned = doRead(channel);

		assertEquals(SUCCESS_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		assertEquals("ello World", serializer.deserializeReturn(Unpooled.wrappedBuffer(returned)));
	}

	@Test
	public void testSimpleCallReturnsPushStream() throws Exception {
		doTestSimpleCallReturnsStream((short)3);
	}

	private void doTestSimpleCallReturnsStream(short method) throws Exception {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort(method);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {32});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

 		ByteBuffer returned = doRead(channel);

 		assertEquals(SUCCESS_RESPONSE, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());
 		Object[] result = (Object[]) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
 		assertEquals(SERVICE_ID, result[0]);
 		assertEquals(789, result[1]);


 		buffer = ByteBuffer.allocate(64);
		buffer.put(Protocol_V2.VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(Protocol_V2.CLIENT_OPEN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.flip();

		sendData(channel, buffer);


		for(Character c : TEST_STRING.toCharArray()) {
			checkData(channel, c);
		}

 		returned = doRead(channel, (byte) 2);

 		assertEquals(Protocol_V2.SERVER_CLOSE_EVENT, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());
	}

	@Test
	public void testSimpleCallReturnsPushStreamEarlyCloseFromClient() throws Exception {
		doTestSimpleCallReturnsStreamEarlyCloseFromClient((short)3);
	}

	private void doTestSimpleCallReturnsStreamEarlyCloseFromClient(short method) throws Exception {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort(method);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {32});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(SUCCESS_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		Object[] result = (Object[]) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
		assertEquals(SERVICE_ID, result[0]);
		assertEquals(789, result[1]);


		buffer = ByteBuffer.allocate(64);
		buffer.put(Protocol_V2.VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(Protocol_V2.CLIENT_OPEN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.flip();

		sendData(channel, buffer);


		for(Character c : TEST_STRING.substring(0, 5).toCharArray()) {
			checkData(channel, c);
		}

		buffer = ByteBuffer.allocate(64);
		buffer.put(Protocol_V2.VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(Protocol_V2.CLIENT_CLOSE);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.flip();

		sendData(channel, buffer);

		buffer = ByteBuffer.allocate(1024);
		for (int i = 0; i < 20; i++) {
			buffer.clear();
			assertEquals(0, channel.read(buffer));
			Thread.sleep(100);
		}
	}

	@Test
	public void testSimpleCallReturnsPushStreamEarlyCloseWithError() throws Exception {
		doTestSimpleCallReturnsStreamEarlyCloseWithError((short)3);
	}

	private void doTestSimpleCallReturnsStreamEarlyCloseWithError(short method) throws Exception {

		ByteChannel channel = getCommsChannel(serviceUri);

		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(CALL_WITH_RETURN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.putShort(method);
		ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(buffer);
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {8});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();

		sendData(channel, buffer);

		ByteBuffer returned = doRead(channel);

		assertEquals(SUCCESS_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		Object[] result = (Object[]) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
		assertEquals(SERVICE_ID, result[0]);
		assertEquals(789, result[1]);


		buffer = ByteBuffer.allocate(64);
		buffer.put(Protocol_V2.VERSION);
		for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
			buffer.put((byte)0);
		}
		buffer.put(Protocol_V2.CLIENT_OPEN);
		buffer.putLong(123);
		buffer.putLong(456);
		buffer.putInt(789);
		buffer.flip();

		sendData(channel, buffer);


		for(Character c : TEST_STRING.substring(0, 8).toCharArray()) {
			checkData(channel, c);
		}

		returned = doRead(channel, (byte) 2);

 		assertEquals(Protocol_V2.SERVER_ERROR_EVENT, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());
 		ArrayIndexOutOfBoundsException aioobe = (ArrayIndexOutOfBoundsException) serializer
 				.deserializeReturn(Unpooled.wrappedBuffer(returned));
 		assertEquals("Failed after 8", aioobe.getMessage());
	}

	@Test
	public void testSimpleCallReturnsPushEventSource() throws Exception {
		doTestSimpleCallReturnsStream((short)4);
	}

	@Test
	public void testSimpleCallReturnsPushEventSourceEarlyCloseFromClient() throws Exception {
		doTestSimpleCallReturnsStreamEarlyCloseFromClient((short)4);
	}

	@Test
	public void testSimpleCallReturnsPushEventSourceEarlyCloseWithError() throws Exception {
		doTestSimpleCallReturnsStreamEarlyCloseWithError((short)4);
	}

	private void checkData(ByteChannel channel, Character character) throws IOException, ClassNotFoundException {
		ByteBuffer returned;
		returned = doRead(channel, (byte) 2);

		assertEquals(Protocol_V2.SERVER_DATA_EVENT, returned.get());
 		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
 		assertEquals(789, returned.getInt());
 		assertEquals(character, serializer.deserializeReturn(Unpooled.wrappedBuffer(returned)));
	}

	private void sendData(ByteChannel channel, ByteBuffer buffer) throws IOException {
		buffer.putShort(buffer.position() + 2, (short) (buffer.remaining() - 4));
 		channel.write(buffer);
	}

	private ByteBuffer doRead(ByteChannel channel) throws IOException {
		return doRead(channel, (byte) 1);
	}

	private ByteBuffer doRead(ByteChannel channel, byte version) throws IOException {
 		ByteBuffer buffer = ByteBuffer.allocate(8192);

 		int loopCount = 0;
 		do {
 			channel.read(buffer);
 			if(buffer.position() >= 4) {
 				if(buffer.get(0) != version) {
 					throw new IllegalArgumentException("" + buffer.get(0));
 				}
 				if(buffer.getShort(2) == (buffer.position() - 4)) {
 					break;
 				}
 			}
 			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
 		} while(loopCount++ < 20);

 		if(buffer.position() < 4) {
 			throw new IllegalArgumentException("No response received");
 		}

 		if(buffer.getShort(2) != buffer.position() - 4) {
 			throw new IllegalArgumentException("The buffer was the wrong size: " + (buffer.position() - 4) +
 					" expected: " + buffer.getShort(2));
		}
 		buffer.flip();
 		buffer.position(4);
 		return buffer;
	}

	private ClassLoader getSeparateClassLoader() {
		return new ClassLoader() {
			private final Map<String, Class<?>> cache = new HashMap<>();

    		@Override
			public Class<?> loadClass(String name) throws ClassNotFoundException {
    			if(name.startsWith("java")) {
    				return super.loadClass(name);
    			}
    			Class<?> c = cache.get(name);
    			if(c != null) return c;

    			String resourceName = name.replace('.', '/') + ".class";

				InputStream resourceAsStream = AbstractServerConnectionManagerTest.this.getClass()
						.getClassLoader().getResourceAsStream(resourceName);
				if(resourceAsStream == null) throw new ClassNotFoundException(name);
				try(InputStream is = resourceAsStream) {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					byte[] b = new byte[4096];

					int i = 0;
					while((i = is.read(b)) > -1) {
						baos.write(b, 0, i);
					}
					c = defineClass(name, baos.toByteArray(), 0, baos.size());
				} catch (IOException e) {
					throw new ClassNotFoundException(name, e);
				}
				cache.put(name, c);
				return c;
			}
		};
	}

	protected String selectProtocol(String ipv6, String ipv4) {
		String protocol;
		try(DatagramSocket ds = new DatagramSocket(0, InetAddress.getByName("::"))) {
			protocol = ipv6;
		} catch (SocketException e) {
			System.out.println("IPV6 not supported");
			protocol = ipv4;
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
		return protocol;
	}

}
