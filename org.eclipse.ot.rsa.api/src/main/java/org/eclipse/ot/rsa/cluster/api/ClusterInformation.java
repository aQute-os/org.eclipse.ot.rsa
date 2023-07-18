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
package org.eclipse.ot.rsa.cluster.api;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.osgi.annotation.versioning.ProviderType;

/**
 * This service is provided by the cluster implementation and it provides
 * information about the cluster. It is always registered with the property
 * <code>cluster.name</code> set to the name of the cluster.
 */
@ProviderType
public interface ClusterInformation {

	/**
	 * Get the known members of the cluster. Each UUID is the framework id of
	 * the remote OSGi framework
	 * 
	 * @return A collection containing the known cluster members
	 */
	Collection<UUID> getKnownMembers();

	/**
	 * Get the IP addresses for each of the members of the cluster
	 *
	 * @return A map of member ids to the address that they appear to be from
	 */
	Map<UUID, InetAddress> getMemberHosts();

	/**
	 * Get the name of this cluster
	 * 
	 * @return the cluster name
	 */
	String getClusterName();

	/**
	 * Get the IP address for a specific member
	 * 
	 * @param member the member to query
	 * @return the IP address
	 */
	InetAddress getAddressFor(UUID member);

	/**
	 * Get the UUID of this cluster member
	 * 
	 * @return the local UUID
	 */
	UUID getLocalUUID();

	/**
	 * Get the stored byte data for a given member
	 * 
	 * @param member the member to query
	 * @return the key to byte values stored by this member
	 */
	Map<String, byte[]> getMemberAttributes(UUID member);

	/**
	 * Get the stored byte data for a given key from a given member
	 * 
	 * @param member the member to query
	 * @param key the property key
	 * @return the bytes stored by the member for this key
	 */
	byte[] getMemberAttribute(UUID member, String key);

	/**
	 * Advertise the named attribute with the supplied data
	 * 
	 * @param key the property name
	 * @param data the data to advertise, or <code>null</code> to remove
	 *            previously advertised data for the key.
	 */
	void updateAttribute(String key, byte[] data);

}
