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
package com.paremus.dosgi.discovery.gossip.local;

import static java.util.stream.Collectors.toMap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.discovery.gossip.comms.SocketComms;
import com.paremus.dosgi.discovery.gossip.scope.EndpointFilter;

public class RemoteDiscoveryEndpoint {
	
	private static final Logger logger = LoggerFactory.getLogger(RemoteDiscoveryEndpoint.class);

	private final UUID id;
	private final String clusterName;
	private final AtomicReference<EndpointFilter> filter = new AtomicReference<>();
	private final SocketComms comms;
	private final AtomicReference<InetSocketAddress> address = new AtomicReference<>();
	private final AtomicInteger reminderCounter = new AtomicInteger();
	
	private final ConcurrentMap<EndpointDescription, Integer> published = new ConcurrentHashMap<>();
	
	public RemoteDiscoveryEndpoint(UUID id, String clusterName, SocketComms comms, 
			InetAddress host, int port, EndpointFilter endpointFilter) {
		if(logger.isDebugEnabled()) {
			logger.debug("Added remote interest from node {} in cluster {}, at {}:{}", new Object[] {id, clusterName, host, port});
		}
		address.set(new InetSocketAddress(host, port));
		this.id = id;
		this.clusterName = clusterName;
		this.comms = comms;
		filter.set(endpointFilter);
	}
	
	public void update(int port, EndpointFilter endpointFilter) {
		InetSocketAddress oldAddress = address.get();
		if(port != oldAddress.getPort()) {
			if(logger.isDebugEnabled()) {
				logger.debug("Updating the discovery port for {} to {}",
					new Object[] {id, port});
			}
			InetSocketAddress newAddress = new InetSocketAddress(oldAddress.getAddress(), port);
			address.set(newAddress);
			comms.stopCalling(id, oldAddress);
			comms.newDiscoveryEndpoint(id, newAddress);
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("Updated the remote discovery filter for {} with clusters {} and systems {}",
					new Object[] {id, endpointFilter.getClusters(), endpointFilter.getScopes()});
			}
		}
		
		filter.set(endpointFilter);
		Map<EndpointDescription, Integer> copy = published.entrySet().stream()
				.collect(toMap(Entry::getKey, Entry::getValue));
		
		copy.forEach((ed, i) -> {
			published.remove(ed);
			if(endpointFilter.accept(ed)) {
				publishEndpoint(i, ed, false);
			}
		});
	}
	
	public UUID getId() {
		return id;
	}

	public SocketAddress getAddress() {
		return address.get();
	}
	
	public String getClusterName() {
		return clusterName;
	}


	public void revokeEndpoint(Integer revocationCounter,
			EndpointDescription ed) {
		if(published.remove(ed) != null) {
			if(logger.isDebugEnabled()) {
				logger.debug("Revoking the endpoint {} from {} for update {}",
					new Object[] {ed.getId(), id, revocationCounter});
			}
			
			comms.revokeEndpoint(ed.getId(), revocationCounter, id, address.get());
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("The endpoint {} has not been published to {}, and does not need to be revoked",
					new Object[] {ed.getId(), id});
			}
		}
	}


	public void publishEndpoint(Integer counter, EndpointDescription endpoint, boolean force) {
		if(filter.get().accept(endpoint)) {
			if(force || !counter.equals(published.get(endpoint))) {
				if(logger.isDebugEnabled()) {
					logger.debug("Publishing endpoint {} to {} for update {}",
						new Object[] {endpoint.getId(), id, counter});
				}
				published.merge(endpoint, counter, (o, n) -> o.compareTo(n) > 0 ? o : n);
				comms.publishEndpoint(endpoint, counter, id, address.get());
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("The endpoint {} has already been published to {} for update {}",
						new Object[] {endpoint.getId(), id, counter});
				}
			}
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("The endpoint {} is being ignored by the filter for the remote node {}",
					new Object[] {endpoint.getId(), id});
			}
		}
	}
	
	public void stopCalling() {
		if(logger.isDebugEnabled()) {
			logger.debug("Shutting down the remote discovery endpoint for {}", id);
		}
		published.clear();
		comms.stopCalling(id, address.get());
	}


	public void open() {
		if(logger.isDebugEnabled()) {
			logger.debug("Starting the remote discovery endpoint for {}", id);
		}
		comms.newDiscoveryEndpoint(id, address.get());
	}
	
	public void sendReminder() {
		Set<String> toRemind = published.keySet().stream()
				.map(EndpointDescription::getId)
				.collect(Collectors.toSet());
		if(!toRemind.isEmpty()) {
			comms.sendReminder(toRemind,
				reminderCounter.incrementAndGet(), id, address.get());
		}
	}
}
