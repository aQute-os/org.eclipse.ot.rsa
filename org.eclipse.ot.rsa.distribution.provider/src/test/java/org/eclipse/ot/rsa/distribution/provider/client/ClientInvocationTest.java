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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.ot.rsa.distribution.provider.promise.PromiseFactory;
import org.eclipse.ot.rsa.distribution.provider.serialize.CompletedPromise;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializer;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;
import org.freshvanilla.lang.MetaClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.ServiceException;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ClientInvocationTest {

	private final UUID		serviceId	= UUID.randomUUID();

	private final int		callId		= 42;

	private final Long		result		= 1234L;

	@Mock
	Channel					channel;

	ChannelPromise			promise;
	ChannelPromise			promise2;

	Serializer				serializer;

	Deferred<Long>			deferred	= new Deferred<>();

	CompletableFuture<Long>	cf			= new CompletableFuture<>();

	@BeforeEach
	public void setUp() {
		promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
		promise2 = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);

		serializer = new VanillaRMISerializer(new MetaClasses(getClass().getClassLoader()));
	}

	@Test
	public void testSimpleInvocationCommsSuccess() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			result
		}, new int[0], new int[0], serializer, PromiseFactory.toNettyFutureAdapter(Promise.class),
			ImmediateEventExecutor.INSTANCE.newPromise(), new AtomicLong(5000), "test[long]");

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());
		assertArrayEquals(new Object[] {
			result
		}, serializer.deserializeArgs(buffer));
		assertFalse(buffer.isReadable());

		promise.trySuccess();

		assertFalse(ci.getResult()
			.isDone());
	}

	@Test
	public void testSimpleInvocationCommsFail() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			result
		}, new int[0], new int[0], serializer, PromiseFactory.toNettyFutureAdapter(Promise.class),
			ImmediateEventExecutor.INSTANCE.newPromise(), new AtomicLong(5000), "test[long]");

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());
		assertArrayEquals(new Object[] {
			result
		}, serializer.deserializeArgs(buffer));
		assertFalse(buffer.isReadable());

		promise.tryFailure(new IOException("bang!"));

		assertTrue(ci.getResult()
			.isDone());
		assertEquals(ServiceException.REMOTE, ((ServiceException) ci.getResult()
			.cause()).getType());
	}

	@Test
	public void testFireAndForgetInvocationCommsSuccess() throws Exception {
		ClientInvocation ci = new ClientInvocation(false, serviceId, 1, callId, new Object[] {
			result
		}, new int[0], new int[0], serializer, PromiseFactory.toNettyFutureAdapter(Promise.class),
			ImmediateEventExecutor.INSTANCE.newPromise(), new AtomicLong(5000), "test[long]");

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITHOUT_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());
		assertArrayEquals(new Object[] {
			result
		}, serializer.deserializeArgs(buffer));
		assertFalse(buffer.isReadable());

		promise.trySuccess();

		assertFalse(ci.getResult()
			.isDone());
	}

	@Test
	public void testSimpleInvocationPromiseArg() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			deferred.getPromise()
		}, new int[] {
			0
		}, new int[0], serializer, PromiseFactory.toNettyFutureAdapter(Promise.class),
			ImmediateEventExecutor.INSTANCE.newPromise(), new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());
		assertArrayEquals(new Object[] {
			null
		}, serializer.deserializeArgs(buffer));
		assertFalse(buffer.isReadable());

		deferred.resolve(result);

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		ArgumentCaptor<AsyncArgumentCompletion> captor = ArgumentCaptor.forClass(AsyncArgumentCompletion.class);

		// This timeout should not be needed (all the work happens on immediate
		// executors)
		// but mockito spuriously fails when running the full suite...
		Mockito.verify(channel, Mockito.timeout(50))
			.writeAndFlush(captor.capture());

		AsyncArgumentCompletion completion = captor.getValue();

		assertEquals(ci, completion.getParentInvocation());
		assertEquals(result, completion.getResult());

		buffer.resetReaderIndex();
		buffer.resetWriterIndex();

		completion.write(buffer, promise2);

		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.ASYNC_METHOD_PARAM_DATA, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(0, buffer.readUnsignedByte());
		assertEquals(result, serializer.deserializeReturn(buffer));
		assertFalse(buffer.isReadable());
	}

	@Test
	public void testSimpleInvocationFailedPromiseArg() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			deferred.getPromise()
		}, new int[] {
			0
		}, new int[0], serializer, PromiseFactory.toNettyFutureAdapter(Promise.class),
			ImmediateEventExecutor.INSTANCE.newPromise(), new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());
		assertArrayEquals(new Object[] {
			null
		}, serializer.deserializeArgs(buffer));
		assertFalse(buffer.isReadable());

		IOException failure = new IOException("failed");
		deferred.fail(failure);

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		ArgumentCaptor<AsyncArgumentCompletion> captor = ArgumentCaptor.forClass(AsyncArgumentCompletion.class);
		Mockito.verify(channel, Mockito.timeout(100))
			.writeAndFlush(captor.capture());

		AsyncArgumentCompletion completion = captor.getValue();

		assertEquals(ci, completion.getParentInvocation());
		assertEquals(failure, completion.getResult());

		buffer.resetReaderIndex();
		buffer.resetWriterIndex();

		completion.write(buffer, promise2);

		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.ASYNC_METHOD_PARAM_FAILURE, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(0, buffer.readUnsignedByte());
		assertEquals("failed", ((Exception) serializer.deserializeReturn(buffer)).getMessage());
		assertFalse(buffer.isReadable());
	}

	@Test
	public void testSimpleInvocationFastPathPromiseArg() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			deferred.getPromise()
		}, new int[] {
			0
		}, new int[0], serializer, PromiseFactory.toNettyFutureAdapter(Promise.class),
			ImmediateEventExecutor.INSTANCE.newPromise(), new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		deferred.resolve(result);

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());

		Object[] deserializeArgs = serializer.deserializeArgs(buffer);
		assertEquals(CompletedPromise.State.SUCCEEDED, ((CompletedPromise) deserializeArgs[0]).state);
		assertEquals(result, ((CompletedPromise) deserializeArgs[0]).value);

		assertFalse(buffer.isReadable());

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		Mockito.verifyNoInteractions(channel);
	}

	@Test
	public void testSimpleInvocationFastPathPromiseArg2() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			deferred.getPromise()
		}, new int[] {
			0
		}, new int[0], serializer, PromiseFactory.toNettyFutureAdapter(Promise.class),
			ImmediateEventExecutor.INSTANCE.newPromise(), new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		IOException failure = new IOException("failed");
		deferred.fail(failure);

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());

		Object[] deserializeArgs = serializer.deserializeArgs(buffer);
		assertEquals(CompletedPromise.State.FAILED, ((CompletedPromise) deserializeArgs[0]).state);
		assertEquals("failed", ((Exception) ((CompletedPromise) deserializeArgs[0]).value).getMessage());

		assertFalse(buffer.isReadable());

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		Mockito.verifyNoInteractions(channel);
	}

	@Test
	public void testSimpleInvocationCompletableFutureArg() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			cf
		}, new int[0], new int[] {
			0
		}, serializer, PromiseFactory.toNettyFutureAdapter(Promise.class), ImmediateEventExecutor.INSTANCE.newPromise(),
			new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());
		assertArrayEquals(new Object[] {
			null
		}, serializer.deserializeArgs(buffer));
		assertFalse(buffer.isReadable());

		cf.complete(result);

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		ArgumentCaptor<AsyncArgumentCompletion> captor = ArgumentCaptor.forClass(AsyncArgumentCompletion.class);
		Mockito.verify(channel)
			.writeAndFlush(captor.capture());

		AsyncArgumentCompletion completion = captor.getValue();

		assertEquals(ci, completion.getParentInvocation());
		assertEquals(result, completion.getResult());

		buffer.resetReaderIndex();
		buffer.resetWriterIndex();

		completion.write(buffer, promise2);

		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.ASYNC_METHOD_PARAM_DATA, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(0, buffer.readUnsignedByte());
		assertEquals(result, serializer.deserializeReturn(buffer));
		assertFalse(buffer.isReadable());
	}

	@Test
	public void testSimpleInvocationFailedCompletableFutureArg() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			cf
		}, new int[0], new int[] {
			0
		}, serializer, PromiseFactory.toNettyFutureAdapter(Promise.class), ImmediateEventExecutor.INSTANCE.newPromise(),
			new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());
		assertArrayEquals(new Object[] {
			null
		}, serializer.deserializeArgs(buffer));
		assertFalse(buffer.isReadable());

		IOException failure = new IOException("failed");
		cf.completeExceptionally(failure);

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		ArgumentCaptor<AsyncArgumentCompletion> captor = ArgumentCaptor.forClass(AsyncArgumentCompletion.class);
		Mockito.verify(channel)
			.writeAndFlush(captor.capture());

		AsyncArgumentCompletion completion = captor.getValue();

		assertEquals(ci, completion.getParentInvocation());
		assertEquals(failure, completion.getResult());

		buffer.resetReaderIndex();
		buffer.resetWriterIndex();

		completion.write(buffer, promise2);

		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.ASYNC_METHOD_PARAM_FAILURE, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(0, buffer.readUnsignedByte());
		assertEquals("failed", ((Exception) serializer.deserializeReturn(buffer)).getMessage());
		assertFalse(buffer.isReadable());
	}

	@Test
	public void testSimpleInvocationFastPathCompletableFutureArg() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			cf
		}, new int[0], new int[] {
			0
		}, serializer, PromiseFactory.toNettyFutureAdapter(Promise.class), ImmediateEventExecutor.INSTANCE.newPromise(),
			new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		cf.complete(result);

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());

		Object[] deserializeArgs = serializer.deserializeArgs(buffer);
		assertEquals(CompletedPromise.State.SUCCEEDED, ((CompletedPromise) deserializeArgs[0]).state);
		assertEquals(result, ((CompletedPromise) deserializeArgs[0]).value);

		assertFalse(buffer.isReadable());

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		Mockito.verifyNoInteractions(channel);
	}

	@Test
	public void testSimpleInvocationFastPathCompletableFutureArg2() throws Exception {
		ClientInvocation ci = new ClientInvocation(true, serviceId, 1, callId, new Object[] {
			cf
		}, new int[0], new int[] {
			0
		}, serializer, PromiseFactory.toNettyFutureAdapter(Promise.class), ImmediateEventExecutor.INSTANCE.newPromise(),
			new AtomicLong(5000), "test[org.osgi.util.promise.Promise]");

		IOException failure = new IOException("failed");
		cf.completeExceptionally(failure);

		ByteBuf buffer = Unpooled.buffer();
		ci.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CALL_WITH_RETURN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1, buffer.readUnsignedShort());

		Object[] deserializeArgs = serializer.deserializeArgs(buffer);
		assertEquals(CompletedPromise.State.FAILED, ((CompletedPromise) deserializeArgs[0]).state);
		assertEquals("failed", ((Exception) ((CompletedPromise) deserializeArgs[0]).value).getMessage());

		assertFalse(buffer.isReadable());

		Mockito.verifyNoInteractions(channel);

		promise.trySuccess();

		Mockito.verifyNoInteractions(channel);
	}
}
