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

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.osgi.util.promise.Promise;

import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;

public interface GossipComms {

	public void publish(byte[] message,
			Collection<SocketAddress> participants);

	public Promise<Void> replicate(MemberInfo member,
			Collection<Snapshot> snapshots);

	public void destroy();

	public Promise<InetSocketAddress> punch(DatagramSocket socket, List<SocketAddress> peers);
	
	public InetAddress getBindAddress();
	
	public Certificate getCertificateFor(SocketAddress udpAddress);

	public boolean preventIndirectDiscovery();
	
	void sendKeyUpdate(Stream<InetSocketAddress> toNotify);
}