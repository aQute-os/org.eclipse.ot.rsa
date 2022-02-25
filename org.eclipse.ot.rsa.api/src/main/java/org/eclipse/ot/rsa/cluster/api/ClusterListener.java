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

import java.util.Set;
import java.util.UUID;

import org.osgi.annotation.versioning.ConsumerType;

/**
 * A whiteboard service interface implemented by bundles who wish to be notified
 * about changes in the cluster
 *
 * <p>
 * The {@link #CLUSTER_NAMES} service property can be used to limit the set of clusters
 * for which this service will be called back. If it is not set then the service will be
 * notified for <strong>all</strong> clusters
 *
 * <p>
 * The {@link #LIMIT_KEYS} service property can be used to limit the set of property
 * keys in which this service is interested. If it is not set then the service will
 * be notified of changes to <strong>all</strong> changes in stored values.
 */
@ConsumerType
public interface ClusterListener {

	/**
	 * This service property is used to limit the set of key/value changes the service
	 * will be notified about
	 */
	public static final String LIMIT_KEYS = "limit.keys";

	/**
	 * This service property is used to limit the set of clusters that the service
	 * will be notified about
	 */
	public static final String CLUSTER_NAMES = "cluster.names";

	/**
	 * This method is called to indicate that the cluster has changed in some way
	 *
	 * @param cluster The cluster which has changed
	 * @param action The type of the change (member add, update or remove)
	 * @param id the Id of the node that has changed
	 * @param addedKeys the set of keys which are newly added
	 * @param removedKeys the set of keys which are removed
	 * @param updatedKeys the set of keys which have new values
	 */
	public void clusterEvent(ClusterInformation cluster, Action action, UUID id, Set<String> addedKeys,
			Set<String> removedKeys, Set<String> updatedKeys);

}
