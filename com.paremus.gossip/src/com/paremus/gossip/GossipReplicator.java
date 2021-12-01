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
import java.net.Socket;
import java.util.Collection;
import java.util.concurrent.Executor;

import org.osgi.util.promise.Promise;

import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;

public interface GossipReplicator {

	public abstract Promise<Long> outgoingExchangeSnapshots(Executor e,
			MemberInfo member, Collection<Snapshot> snapshots,
			InetAddress bindAddress);

	public abstract Promise<Long> incomingExchangeSnapshots(Executor e, Socket s);

}