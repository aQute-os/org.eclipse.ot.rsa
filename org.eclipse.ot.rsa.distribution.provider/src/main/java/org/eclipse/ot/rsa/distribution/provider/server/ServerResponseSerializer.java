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

import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.FAILURE_TO_SERIALIZE_FAILURE_TYPE;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.FAILURE_TO_SERIALIZE_SUCCESS_TYPE;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.FAILURE_UNKNOWN_TYPE;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

@Sharable
public class ServerResponseSerializer extends ChannelOutboundHandlerAdapter {

	private static final Logger LOG = LoggerFactory.getLogger(ServerResponseSerializer.class);

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

		@SuppressWarnings("unchecked")
		AbstractRSAMessage<ServerMessageType> response = (AbstractRSAMessage<ServerMessageType>) msg;
		try {
			/* See Protocol_V1 and Protocol_V2 for header structure */
			ByteBuf buf = ctx.alloc()
				.ioBuffer();
			try {
				response.write(buf, promise);
			} catch (Exception e) {
				buf.clear();
				getErrorResponse(response, e).write(buf, promise);
			}

			ctx.writeAndFlush(buf, promise);
		} catch (Exception e) {
			LOG.error("An error occurred when invoking service {} ", response.getServiceId(), e);
		}
	}

	private AbstractRSAMessage<ServerMessageType> getErrorResponse(AbstractRSAMessage<ServerMessageType> response,
		Exception e) {
		AbstractRSAMessage<ServerMessageType> toReturn;
		switch (response.getType()) {
			case SUCCESS_RESPONSE_TYPE :
				toReturn = new ServerErrorMessageResponse(FAILURE_TO_SERIALIZE_SUCCESS_TYPE, response.getServiceId(),
					response.getCallId(), e.getMessage());
				break;
			case FAILURE_RESPONSE_TYPE :
				toReturn = new ServerErrorMessageResponse(FAILURE_TO_SERIALIZE_FAILURE_TYPE, response.getServiceId(),
					response.getCallId(), e.getMessage());
				break;
			default :
				toReturn = new ServerErrorMessageResponse(FAILURE_UNKNOWN_TYPE, response.getServiceId(),
					response.getCallId(), e.getMessage());
		}
		return toReturn;
	}
}
