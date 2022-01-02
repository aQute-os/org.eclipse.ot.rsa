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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.framework.ServiceException;

import com.paremus.dosgi.net.pushstream.PushStreamFactory.DataStreamFactory;
import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;

class StreamReturnHandler extends BasicReturnHandler {
	
	private final AtomicInteger notWritableFor = new AtomicInteger(0);
	
	private final RemotingProvider remotingProvider;
	
	private final DataStreamFactory streamConnector;
	
	public StreamReturnHandler(UUID serviceId, Serializer serializer, Future<?> completeFuture,
			RemotingProvider remotingProvider, DataStreamFactory streamConnector) {
		super(serviceId, serializer, completeFuture);
		this.remotingProvider = remotingProvider;
		this.streamConnector = streamConnector;
	}

	@Override
	public Future<?> success(Channel channel, int callId, Object returnValue) {
		
		ServerStreamDataResponse template = new ServerStreamDataResponse(
				serviceId, callId, serializer, null);
		
		remotingProvider.registerStream(channel, serviceId, callId, 
				streamConnector.apply(data -> {
					channel.writeAndFlush(template.fromTemplate(data))
						.addListener(f -> {
								if(!f.isSuccess()) {
									channel.writeAndFlush(new ServerStreamErrorResponse(serviceId, callId, serializer, 
											new ServiceException("Failed to send data", ServiceException.REMOTE, f.cause())),
											channel.voidPromise());
									((AutoCloseable) returnValue).close();
								}
							});
					return channelBackPressure(channel);
				}, error -> {
					channel.writeAndFlush(error == null ? new ServerStreamCloseResponse(serviceId, callId) :
						new ServerStreamErrorResponse(serviceId, callId, serializer, error), channel.voidPromise());
				}, returnValue));
		return super.success(channel, callId, new Object[] {serviceId, callId});
	}
	
	private long channelBackPressure(Channel channel) {
		if(channel.isOpen()) {
			int notWritableCount = notWritableFor.getAndUpdate(old -> channel.isWritable() ? 0 : old++);
			return notWritableCount > 64 ? -1 : (1 << Math.min(notWritableCount / 2, 10)) - 1;
		}
		return -1;
	}
}
