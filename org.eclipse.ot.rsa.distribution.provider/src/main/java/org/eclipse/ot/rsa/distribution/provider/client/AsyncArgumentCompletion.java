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
package org.eclipse.ot.rsa.distribution.provider.client;

import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.ASYNC_ARG_FAILURE;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.ASYNC_ARG_SUCCESS;
import static org.osgi.framework.ServiceException.REMOTE;

import java.io.IOException;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractPayloadMessage;
import org.osgi.framework.ServiceException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public class AsyncArgumentCompletion extends AbstractPayloadMessage<ClientMessageType> {

	private final int				parameterIndex;

	private final Object			result;

	private final ClientInvocation	invocation;

	public AsyncArgumentCompletion(boolean success, ClientInvocation invocation, int i, Object result) {
		super(success ? ASYNC_ARG_SUCCESS : ASYNC_ARG_FAILURE, invocation.getServiceId(), invocation.getCallId(),
			invocation.getSerializer());

		this.invocation = invocation;

		this.parameterIndex = i;
		this.result = result;
	}

	public int getParameterIndex() {
		return parameterIndex;
	}

	public final Object getResult() {
		return result;
	}

	public ClientInvocation getParentInvocation() {
		return invocation;
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) throws IOException {
		writeHeader(buffer);
		buffer.writeByte(parameterIndex);
		getSerializer().serializeReturn(buffer, result);
		writeLength(buffer);

		promise.addListener(f -> {
			if (!f.isSuccess()) {
				// TODO log this?
				invocation.fail(new ServiceException("Failed to handle an asynchronous method parameter for service "
					+ getServiceId() + " due to a communications failure", REMOTE, f.cause()));
			}
		});
	}
}
