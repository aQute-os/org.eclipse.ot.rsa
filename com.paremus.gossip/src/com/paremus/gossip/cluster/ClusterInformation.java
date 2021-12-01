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
package com.paremus.gossip.cluster;

import java.net.InetAddress;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.osgi.annotation.versioning.ProviderType;

@ProviderType
public interface ClusterInformation {

	Collection<UUID> getKnownMembers();

	Map<UUID, InetAddress> getMemberHosts();
	
	String getClusterName();
	
	InetAddress getAddressFor(UUID member);

	Certificate getCertificateFor(UUID member);
	
	UUID getLocalUUID();
	
	Map<String, byte[]> getMemberAttributes(UUID member);

	byte[] getMemberAttribute(UUID member, String key);
	
	void updateAttribute(String key, byte[] data);
	
}
