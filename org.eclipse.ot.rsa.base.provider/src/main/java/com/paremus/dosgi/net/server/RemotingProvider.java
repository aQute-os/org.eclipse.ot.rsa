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

import java.net.URI;
import java.util.Collection;
import java.util.UUID;

import com.paremus.dosgi.net.pushstream.PushStreamFactory.DataStream;

import io.netty.channel.Channel;

public interface RemotingProvider {

	boolean isSecure();

	Collection<URI> registerService(UUID id, ServiceInvoker invoker);

	void unregisterService(UUID id);

	void registerStream(Channel ch, UUID id, int callId, DataStream stream);

}
