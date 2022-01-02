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

import static com.paremus.dosgi.net.server.ServerMessageType.FAILURE_SERIALIZATION_ERROR;
import static com.paremus.dosgi.net.server.ServerMessageType.RETURN_SERIALIZATION_ERROR;
import static com.paremus.dosgi.net.server.ServerMessageType.UNKNOWN_ERROR;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.message.AbstractRSAMessage;

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
			ByteBuf buf = ctx.alloc().ioBuffer();
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

	private AbstractRSAMessage<ServerMessageType> getErrorResponse(
			AbstractRSAMessage<ServerMessageType> response, Exception e) {
		AbstractRSAMessage<ServerMessageType> toReturn;
		switch(response.getType()) {
			case SUCCESS:
				toReturn = new ServerErrorMessageResponse(RETURN_SERIALIZATION_ERROR, 
						response.getServiceId(), response.getCallId(), e.getMessage());
				break;
			case FAILURE:
				toReturn = new ServerErrorMessageResponse(FAILURE_SERIALIZATION_ERROR, 
						response.getServiceId(), response.getCallId(), e.getMessage());
				break;
			default:
				toReturn = new ServerErrorMessageResponse(UNKNOWN_ERROR, 
						response.getServiceId(), response.getCallId(), e.getMessage());
		}
		return toReturn;
	}
}
