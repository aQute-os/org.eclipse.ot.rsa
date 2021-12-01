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
package com.paremus.gossip.cluster.listener;

import java.util.Set;
import java.util.UUID;

import org.osgi.annotation.versioning.ConsumerType;

import com.paremus.gossip.cluster.ClusterInformation;


@ConsumerType
public interface ClusterListener {
	
	public static final String LIMIT_KEYS = "limit.keys";

	public void clusterEvent(ClusterInformation cluster, Action action, UUID id, Set<String> addedKeys, 
			Set<String> removedKeys, Set<String> updatedKeys);
	
}
