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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializer;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;
import org.freshvanilla.lang.MetaClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class BeginStreamingTest {

	private static final Exception MARKER_EXCEPTION = new Exception("marker");

	private final UUID serviceId = UUID.randomUUID();

	private final int callId = 42;

	@Mock
	Channel channel;

	ChannelPromise promise;
	ChannelPromise promise2;

	Promise<Void> closePromise = ImmediateEventExecutor.INSTANCE.newPromise();

	List<Object> data = new CopyOnWriteArrayList<>();

	AtomicReference<Exception> failure = new AtomicReference<>(MARKER_EXCEPTION);

	Serializer serializer;

	@BeforeEach
	public void setUp() {
		promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
		promise2 = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);

		serializer = new VanillaRMISerializer(new MetaClasses(getClass().getClassLoader()));
	}

	@Test
	public void testOpenStream() {
		BeginStreamingInvocation bsi = new BeginStreamingInvocation(serviceId, callId,
				serializer, ImmediateEventExecutor.INSTANCE, data::add, failure::set, closePromise);

		ByteBuf buffer = Unpooled.buffer();

		bsi.write(buffer, promise);

		Mockito.verifyNoInteractions(channel);

		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.CLIENT_OPEN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertFalse(buffer.isReadable());
	}
}
