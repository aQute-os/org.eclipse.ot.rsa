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
package com.paremus.gossip.impl;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.UUID;

import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;

import com.paremus.gossip.GossipComms;
import com.paremus.net.info.ClusterNetworkInformation;

public class ClusterNetworkInformationImpl implements ClusterNetworkInformation {

	private final InetAddress fibreAddress;

	private final String clusterName;
	
	private final GossipComms comms;

	private final boolean firewalled;
	
	private final UUID id;
	
	private final List<SocketAddress> peers;
	
	
	public ClusterNetworkInformationImpl(InetAddress fibreAddress, String clusterName,
			GossipComms comms, UUID id, List<SocketAddress> peers) {
		this.fibreAddress = fibreAddress;
		this.clusterName = clusterName;
		this.comms = comms;
		boolean firewalled;
		try {
			firewalled = NetworkInterface.getByInetAddress(fibreAddress) == null;
		} catch (SocketException se) {
			firewalled = true;
		}
		this.firewalled = firewalled;
		this.id = id;
		this.peers = peers;
	}

	@Override
	public Promise<InetSocketAddress> getAddressFor(DatagramSocket socket) {
		if(isFirewalled()) {
			return comms.punch(socket, peers);
		} else 
		return Promises.resolved((InetSocketAddress) socket.getLocalSocketAddress());
	}

	@Override
	public InetAddress getBindAddress() {
		return comms.getBindAddress();
	}

	@Override
	public boolean isFirewalled() {
		return firewalled;
	}

	@Override
	public InetAddress getFibreAddress() {
		return fibreAddress;
	}

	@Override
	public String getClusterName() {
		return clusterName;
	}

	@Override
	public UUID getLocalUUID() {
		return id;
	}

}
