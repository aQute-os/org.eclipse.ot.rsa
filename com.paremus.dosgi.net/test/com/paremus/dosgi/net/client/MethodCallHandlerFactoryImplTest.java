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

import static com.paremus.dosgi.net.proxy.MethodCallHandler.CallType.WITH_RETURN;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.sound.sampled.UnsupportedAudioFileException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceException;
import org.osgi.util.promise.Promise;

import com.paremus.dosgi.net.config.ImportedServiceConfig;
import com.paremus.dosgi.net.impl.ImportRegistrationImpl;
import com.paremus.dosgi.net.proxy.MethodCallHandler;
import com.paremus.dosgi.net.serialize.Serializer;
import com.paremus.dosgi.net.serialize.SerializerFactory;
import com.paremus.dosgi.net.wireformat.Protocol_V1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.ImmediateEventExecutor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MethodCallHandlerFactoryImplTest{

	@Mock
	Timer timer;
	@Mock
	Timeout timeout;
	@Mock
	Channel channel;
	@Mock
	ImportRegistrationImpl ir;
	@Mock
	ImportedServiceConfig irConfig;
	@Mock
	SerializerFactory serializerFactory;
	@Mock
	Serializer serializer;
	@Mock
	Bundle client;
	@Mock
	ClientConnectionManager ccm;
	
	ByteBufAllocator allocator = new UnpooledByteBufAllocator(false);

	UUID serviceId = UUID.randomUUID();
	
	MethodCallHandlerFactoryImpl impl;
	
	ChannelPromise writePromise;
	
	@BeforeEach
	public void setUp() {
		Mockito.when(timer.newTimeout(Mockito.any(), Mockito.anyLong(), Mockito.any())).thenReturn(timeout);
		
		Mockito.when(serializerFactory.create(client)).thenReturn(serializer);
		
		writePromise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
		Mockito.when(channel.writeAndFlush(Mockito.any()))
			.thenReturn(writePromise);
		
		Mockito.when(ir.getConfig()).thenReturn(irConfig);
		
		impl = new MethodCallHandlerFactoryImpl(channel, allocator, serviceId, serializerFactory, 
				singletonMap(1, "foo[]"), ccm, timer);
	}
	
	@Test
	public void testSuccessResponseCleansUp() {
		doTestResponseCleansUp(Protocol_V1.SUCCESS_RESPONSE, allocator.buffer(0));
	}

	@Test
	public void testFailureResponseCleansUp() throws ClassNotFoundException, IOException {
		ByteBuf buffer = allocator.buffer(0);
		Mockito.when(serializer.deserializeReturn(buffer)).thenReturn(new UnsupportedAudioFileException());
		doTestResponseCleansUp(Protocol_V1.SUCCESS_RESPONSE, buffer);
	}

	@Test
	public void testFailureNoService() throws ClassNotFoundException, IOException {
		doTestResponseCleansUp(Protocol_V1.FAILURE_NO_SERVICE, allocator.buffer(0));
	}
	
	@Test
	public void testFailureNoMethod() throws ClassNotFoundException, IOException {
		doTestResponseCleansUp(Protocol_V1.FAILURE_NO_METHOD, allocator.buffer(0));
	}
	
	@Test
	public void testFailureToDeserializeArgsCleansUp() throws ClassNotFoundException, IOException {
		doTestResponseCleansUp(Protocol_V1.FAILURE_TO_DESERIALIZE, allocator.buffer(0));
	}
	
	@Test
	public void testFailureToSerializeResponseCleansUp() throws ClassNotFoundException, IOException {
		doTestResponseCleansUp(Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS, allocator.buffer(0));
	}
	
	@Test
	public void testFailureToSerializeErrorCleansUp() throws ClassNotFoundException, IOException {
		doTestResponseCleansUp(Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE, allocator.buffer(0));
	}
	
	@Test
	public void doTestResponseCleansUp(byte response, ByteBuf buffer) {
		
		MethodCallHandler handler = impl.create(client);
		
		Promise<? extends Object> promise = handler.call(WITH_RETURN, 1, null, 3000);
		
		ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
		
		Mockito.verify(channel).writeAndFlush(captor.capture());
		writePromise.trySuccess();
		
		int callId = captor.getValue().skipBytes(21).readInt();
		
		impl.response(callId, response, buffer.duplicate());
		
		assertTrue(promise.isDone());
		Mockito.verify(timeout).cancel();
		
		impl.response(callId, response, buffer);
		Mockito.verify(timeout).cancel();
	}

	@Test
	public void testCancelCleansUp() throws ClassNotFoundException, IOException {
		
		MethodCallHandler handler = impl.create(client);
		
		Promise<? extends Object> promise = handler.call(WITH_RETURN, 1, null, 3000);
		
		((Future<?>)promise).cancel(true);
		
		assertTrue(promise.isDone());
		Mockito.verify(timeout).cancel();
		
		((Future<?>)promise).cancel(true);
		Mockito.verify(timeout).cancel();
	}
	
	@Test
	public void testTimeoutCleansUp() throws Exception {
		MethodCallHandler handler = impl.create(client);
		Promise<? extends Object> promise = handler.call(WITH_RETURN, 1, null, 3000);
		
		ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
		Mockito.verify(channel).writeAndFlush(captor.capture());
		writePromise.trySuccess();

		ArgumentCaptor<TimerTask> taskCaptor = ArgumentCaptor.forClass(TimerTask.class);
		Mockito.verify(timer).newTimeout(taskCaptor.capture(), Mockito.anyLong(), Mockito.any());
		
		taskCaptor.getValue().run(timeout);
		assertTrue(promise.isDone());

		Mockito.verify(channel).writeAndFlush(captor.capture(), Mockito.any());
		
		ByteBuf buf = captor.getValue();
		assertEquals(Protocol_V1.CANCEL, buf.skipBytes(4).readByte());
		
		int callId = buf.skipBytes(16).readInt();
		
		impl.response(callId, Protocol_V1.SUCCESS_RESPONSE, allocator.buffer(0));
		Mockito.verify(timeout, Mockito.never()).cancel();
	}

	@Test
	public void testChannelCloseCleansUp() throws Exception {
		impl.addImportRegistration(ir);
		MethodCallHandler handler = impl.create(client);
		
		Promise<? extends Object> promise = handler.call(WITH_RETURN, 1, null, 3000);
		
		writePromise.tryFailure(new Exception("Bang"));
		
		assertTrue(promise.isDone());
		Mockito.verify(timeout).cancel();
		assertNotNull(promise.getFailure());
	}

	@Test
	public void testImportRegistrationAfterChannelCloseFails() throws Exception {
		impl.failAll(new IllegalArgumentException("Bang!"));
		
		try {
			impl.addImportRegistration(ir);
		} catch (ServiceException se) {
			assertEquals(ServiceException.REMOTE, se.getType());
		}
	}
}
