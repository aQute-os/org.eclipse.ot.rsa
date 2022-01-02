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

import static com.paremus.dosgi.net.client.ClientMessageType.STREAMING_RESPONSE_CLOSE;

import java.util.UUID;

import com.paremus.dosgi.net.message.AbstractRSAMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public class EndStreamingInvocation extends AbstractRSAMessage<ClientMessageType> {

	public EndStreamingInvocation(UUID serviceId, int callId) {
		super(STREAMING_RESPONSE_CLOSE, serviceId, callId);
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) {
		writeHeader(buffer);
		writeLength(buffer);
	}
}
