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

import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.FAILURE_RESPONSE_TYPE;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.SUCCESS_RESPONSE_TYPE;

import java.io.IOException;
import java.util.UUID;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractPayloadMessage;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public class MethodCompleteResponse extends AbstractPayloadMessage<ServerMessageType> {

	private final Object response;

	public MethodCompleteResponse(boolean successful, UUID serviceId, int callId, Serializer serializer,
		Object response) {
		super(successful ? SUCCESS_RESPONSE_TYPE : FAILURE_RESPONSE_TYPE, serviceId, callId, serializer);
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
