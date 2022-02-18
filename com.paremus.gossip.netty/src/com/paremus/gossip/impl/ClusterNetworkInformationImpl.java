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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.UUID;

import com.paremus.gossip.GossipComms;
import com.paremus.net.info.ClusterNetworkInformation;

public class ClusterNetworkInformationImpl implements ClusterNetworkInformation {

	private final InetAddress fibreAddress;

	private final String clusterName;

	private final GossipComms comms;

	private final boolean firewalled;

	private final UUID id;

	public ClusterNetworkInformationImpl(InetAddress fibreAddress, String clusterName,
			GossipComms comms, UUID id) {
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
