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

import java.util.UUID;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractPayloadMessage;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public abstract class AbstractClientInvocationWithResult extends AbstractPayloadMessage<ClientMessageType> {

	public AbstractClientInvocationWithResult(ClientMessageType calltype, UUID serviceId, int callId,
		Serializer serializer) {
		super(calltype, serviceId, callId, serializer);
	}

	public abstract long getTimeout();

	public abstract void fail(Throwable e);

	public abstract void fail(ByteBuf o) throws Exception;

	public abstract void data(ByteBuf o) throws Exception;

	public abstract void addCompletionListener(GenericFutureListener<Future<Object>> listener);

}
