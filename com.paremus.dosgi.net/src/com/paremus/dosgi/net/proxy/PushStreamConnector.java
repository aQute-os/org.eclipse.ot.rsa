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
package com.paremus.dosgi.net.proxy;

import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import org.osgi.framework.ServiceException;

import com.paremus.dosgi.net.client.BeginStreamingInvocation;
import com.paremus.dosgi.net.client.ClientBackPressure;
import com.paremus.dosgi.net.client.EndStreamingInvocation;
import com.paremus.dosgi.net.message.AbstractRSAMessage.CacheKey;
import com.paremus.dosgi.net.pushstream.PushStreamFactory.OnConnect;
import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.channel.Channel;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;

public class PushStreamConnector implements OnConnect<Object> {

	private final Channel _channel;
	
	private final Serializer _serializer;
	
	public PushStreamConnector(Channel _channel, Serializer _serializer) {
		this._channel = _channel;
		this._serializer = _serializer;
	}

	@Override
	public void connect(CacheKey key, EventExecutor worker, Future<?> closeFuture, 
			ToLongFunction<Object> pushData, Consumer<Exception> pushClose) {
		ClientBackPressure template = new ClientBackPressure(
				key.getId(), key.getCallId(), 0);
		
		Consumer<Object> onData = t ->
			{
				long bp = pushData.applyAsLong(t);
				
				if(bp != 0) {
					if(bp < 0) {
						_channel.writeAndFlush(new EndStreamingInvocation(
								key.getId(), key.getCallId()), _channel.voidPromise());
						pushClose.accept(null);
					} else {
						_channel.writeAndFlush(template.fromTemplate(bp), 
								_channel.voidPromise());
					}
				}
			};
		
		// Open the channel
		_channel.writeAndFlush(new BeginStreamingInvocation(key.getId(), key.getCallId(), 
				_serializer, worker, onData, pushClose, closeFuture)).addListener(f -> {
					if(!f.isSuccess()) {
						pushClose.accept(new ServiceException("Unable to open the data stream",
								ServiceException.REMOTE, f.cause()));
					}
				});
	}

}
