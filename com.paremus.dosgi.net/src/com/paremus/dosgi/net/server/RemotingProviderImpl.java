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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;

class RemotingProviderImpl implements RemotingProvider {

	private final boolean secure;
	
	private final String scheme;
	
	private final ServerRequestHandler handler;
	
	private final Channel channel;

	private ChannelGroup channelGroup;
	
	public RemotingProviderImpl(boolean secure, String scheme, ServerRequestHandler handler, Channel channel,
			ChannelGroup group) {
		this.secure = secure;
		this.scheme = scheme;
		this.handler = handler;
		this.channel = channel;
		this.channelGroup = group;
	}

	@Override
	public boolean isSecure() {
		return secure;
	}

	@Override
	public URI registerService(UUID id, ServiceInvoker invoker) {
		handler.registerService(id, invoker);
		
		InetSocketAddress address = (InetSocketAddress) channel.localAddress();
		try {
			return new URI(scheme,  null,  address.getHostString(), address.getPort(), null, null, null);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
	}

	@Override
	public void unregisterService(UUID id) {
		handler.unregisterService(id);
	}

	public void close() {
		channelGroup.close();
	}
}
