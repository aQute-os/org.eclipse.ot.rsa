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

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

import org.osgi.util.promise.Promise;

public interface ClusterNetworkInformation {

	public Promise<InetSocketAddress> getAddressFor(DatagramSocket socket);
	
	public InetAddress getBindAddress();

	public boolean isFirewalled();
	
	public InetAddress getFibreAddress();
	
	public String getClusterName();

	public UUID getLocalUUID();
	
}
