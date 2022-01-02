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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.message.AbstractRSAMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public class ClientRequestSerializer extends ChannelOutboundHandlerAdapter {

	private static final Logger LOG = LoggerFactory.getLogger(ClientRequestSerializer.class);
	
	private final ClientResponseHandler responseHandler;
	
	public ClientRequestSerializer(ClientResponseHandler responseHandler) {
		this.responseHandler = responseHandler;
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		
		@SuppressWarnings("unchecked")
		AbstractRSAMessage<ClientMessageType> invocation = (AbstractRSAMessage<ClientMessageType>) msg;
		
		ClientMessageType callType = invocation.getType();
		
		try {
			/* See Protocol_V1 and Protocol_V2 for header structure */
			ByteBuf buffer = ctx.alloc().ioBuffer();
			invocation.write(buffer, promise);
			
			switch(callType.getAction()) {
			
				case ADD :
					responseHandler.registerInvocation((AbstractClientInvocationWithResult) invocation);
					promise.addListener(f -> {
							if (!f.isSuccess()) {
								responseHandler.unregisterInvocation(invocation.getKey());
							}
						});
					break;
				case REMOVE :
					responseHandler.unregisterInvocation(invocation.getKey());
					break;
				case SKIP :
					break;
				default :
					throw new IllegalArgumentException("An unknown action type " + callType.getAction().name()
						+ " was made on service " + invocation.getServiceId());
			}
			ctx.write(buffer, promise);
		} catch (Exception e) {
			LOG.error("An error occurred when invoking service {} ", invocation.getServiceId(), e);
			if(!promise.isVoid()) {
				promise.tryFailure(e);
			}
		}
	}
}
