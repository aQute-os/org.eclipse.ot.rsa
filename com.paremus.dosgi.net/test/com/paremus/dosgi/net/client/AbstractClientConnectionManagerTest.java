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
package com.paremus.dosgi.net.client;

import static aQute.bnd.annotation.metatype.Configurable.createConfigurable;
import static com.paremus.dosgi.net.proxy.MethodCallHandler.CallType.FIRE_AND_FORGET;
import static com.paremus.dosgi.net.proxy.MethodCallHandler.CallType.WITH_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CANCEL;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_METHOD;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_SERVICE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_DESERIALIZE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.SUCCESS_RESPONSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.VERSION;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceException;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.util.promise.Promise;

import com.paremus.dosgi.net.config.Config;
import com.paremus.dosgi.net.impl.ImportRegistrationImpl;
import com.paremus.dosgi.net.proxy.MethodCallHandler;
import com.paremus.dosgi.net.proxy.MethodCallHandlerFactory;
import com.paremus.dosgi.net.serialize.Serializer;
import com.paremus.dosgi.net.serialize.freshvanilla.VanillaRMISerializerFactory;
import com.paremus.dosgi.net.wireformat.Protocol_V1;
import com.paremus.net.encode.EncodingSchemeFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class AbstractClientConnectionManagerTest {

	public static class ClientException extends Exception {

		private static final long serialVersionUID = -1273488084147564822L;

		public ClientException(String message) {
			super(message);
		}
	}
	
    ClientConnectionManager clientConnectionManager;
    
    @Mock
    EncodingSchemeFactory esf;
    @Mock
    ImportRegistrationImpl ir;
    @Mock
    EndpointDescription ed;
    @Mock
    Bundle classSpace;
    @Mock
    BundleWiring wiring;

    @BeforeEach
    public void setUp() throws Exception {
//        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        ResourceLeakDetector.setLevel(Level.PARANOID);
        
        Mockito.when(classSpace.adapt(BundleWiring.class)).thenReturn(wiring);
        Mockito.when(wiring.getClassLoader()).thenReturn(getClass().getClassLoader());
        
        Map<String, Object> config = getConfig();
        
        Mockito.when(ed.getId()).thenReturn(new UUID(12, 34).toString());
        
        clientConnectionManager = new ClientConnectionManager(createConfigurable(Config.class, 
        		config), esf, PooledByteBufAllocator.DEFAULT);
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
	    		
			    return new byte[]{VERSION, 0, 0, 22, SUCCESS_RESPONSE, 
			    				0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId, -1};
    	});
    	
        	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    			new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	
    	assertNull(call.getValue());
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
			out.writeBytes(new byte[]{VERSION, 0, 0, 0, Protocol_V1.FAILURE_RESPONSE, 
					0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId});
			
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
    	
    	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    					new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	
    	assertEquals("bang!", call.getFailure().getMessage());
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
							Serializer serializer = new VanillaRMISerializerFactory()
									.create(classSpace);
							
							Object[] args = serializer.deserializeArgs(buf);
							
							assertEquals(0, buf.readableBytes());

							assertEquals(3, args.length);
							assertEquals(Integer.valueOf(1), args[0]);
							assertEquals(Long.valueOf(7), args[1]);
							assertEquals("forty-two", args[2]);
							
							ByteBuf out = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
							out.writeBytes(new byte[]{VERSION, 0, 0, 0, SUCCESS_RESPONSE, 
			    					0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId});
			    			
							serializer.serializeReturn(out, new URL("http://www.paremus.com"));
			    			
							out.setMedium(out.readerIndex() + 1, out.readableBytes() - 4);
			    			byte[] b2 = new byte[out.readableBytes()];
			    			out.readBytes(b2);
			    			return b2;
							
						} catch (Exception e) {
							e.printStackTrace();
							return null;
						}
			    	});
    	
        	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    			new VanillaRMISerializerFactory(), singletonMap(8, "touch[int,long,java.lang.String]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	Promise<?> call = handler.call(WITH_RETURN, 8, new Object[] {1, 7L, "forty-two"}, 3000);
    	
    	assertEquals(new URL("http://www.paremus.com"), call.getValue());
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
    			Serializer serializer = new VanillaRMISerializerFactory()
    					.create(classSpace);
    			
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
    	
    	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    					new VanillaRMISerializerFactory(), singletonMap(8, "touch[int,long,java.lang.String]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	assertNull(handler.call(FIRE_AND_FORGET, 8, new Object[] {1, 7L, "forty-two"}, 3000));

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
    	
    	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    					new VanillaRMISerializerFactory(), singletonMap(8, "touch[int,long,java.lang.String]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	Future<?> call = (Future<?>) handler.call(WITH_RETURN, 8, new Object[] {1, 7L, "forty-two"}, 3000);
    	
    	call.cancel(true);
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
    	
        	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    			new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	
    	Throwable t = call.getFailure();
    	
    	assertTrue(t instanceof ServiceException, ()->String.valueOf(t));
    	assertEquals(ServiceException.REMOTE, ((ServiceException)t).getType(), ()->"Not a remote ServiceException");
    	assertTrue(t.getCause() instanceof TimeoutException, ()->String.valueOf(t.getCause()));
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
	    		
			    return new byte[]{VERSION, 0, 0, 21, FAILURE_NO_SERVICE, 
			    				0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId};
    	});
    	
        	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    			new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	Throwable failure = call.getFailure();
    	assertTrue(failure instanceof ServiceException, ()->failure.getMessage());
    	assertEquals(ServiceException.REMOTE, ((ServiceException)failure).getType());
    	assertTrue(failure.getCause() instanceof MissingServiceException, ()->failure.getCause().getMessage());
    	
    	verify(ir, timeout(200)).close();
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
	    		
			    return new byte[]{VERSION, 0, 0, 21, FAILURE_NO_METHOD, 
			    				0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId};
    	});
    	
        	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    			new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	Throwable failure = call.getFailure();
    	assertTrue(failure instanceof ServiceException, ()->failure.getMessage());
    	assertEquals(ServiceException.REMOTE, ((ServiceException)failure).getType());
    	assertTrue(failure.getCause() instanceof MissingMethodException, ()->failure.getCause().getMessage());
    	assertTrue(failure.getCause().getMessage().contains("touch[]"), ()->failure.getCause().getMessage());
    	
    	verify(ir, timeout(200)).close();
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
    		
    		return new byte[]{VERSION, 0, 0, 28, FAILURE_TO_DESERIALIZE, 
    				0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId,
    				// Bang!
    				0,5,66,97,110,103,33};
    	});
    	
    	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    					new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	Throwable failure = call.getFailure();
    	assertTrue(failure instanceof ServiceException, ()->failure.getMessage());
    	assertEquals(ServiceException.REMOTE, ((ServiceException)failure).getType());
    	assertTrue(failure.getCause() instanceof IllegalArgumentException, ()->failure.getCause().getClass().getName());
    	assertEquals("Bang!", failure.getCause().getMessage());
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
    		
    		return new byte[]{VERSION, 0, 0, 28, FAILURE_TO_SERIALIZE_SUCCESS, 
    				0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId,
    				// Bang!
    				0,5,66,97,110,103,33};
    	});
    	
    	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    					new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	Throwable failure = call.getFailure();
    	assertTrue(failure instanceof ServiceException, ()->failure.getMessage());
    	assertEquals(ServiceException.REMOTE, ((ServiceException)failure).getType());
    	assertTrue(failure.getMessage().contains("succeeded"), ()->failure.getMessage());
    	assertTrue(failure.getCause() instanceof IllegalArgumentException, ()->failure.getCause().getClass().getName());
    	assertEquals("Bang!", failure.getCause().getMessage());
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
    		
    		return new byte[]{VERSION, 0, 0, 28, FAILURE_TO_SERIALIZE_FAILURE, 
    				0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId,
    				// Bang!
    				0,5,66,97,110,103,33};
    	});
    	
    	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    					new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	Throwable failure = call.getFailure();
    	assertTrue(failure instanceof ServiceException, ()->failure.getMessage());
    	assertEquals(ServiceException.REMOTE, ((ServiceException)failure).getType());
    	assertTrue(failure.getMessage().contains("failed"), ()->failure.getMessage());
    	assertTrue(failure.getCause() instanceof IllegalArgumentException, ()->failure.getCause().getClass().getName());
    	assertEquals("Bang!", failure.getCause().getMessage());
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
	    		
			    return new byte[]{VERSION, 0, 0, 22, SUCCESS_RESPONSE, 
			    				0,0,0,0,0,0,0,12, 0,0,0,0,0,0,0,34, 0,0,0,(byte)callId, -1};
    	};
		String uri = runTCPServer(true, doSimpleVoidCall, doSimpleVoidCall);
    	
        	
    	MethodCallHandlerFactory mchf = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    			new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	mchf.addImportRegistration(ir);
    	MethodCallHandler handler = mchf.create(classSpace);
    	
    	Promise<?> call = handler.call(WITH_RETURN, 7, null, 3000);
    	assertNull(call.getValue());
    	
    	//Trigger an error then Topology manager close
    	verify(ir, timeout(500)).asyncFail(any(ServiceException.class));
    	mchf.close(ir);
    	
    	MethodCallHandlerFactory mchf2 = clientConnectionManager
    			.getFactoryFor(new URI(uri), ed, 
    			new VanillaRMISerializerFactory(), singletonMap(7, "touch[]"));
    	
    	assertNotSame(mchf, mchf2);
    	
    	handler = mchf2.create(classSpace);
    	
    	//It should now work a second time
    	call = handler.call(WITH_RETURN, 7, null, 3000);
    	assertNull(call.getValue());
    }

    @SafeVarargs
    protected final String runTCPServer(Function<byte[], byte[]>... validators) throws Exception {
    	return runTCPServer(false, validators);
    }
    
    @SafeVarargs
	protected final String runTCPServer(boolean closeInbetween, Function<byte[], byte[]>... validators) throws Exception {
    	
    	Semaphore sem = new Semaphore(0);
    	new Thread(() -> {
    		
    		try (ServerSocket ss = getConfiguredSocket()) {
    			sem.release(ss.getLocalPort());
    			ss.setSoTimeout(10000000);
    			
    			Socket s = ss.accept();
    			InputStream is = null;
    			try {
					is = s.getInputStream();
	    			
	    			for(Function<byte[], byte[]> validator : validators) {
		    			assertEquals(1, is.read());
						int len = (is.read() << 16) + (is.read() << 8) + is.read();
						byte[] b = new byte[len];
						int read = 0;
						while((read += is.read(b, read, len - read)) < len);
						b = validator.apply(b);
						if(b != null) {
							s.getOutputStream().write(b);
							s.getOutputStream().flush();
						}
						
						if(closeInbetween) {
							//Wait a little before closing to avoid racing the return
							Thread.sleep(100);
							is.close();
							s.close();
							s = ss.accept();
							is = s.getInputStream();
						}
	    			} 
    			} finally {
    				//Wait a little before closing to avoid racing the return
    				Thread.sleep(100);
    				if(is != null)
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

	protected abstract String getPrefix();

	protected abstract ServerSocket getConfiguredSocket() throws Exception;
}
