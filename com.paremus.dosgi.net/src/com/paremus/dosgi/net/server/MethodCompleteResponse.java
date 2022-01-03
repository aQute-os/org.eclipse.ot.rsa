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

import static com.paremus.dosgi.net.server.ServerMessageType.FAILURE;
import static com.paremus.dosgi.net.server.ServerMessageType.SUCCESS;

import java.io.IOException;
import java.util.UUID;

import com.paremus.dosgi.net.message.AbstractPayloadMessage;
import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public class MethodCompleteResponse extends AbstractPayloadMessage<ServerMessageType> {

	private final Object response;
	
	public MethodCompleteResponse(boolean successful, UUID serviceId, int callId,
			Serializer serializer, Object response) {
		super(successful ? SUCCESS : FAILURE, serviceId, callId, serializer);
		this.response = response;
	}

	public Object getResponse() {
		return response;
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) throws IOException {
		writeHeader(buffer);
		getSerializer().serializeReturn(buffer, response);
		writeLength(buffer);
	}
}