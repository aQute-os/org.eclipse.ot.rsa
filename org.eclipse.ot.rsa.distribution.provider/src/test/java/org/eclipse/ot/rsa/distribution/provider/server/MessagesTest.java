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

import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.FAILURE_NO_SERVICE_TYPE;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.FAILURE_UNKNOWN_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MessagesTest {

	private static final String	TEST_MESSAGE	= "Test Message";

	private final UUID			serviceId		= UUID.randomUUID();

	private final int			callId			= 42;

	@Mock
	ChannelPromise				promise;

	@Test
	public void testServerError() throws IOException {
		ServerErrorResponse ser = new ServerErrorResponse(FAILURE_NO_SERVICE_TYPE, serviceId, callId);

		ByteBuf buffer = Unpooled.buffer();

		ser.write(buffer, promise);

		Mockito.verifyNoInteractions(promise);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.FAILURE_NO_SERVICE, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertFalse(buffer.isReadable());
	}

	@Test
	public void testServerErrorWithMessage() throws IOException {
		ServerErrorMessageResponse ser = new ServerErrorMessageResponse(FAILURE_UNKNOWN_TYPE, serviceId, callId, TEST_MESSAGE);

		ByteBuf buffer = Unpooled.buffer();

		ser.write(buffer, promise);

		Mockito.verifyNoInteractions(promise);

		assertEquals(Protocol_V1.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V1.FAILURE_UNKNOWN, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertEquals(TEST_MESSAGE, buffer.readCharSequence(buffer.readUnsignedShort(), StandardCharsets.UTF_8));
		assertFalse(buffer.isReadable());
	}

	@Test
	public void testEndStream() {
		ServerStreamCloseResponse sscr = new ServerStreamCloseResponse(serviceId, callId);

		ByteBuf buffer = Unpooled.buffer();

		sscr.write(buffer, promise);

		Mockito.verifyNoInteractions(promise);

		assertEquals(Protocol_V2.VERSION, buffer.readByte());
		int length = buffer.readUnsignedMedium();
		assertEquals(buffer.readableBytes(), length);
		assertEquals(Protocol_V2.SERVER_CLOSE_EVENT, buffer.readByte());
		assertEquals(serviceId.getMostSignificantBits(), buffer.readLong());
		assertEquals(serviceId.getLeastSignificantBits(), buffer.readLong());
		assertEquals(callId, buffer.readInt());
		assertFalse(buffer.isReadable());
	}
}
