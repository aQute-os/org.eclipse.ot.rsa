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

import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;

class BasicReturnHandler implements ReturnHandler {

	protected final UUID serviceId;
	protected final Serializer serializer;
	private Future<?> completeFuture;

	public BasicReturnHandler(UUID serviceId, Serializer serializer, Future<?> completeFuture) {
		this.serviceId = serviceId;
		this.serializer = serializer;
		this.completeFuture = completeFuture;
	}

	@Override
	public Future<?> success(Channel channel, int callId, Object returnValue) {
		sendReturn(channel, callId, true, returnValue);
		return completeFuture;
	}

	@Override
	public Future<?> failure(Channel channel, int callId, Throwable failure) {
		sendReturn(channel, callId, false, failure);
		return completeFuture;
	}

	protected void sendReturn(Channel channel, int callId, boolean successful, Object o) {
		channel.writeAndFlush(
				new MethodCompleteResponse(successful, serviceId, callId, serializer, o),
				channel.voidPromise());
	}
}
