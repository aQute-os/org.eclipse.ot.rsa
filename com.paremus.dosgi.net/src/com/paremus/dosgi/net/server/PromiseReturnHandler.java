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
import java.util.function.Function;

import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;

class PromiseReturnHandler extends BasicReturnHandler {
	
	private final Function<Object, Future<Object>> toNettyFuture;
	
	public PromiseReturnHandler(UUID serviceId, Serializer serializer, Future<?> completeFuture,
			Function<Object, Future<Object>> toNettyFuture) {
		super(serviceId, serializer, completeFuture);
		this.toNettyFuture = toNettyFuture;
	}

	@Override
	public Future<?> success(Channel channel, int callId, Object returnValue) {
		return toNettyFuture.apply(returnValue)
			.addListener(f -> asyncResponse(channel, callId, f));
	}

	private void asyncResponse(Channel channel, int callId, Future<? super Object> f) {
		if(f.isSuccess()) {
			sendReturn(channel, callId, true, f.getNow());
		} else {
			sendReturn(channel, callId, false, f.cause());
		}
	}
}
