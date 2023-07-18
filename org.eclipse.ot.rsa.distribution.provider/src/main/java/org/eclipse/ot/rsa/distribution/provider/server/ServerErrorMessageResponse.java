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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public class ServerErrorMessageResponse extends AbstractRSAMessage<ServerMessageType> {

	private final String message;

	public ServerErrorMessageResponse(ServerMessageType type, UUID serviceId, int callId, String message) {
		super(check(type), serviceId, callId);
		this.message = message == null ? "" : message;
	}

	private static ServerMessageType check(ServerMessageType type) {
		if (!type.isError()) {
			throw new IllegalArgumentException("The type is not an error");
		}
		return type;
	}

	public String getMessage() {
		return message;
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) throws IOException {
		writeHeader(buffer);

		// Create space for the length prefix
		int messageLengthStart = buffer.writerIndex();
		buffer.writerIndex(messageLengthStart + 2);

		// Write the string then set the length
		int length = buffer.writeCharSequence(message, StandardCharsets.UTF_8);
		buffer.setShort(messageLengthStart, length);

		// Write the overall length of the message
		writeLength(buffer);
	}
}
