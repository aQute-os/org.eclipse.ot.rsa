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
package com.paremus.gossip;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;

import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;

import io.netty.util.concurrent.Future;

public interface GossipComms {

	public void publish(GossipMessage message,
			Collection<InetSocketAddress> participants);

	public Future<Void> replicate(MemberInfo member,
			Collection<Snapshot> snapshots);

	public Future<?> destroy();

	public InetAddress getBindAddress();
	
	public boolean preventIndirectDiscovery();
}