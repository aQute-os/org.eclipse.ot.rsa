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

import java.io.IOException;
import java.util.UUID;

import com.paremus.dosgi.net.message.AbstractPayloadMessage;
import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public class ServerStreamDataResponse extends AbstractPayloadMessage<ServerMessageType> {

	private final Object data;

	public ServerStreamDataResponse(UUID serviceId, int callId, Serializer serializer, Object data) {
		super(ServerMessageType.STREAM_DATA, serviceId, callId, serializer);
		this.data = data;
	}

	public ServerStreamDataResponse fromTemplate(Object data) {
		return new ServerStreamDataResponse(getServiceId(), getCallId(), getSerializer(), data);
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) throws IOException {
		writeHeader(buffer);
		getSerializer().serializeReturn(buffer, data);
		writeLength(buffer);
	}
}
