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
package com.paremus.net.info;

import java.net.InetAddress;
import java.util.UUID;

import org.osgi.annotation.versioning.ProviderType;

@ProviderType
public interface ClusterNetworkInformation {

	/**
	 * The bind address that this cluster is using
	 *
	 * @return the address to which this server is bound
	 */
	public InetAddress getBindAddress();

	/**
	 * Our best guess as to whether there is a NAT firewall between us
	 * and the remote node
	 * @return true if a NAT firewall has been detected
	 */
	public boolean isFirewalled();

	/**
	 * Get the address of this fibre as seen by the
	 * outside world
	 *
	 * @return The InetAddress of this fibre as used
	 *  by a remote node to communicate with us
	 */
	public InetAddress getFibreAddress();

	/**
	 * Get the name of this cluster
	 * @return the cluster name
	 */
	public String getClusterName();

	/**
	 * Get the UUID of this cluster member
	 * @return the local UUID
	 */
	public UUID getLocalUUID();

}
