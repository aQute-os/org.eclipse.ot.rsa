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
import java.util.concurrent.CompletionStage;

import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.channel.Channel;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

class JavaCompletionStageReturnHandler extends BasicReturnHandler {
	
	private EventExecutorGroup executor;

	public JavaCompletionStageReturnHandler(UUID serviceId, Serializer serializer, Future<?> completeFuture,
			EventExecutorGroup executor) {
		super(serviceId, serializer, completeFuture);
		this.executor = executor;
	}

	@Override
	public Future<?> success(Channel channel, int callId, Object returnValue) {
		Promise<Object> p = executor.next().newPromise();
		((CompletionStage<?>) returnValue).whenComplete(
				(r,t) -> asyncResponse(channel, callId, r, t, p));
		p.addListener(f -> {
				if(f.isCancelled() && returnValue instanceof java.util.concurrent.Future) {
					((java.util.concurrent.Future<?>) returnValue).cancel(true);
				}
			});
		return p;
	}

	private void asyncResponse(Channel channel, int callId, Object r, Throwable t, Promise<Object> p) {
		if(t == null) {
			sendReturn(channel, callId, true, r);
		} else {
			sendReturn(channel, callId, false, t);
		}
		p.trySuccess(null);
	}
}
