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
package com.paremus.dosgi.net.server;

import static aQute.bnd.annotation.metatype.Configurable.createConfigurable;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_METHOD;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_SERVICE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_RESPONSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_DESERIALIZE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.SIZE_WIDTH_IN_BYTES;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.SUCCESS_RESPONSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.osgi.util.promise.Promises.failed;
import static org.osgi.util.promise.Promises.resolved;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;

import com.paremus.dosgi.net.config.Config;
import com.paremus.dosgi.net.serialize.SerializationType;
import com.paremus.dosgi.net.serialize.Serializer;
import com.paremus.net.encode.EncodingSchemeFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;

@ExtendWith(MockitoExtension.class)
public abstract class AbstractServerConnectionManagerTest {

	protected static final UUID SERVICE_ID = new UUID(123, 456);
	@Mock 
	protected EncodingSchemeFactory esf;
	@Mock 
	protected Bundle hostBundle;
	@Mock 
	protected BundleWiring hostBundleWiring;
	
	protected CharSequence serviceObject;
	protected CharSequence mockServiceObject;
	
	protected Serializer serializer;
	
	protected ServerConnectionManager scm;
	protected RemotingProvider rp;
	protected URI serviceUri;
	protected DefaultEventLoop worker;
	protected Map<Integer, Method> methodMappings = new HashMap<>();
	
	@BeforeEach
	public void setUp() throws Exception {
		childSetUp();
		ResourceLeakDetector.setLevel(Level.PARANOID);
		
		scm = new ServerConnectionManager(createConfigurable(Config.class, getConfig()), esf, UnpooledByteBufAllocator.DEFAULT);
		rp = scm.getConfiguredProviders().get(0);
		
		Mockito.when(hostBundle.adapt(BundleWiring.class)).thenReturn(hostBundleWiring);
		Mockito.when(hostBundleWiring.getClassLoader()).thenReturn(getClass().getClassLoader());
		
		serializer = Mockito.spy(SerializationType.FAST_BINARY.getFactory().create(hostBundle));
		
		serviceObject = "Hello World!";
		mockServiceObject = mock(CharSequence.class, withSettings().name("serviceObject")
				.defaultAnswer(delegatesTo("Hello World!")));
		
		worker = new DefaultEventLoop(Executors.newSingleThreadExecutor());
		
		methodMappings.put(1, CharSequence.class.getMethod("length")); 
		methodMappings.put(2, CharSequence.class.getMethod("subSequence", int.class, int.class)); 
		
		ServiceInvoker invoker = new ServiceInvoker(SERVICE_ID, serializer, serviceObject, methodMappings, worker);
		
		serviceUri = rp.registerService(SERVICE_ID, invoker);
	}
	
	@AfterEach
	public void tearDown() {
		long start = System.currentTimeMillis();
		scm.close();
		System.out.println("Took: " + (System.currentTimeMillis() - start));
	}

	private Map<String, Object> getConfig() {
		Map<String, Object> config = new HashMap<String, Object>();
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
		buffer.putShort((short)1);
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
		buffer.putShort((short)2);
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
		buffer.putShort((short)2);
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
		assertTrue(failure instanceof StringIndexOutOfBoundsException, ()->failure.getClass().getName());
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
		buffer.putShort((short)2);
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
		
		Mockito.doThrow(IOException.class).when(serializer).serializeReturn(Mockito.any(), Mockito.any());
		
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
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {1, 11});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();
		
		sendData(channel, buffer);
		
		ByteBuffer returned = doRead(channel);
		
		assertEquals(FAILURE_TO_SERIALIZE_SUCCESS, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
	}

	@Test
	public void testFailureToSerializeFailure() throws IOException, ClassNotFoundException {
		
		Mockito.doThrow(IOException.class).when(serializer).serializeReturn(Mockito.any(), Mockito.any());
		
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
		serializer.serializeArgs(wrappedBuffer.writerIndex(wrappedBuffer.readerIndex()), new Object[] {-1, 11});
		buffer.position(buffer.position() + wrappedBuffer.writerIndex());
		buffer.flip();
		
		sendData(channel, buffer);
		
		ByteBuffer returned = doRead(channel);
		
		assertEquals(FAILURE_TO_SERIALIZE_FAILURE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
	}
	
	@Test
	public void testComplexCallFireAndForget() throws IOException, ClassNotFoundException {
		
		rp.registerService(SERVICE_ID, 
				new ServiceInvoker(SERVICE_ID, serializer, mockServiceObject, methodMappings, worker));
		
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
		buffer.putShort((short)2);
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
		methodMappings.clear();
		methodMappings.put(1, serviceToUse.getClass().getMethod("length"));
		
		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID, 
				new ServiceInvoker(SERVICE_ID, serializer, serviceToUse, methodMappings, worker)));
		
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
		methodMappings.clear();
		methodMappings.put(1, serviceToUse.getClass().getMethod("length"));
		
		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID, 
				new ServiceInvoker(SERVICE_ID, serializer, serviceToUse, methodMappings, worker)));
		
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
		buffer.put((byte)0);
		buffer.flip();
		
		sendData(channel, buffer);
		
		ByteBuffer returned = doRead(channel);
		
		assertEquals(FAILURE_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		
		Exception failure = (Exception) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
		//We have to get the cause as the mock service throws
		assertTrue(failure instanceof StringIndexOutOfBoundsException, ()->failure.getClass().getName());
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
		
		when(hostBundleWiring.getClassLoader()).thenReturn(testServiceClass.getClassLoader());
		serializer = Mockito.spy(SerializationType.FAST_BINARY.getFactory().create(hostBundle));
		
		methodMappings.clear();
		methodMappings.put(1, testServiceClass.getMethod("length"));
		
		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID, 
				new ServiceInvoker(SERVICE_ID, serializer, serviceToUse, methodMappings, worker)));
		
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
		
		when(hostBundleWiring.getClassLoader()).thenReturn(testServiceClass.getClassLoader());
		serializer = Mockito.spy(SerializationType.FAST_BINARY.getFactory().create(hostBundle));
		methodMappings.clear();
		methodMappings.put(1, testServiceClass.getMethod("length"));
		
		ByteChannel channel = getCommsChannel(rp.registerService(SERVICE_ID, 
				new ServiceInvoker(SERVICE_ID, serializer, serviceToUse, methodMappings, worker)));
		
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
		buffer.put((byte)0);
		buffer.flip();
		
		sendData(channel, buffer);
		
		ByteBuffer returned = doRead(channel);
		
		assertEquals(FAILURE_RESPONSE, returned.get());
		assertEquals(SERVICE_ID, new UUID(returned.getLong(), returned.getLong()));
		assertEquals(789, returned.getInt());
		
		Exception failure = (Exception) serializer.deserializeReturn(Unpooled.wrappedBuffer(returned));
		//We have to get the cause as the mock service throws
		assertTrue(failure instanceof StringIndexOutOfBoundsException, ()->failure.getClass().getName());
	}
	
	private void sendData(ByteChannel channel, ByteBuffer buffer) throws IOException {
		buffer.putShort(buffer.position() + 2, (short) (buffer.remaining() - 4));
 		channel.write(buffer);
	}

	private ByteBuffer doRead(ByteChannel channel) throws IOException {
 		ByteBuffer buffer = ByteBuffer.allocate(4096);
 		
 		int loopCount = 0;  
 		do {
 			channel.read(buffer);
 			if(buffer.position() >= 4) {
 				if(buffer.get(0) != 1) {
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
			private final Map<String, Class<?>> cache = new HashMap<String, Class<?>>();
			
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
	
}
