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

import java.io.DataInput;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.UUID;

import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;

public interface Gossip {

	public abstract void handleMessage(InetSocketAddress sender, DataInput message);

	public abstract Snapshot merge(Snapshot snapshot);

	public abstract Collection<Snapshot> getAllSnapshots();
	
	public abstract MemberInfo getInfoFor(UUID id);

	public abstract void ping(SocketAddress udpAddress);
	
}