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


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.paremus.dosgi.net.wireformat.Protocol_V1;
import com.paremus.dosgi.net.wireformat.Protocol_V2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MessagesTest {

	private final UUID serviceId = UUID.randomUUID();
	
	private final int callId = 42;
	
	@Mock
	ChannelPromise promise;
	
	@Test
	public void testBackPressure() {
		ClientBackPressure end = new ClientBackPressure(serviceId, callId, 1234L);
		
		ByteBuf buffer = Unpooled.buffer();
		
		end.write(buffer, promise);
		
		Mockito.verifyNoInteractions(promise);
		
		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.CLIENT_BACK_PRESSURE, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(1234L, buffer.readLong());
		assertFalse(buffer.isReadable());
	}
	
	@Test
	public void testEndStreamingInvocation() {
		EndStreamingInvocation end = new EndStreamingInvocation(serviceId, callId);
		
		ByteBuf buffer = Unpooled.buffer();
		
		end.write(buffer, promise);
		
		Mockito.verifyNoInteractions(promise);
		
		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.CLIENT_CLOSE, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertFalse(buffer.isReadable());
	}
	
	@Test
	public void testInvocationCancellation() {
		InvocationCancellation cancellation = new InvocationCancellation(serviceId, callId, false);
		
		ByteBuf buffer = Unpooled.buffer();
		
		cancellation.write(buffer, promise);
		
		Mockito.verifyNoInteractions(promise);
		
		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.CANCEL, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertFalse(buffer.readBoolean());
		assertFalse(buffer.isReadable());
	}
	
}
