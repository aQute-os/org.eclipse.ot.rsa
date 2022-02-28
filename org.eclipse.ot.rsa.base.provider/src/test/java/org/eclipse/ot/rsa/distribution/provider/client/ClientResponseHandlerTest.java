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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.sound.sampled.UnsupportedAudioFileException;

import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ClientResponseHandlerTest {

	@Mock
	Timer timer;
	@Mock
	Timeout timeout;
	@Mock
	Channel channel;
	@Mock
	ChannelHandlerContext ctx;
	@Mock
	Serializer serializer;
	@Mock
	ClientConnectionManager ccm;

	ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

	UUID serviceId = UUID.randomUUID();

	ClientResponseHandler impl;

	EventExecutor executor;

	Supplier<Promise<Object>> nettyPromiseSupplier;

	@BeforeEach
	public void setUp() {
		Mockito.when(timer.newTimeout(any(), ArgumentMatchers.anyLong(), any())).thenReturn(timeout);

		executor = new DefaultEventExecutor();

		nettyPromiseSupplier = () -> executor.next().newPromise();

		impl = new ClientResponseHandler(ccm, timer);
	}

	@AfterEach
	public void tearDown() throws Exception {
		executor.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS).await(1, TimeUnit.SECONDS);
	}

	@Test
	public void testSuccessResponseCleansUp() throws Exception {
		doTestResponseCleansUp(Protocol_V1.SUCCESS_RESPONSE, allocator.buffer(0));
	}

	@Test
	public void testFailureResponseCleansUp() throws Exception {
		ByteBuf buffer = allocator.buffer(0);
		Mockito.when(serializer.deserializeReturn(buffer)).thenReturn(new UnsupportedAudioFileException());
		doTestResponseCleansUp(Protocol_V1.SUCCESS_RESPONSE, buffer);
	}

	@Test
	public void testFailureNoService() throws Exception {
		doTestResponseCleansUp(Protocol_V1.FAILURE_NO_SERVICE, allocator.buffer(0));
	}

	@Test
	public void testFailureNoMethod() throws Exception {
		doTestResponseCleansUp(Protocol_V1.FAILURE_NO_METHOD, allocator.buffer(0));
	}

	@Test
	public void testFailureToDeserializeArgsCleansUp() throws Exception {
		doTestResponseCleansUp(Protocol_V1.FAILURE_TO_DESERIALIZE, allocator.buffer(0));
	}

	@Test
	public void testFailureToSerializeResponseCleansUp() throws Exception {
		doTestResponseCleansUp(Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS, allocator.buffer(0));
	}

	@Test
	public void testFailureToSerializeErrorCleansUp() throws Exception {
		doTestResponseCleansUp(Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE, allocator.buffer(0));
	}

	public void doTestResponseCleansUp(byte response, ByteBuf buf) throws Exception {

		ClientInvocation ci = new ClientInvocation(true, serviceId, -1, 42, new Object[0],
				new int[0], new int[0], serializer, null, nettyPromiseSupplier.get(), new AtomicLong(3000), "test");

		impl.registerInvocation(ci);

		buf.writeByte(Protocol_V1.SUCCESS_RESPONSE);
		buf.writeLong(serviceId.getMostSignificantBits());
		buf.writeLong(serviceId.getLeastSignificantBits());
		buf.writeInt(42);

		buf.markReaderIndex();
		int refCnt = buf.refCnt();
		buf.retain();

		impl.channelRead(ctx, buf);
		assertEquals(refCnt - 1, buf.refCnt());

		assertTrue(ci.getResult().isSuccess());
		Mockito.verify(timeout, timeout(100)).cancel();

		buf.resetReaderIndex();
		impl.channelRead(ctx, buf);
		assertEquals(refCnt - 2, buf.refCnt());
		Mockito.verify(timeout, after(100)).cancel();
	}

	private void assertEquals(int i, int refCnt) {
		// TODO Auto-generated method stub

	}

	@Test
	public void testTimeoutCleansUp() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, -1, 42, new Object[0],
				new int[0], new int[0], serializer, null, nettyPromiseSupplier.get(), new AtomicLong(3000), "test");

		impl.registerInvocation(ci);

		ArgumentCaptor<TimerTask> taskCaptor = ArgumentCaptor.forClass(TimerTask.class);
		Mockito.verify(timer).newTimeout(taskCaptor.capture(), ArgumentMatchers.anyLong(), any());

		Mockito.when(timeout.isExpired()).thenReturn(Boolean.TRUE);
		taskCaptor.getValue().run(timeout);
		assertTrue(ci.getResult().isDone());

		ByteBuf buf = allocator.heapBuffer();
		buf.writeByte(Protocol_V1.SUCCESS_RESPONSE);
		buf.writeLong(serviceId.getMostSignificantBits());
		buf.writeLong(serviceId.getLeastSignificantBits());
		buf.writeInt(42);

		impl.channelRead(ctx, buf);

		Mockito.verify(timeout, Mockito.never()).cancel();
	}

	@Test
	public void testChannelCloseCleansUp() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, -1, 42, new Object[0],
				new int[0], new int[0], serializer, null, nettyPromiseSupplier.get(), new AtomicLong(3000), "test");

		impl.registerInvocation(ci);

		impl.channelInactive(ctx);

		assertTrue(ci.getResult().isDone());
		Mockito.verify(timeout, timeout(100)).cancel();
		assertNotNull(ci.getResult().cause());
	}
}
