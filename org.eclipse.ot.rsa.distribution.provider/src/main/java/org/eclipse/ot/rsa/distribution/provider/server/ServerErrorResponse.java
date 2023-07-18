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
import java.util.UUID;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public class ServerErrorResponse extends AbstractRSAMessage<ServerMessageType> {

	public ServerErrorResponse(ServerMessageType type, UUID serviceId, int callId) {
		super(check(type), serviceId, callId);
	}

	private static ServerMessageType check(ServerMessageType type) {
		if (!type.isError()) {
			throw new IllegalArgumentException("The type is not an error");
		}
		return type;
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) throws IOException {
		writeHeader(buffer);
		writeLength(buffer);
	}
}
