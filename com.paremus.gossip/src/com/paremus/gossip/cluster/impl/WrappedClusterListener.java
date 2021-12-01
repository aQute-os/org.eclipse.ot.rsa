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
package com.paremus.gossip.cluster.impl;

import static java.util.stream.Collectors.toSet;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.listener.Action;
import com.paremus.gossip.cluster.listener.ClusterListener;

public class WrappedClusterListener implements ClusterListener {

	private static final Logger logger = LoggerFactory.getLogger(WrappedClusterListener.class);
	
	private final ClusterListener listener;
	
	private final Executor executor;
	
	private final AtomicReference<Function<Set<String>, Set<String>>> filter = new AtomicReference<>();
	
	public WrappedClusterListener(ClusterListener listener, Executor executor) {
		if(listener == null) throw new IllegalStateException("Listener is invalid");
		this.listener = listener;
		this.executor = executor;
		filter.set(Function.identity());
	}

	@Override
	public void clusterEvent(ClusterInformation ci, Action action, UUID id, Set<String> addedKeys,
			Set<String> removedKeys, Set<String> updatedKeys) {
		Function<Set<String>, Set<String>> f = filter.get();
		
		Set<String> filteredAdded = f.apply(addedKeys);
		Set<String> filteredRemoved = f.apply(removedKeys);
		Set<String> filteredUpdated = f.apply(updatedKeys);
		
		if(logger.isDebugEnabled()) {
			logger.debug("Event: {} {} {} {}", new Object[] {action, filteredAdded, filteredRemoved, filteredUpdated});
		}
		
		if(action == Action.UPDATED && addedKeys.isEmpty() && removedKeys.isEmpty() && updatedKeys.isEmpty()) {
			return;
		} else {
			executor.execute(() -> listener.clusterEvent(ci, action, id, filteredAdded, filteredRemoved, filteredUpdated));
		}
	}
	
	public void update(ServiceReference<ClusterListener> ref) {
		Set<String> wanted = new HashSet<>();
		
		Object o = ref.getProperty(LIMIT_KEYS);
		
		if(o instanceof String) {
			wanted.add(o.toString());
		} else if (o instanceof String[]) {
			wanted.addAll(Arrays.asList((String[]) o));
		} else if (o instanceof Collection) {
			((Collection<?>) o).forEach((s) -> wanted.add(s.toString()));
		}
		
		if(wanted.isEmpty()) {
			filter.set(Function.identity());
		} else {
			filter.set((s) -> s.stream().filter(wanted::contains).collect(toSet()));
		}
		
	}
}
