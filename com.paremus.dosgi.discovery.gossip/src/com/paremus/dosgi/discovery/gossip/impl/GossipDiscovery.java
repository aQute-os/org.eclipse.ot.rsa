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
package com.paremus.dosgi.discovery.gossip.impl;

import static java.util.Optional.ofNullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.discovery.gossip.comms.SocketComms;
import com.paremus.dosgi.discovery.gossip.local.LocalDiscoveryListener;
import com.paremus.dosgi.discovery.gossip.local.RemoteDiscoveryEndpoint;
import com.paremus.dosgi.discovery.gossip.remote.RemoteDiscoveryNotifier;
import com.paremus.dosgi.discovery.gossip.scope.EndpointFilter;
import com.paremus.dosgi.discovery.scoped.ScopedDiscovery;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.listener.Action;
import com.paremus.net.encode.EncodingSchemeFactory;
import com.paremus.net.info.ClusterNetworkInformation;

public class GossipDiscovery implements ScopedDiscovery {

	public static final String PAREMUS_DISCOVERY_DATA = "com.paremus.dosgi.discovery";
	
	private static final Logger logger = LoggerFactory.getLogger(GossipDiscovery.class);
	
	private final UUID localId;
	
	private final ConcurrentMap<String, ClusterInformation> clusters = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, ClusterNetworkInformation> networkInfos = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, SocketComms> clusterComms = new ConcurrentHashMap<>();
	
	private final Lock clusterLock = new ReentrantLock();
	
	private final Config config;
	
	private final LocalDiscoveryListener localDiscoveryListener;
	
	private final RemoteDiscoveryNotifier remoteDiscoveryNotifier;

	private final ServiceTracker<ClusterInformation, ClusterInformation> clusterTracker;
	private final ServiceTracker<ClusterNetworkInformation, ClusterNetworkInformation> networkInformationTracker;

	private final EncodingSchemeFactory esf;
	
	private final EndpointFilter filter;

	public GossipDiscovery(BundleContext context, UUID id, LocalDiscoveryListener listener,
			EncodingSchemeFactory esf, Config config) {
		this.localId = id;
		this.config = config;
		this.localDiscoveryListener = listener;
		this.esf = esf;
		
		this.filter = new EndpointFilter(config.root_cluster());
		
		remoteDiscoveryNotifier = new RemoteDiscoveryNotifier(filter, context);
		
		clusterTracker = new ServiceTracker<ClusterInformation, ClusterInformation>(context, ClusterInformation.class, null) {

			@Override
			public ClusterInformation addingService(
					ServiceReference<ClusterInformation> reference) {
				ClusterInformation svc = super.addingService(reference);
				addClusterInformation(svc);
				return svc;
			}

			@Override
			public void removedService(
					ServiceReference<ClusterInformation> reference,
					ClusterInformation service) {
				removeClusterInformation(service);
				super.removedService(reference, service);
			}
			
		};
		networkInformationTracker = new ServiceTracker<ClusterNetworkInformation, ClusterNetworkInformation>(
				context, ClusterNetworkInformation.class, null) {
			
			@Override
			public ClusterNetworkInformation addingService(
					ServiceReference<ClusterNetworkInformation> reference) {
				ClusterNetworkInformation svc = super.addingService(reference);
				String clusterName = svc.getClusterName();
				
				if(networkInfos.put(clusterName, svc) != null) {
					logger.warn("More than one network information service exists for the cluster {}", clusterName);
				}
				
				ClusterInformation ci = clusters.get(clusterName);
				if(ci == null) {
					logger.error("The node {} in gossip cluster {} is updated but the cluster information service for that cluster was not available", 
							id, clusterName);
					return svc;
				}
				
				addNetworkInformation(svc, clusterName, ci);
				return svc;
			}

			@Override
			public void removedService(
					ServiceReference<ClusterNetworkInformation> reference,
					ClusterNetworkInformation service) {
				ofNullable(clusterComms.remove(service.getClusterName())).ifPresent(SocketComms::destroy);
			}
			
		};
		clusterTracker.open();
		networkInformationTracker.open();
		
		
	}
	
	void addClusterInformation(ClusterInformation info) {
		clusterLock.lock();
		try {
			String clusterName = info.getClusterName();
			if((clusters.put(clusterName, info)) != null) {
				logger.warn("Two gossip clusters exist for the same name {}. This can cause significant problems and result in unreliable topologies. Removing all members from the previous cluster.", clusterName);
				deleteCluster(clusterName);
			}
			filter.addCluster(clusterName);
		}
		finally {
			clusterLock.unlock();
		}
		advertiseDiscoveryData();
	}

	void removeClusterInformation(ClusterInformation info) {
		boolean changed = false;
		clusterLock.lock();
		try {
			String clusterName = info.getClusterName();
			if(clusters.remove(clusterName, info)) {
				deleteCluster(clusterName);
				filter.removeCluster(clusterName);
				changed = true;
			}
		} finally {
			clusterLock.unlock();
		}
		if(changed) {
			advertiseDiscoveryData();
		}
	}

	private void deleteCluster(String clusterName) {
		localDiscoveryListener
			.removeRemotesForCluster(clusterName)
			.stream()
			.forEach((remoteDiscoveryNotifier::revokeAllFromFramework));
	}
	
	void addNetworkInformation(ClusterNetworkInformation svc, String clusterName, ClusterInformation ci) {
		try {
			SocketComms comms = clusterComms.computeIfAbsent(clusterName, 
					f -> new SocketComms(localId, ci, localDiscoveryListener, 
							remoteDiscoveryNotifier, esf));
			comms.bind(svc, config);
			advertiseDiscoveryData(clusterName, comms.getUdpPort());
		} catch (Exception e) {
			//TODO
			e.printStackTrace();
		}
	}

	private void advertiseDiscoveryData() {
		clusters.keySet().forEach(f -> ofNullable(clusterComms.get(f))
				.ifPresent(c -> advertiseDiscoveryData(f, c.getUdpPort())));
	}
	
	private void advertiseDiscoveryData(String clusterName, int udpPort) {
		ClusterInformation ci = clusters.get(clusterName);
		if(ci == null) {
			logger.warn("The discovery for cluster {} has started, but the cluster information service for that cluster is not available. The discovery port cannot be advertised.", 
					clusterName);
			return;
		}
		
		byte[] bytes = ci.getMemberAttribute(localId, PAREMUS_DISCOVERY_DATA);
		
		if(udpPort == -1) {
			if(bytes != null) {
				ci.updateAttribute(PAREMUS_DISCOVERY_DATA, null);
			}
		} else {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (DataOutputStream dos = new DataOutputStream(baos)){
				dos.writeShort(udpPort);
				filter.writeOut(dos);
			} catch (IOException e) {}
			ci.updateAttribute(PAREMUS_DISCOVERY_DATA, baos.toByteArray());
		}
	}

	public void clusterEvent(ClusterInformation clusterInfo, Action action, UUID id, Set<String> addedKeys, 
			Set<String> removedKeys, Set<String> updatedKeys) {
		try {
			switch(action) {
				case REMOVED:
					if(localDiscoveryListener.removeRemote(clusterInfo.getClusterName(), id)) {
						remoteDiscoveryNotifier.revokeAllFromFramework(id);
					}
					break;
				case ADDED:
				case UPDATED:
					updateRemoteDiscovery(clusterInfo, id, updatedKeys, removedKeys);
			}
		} catch (RuntimeException re) {
			//TODO
			re.printStackTrace();
		}
	}

	public void destroy() {
		for(ClusterInformation ci : clusters.values()) {
			ci.updateAttribute(PAREMUS_DISCOVERY_DATA, null);
		}
		clusterTracker.close();
		networkInformationTracker.close();
		localDiscoveryListener.destroy();
		clusterComms.values().forEach(SocketComms::destroy);
		remoteDiscoveryNotifier.destroy();
	}

	private void updateRemoteDiscovery(ClusterInformation clusterInfo, UUID id, Set<String> updated, Set<String> removed) {
		
		String clusterName = clusterInfo.getClusterName();
		ClusterInformation ci = clusters.get(clusterName);
		if(ci == null) {
			logger.error("The node {} in gossip cluster {} is updated but the cluster information service for that cluster was not available", 
					id, clusterName);
			return;
		} else if (!clusterInfo.equals(ci)) {
			logger.error("The cluster callback for node {} in {} was using a different cluster information service. Ignoring it");
			return;
		}
		
		SocketComms comms = createComms(clusterName, id, ci);
		
		if(this.localId.equals(id)) {
			return;
		}
		
		InetAddress host = ci.getAddressFor(id);
		if(host == null) {
			logger.error("The node {} in gossip cluster {} is updated but no network address is available for that node", 
					id, clusterName);
			return;
		}
		
		if(removed.contains(PAREMUS_DISCOVERY_DATA)) {
			if(logger.isInfoEnabled()) {
				logger.info("The remote node {} in cluster {} is no longer running gossip based discovery.", id, clusterName);
			}
			localDiscoveryListener.removeRemote(clusterName, id);
			remoteDiscoveryNotifier.revokeAllFromFramework(id);
			return;
		}
		
		byte[] data = ci.getMemberAttribute(id, PAREMUS_DISCOVERY_DATA);
		if(data != null) {
			try(DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
				int portNumber = dis.readUnsignedShort();
				EndpointFilter endpointFilter = EndpointFilter.createFilter(dis);
				if(logger.isDebugEnabled()) {
					logger.debug("The remote node {} in cluster {} is participating in gossip-based discovery with {} on port {}.", 
							new Object[] {id, clusterName, localId, portNumber});
				}
				localDiscoveryListener.updateRemote(clusterName, id, portNumber, endpointFilter,  
						() -> new RemoteDiscoveryEndpoint(id, clusterName, comms, host, portNumber, endpointFilter));
			} catch (IOException e) {
				//Impossible in a spec compliant VM
			}
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("The remote node {} in cluster {} is not participating in gossip-based discovery", id, clusterName);
			}
		}
	}

	private SocketComms createComms(String clusterName, UUID id,
			ClusterInformation ci) {
		SocketComms comms = clusterComms.computeIfAbsent(clusterName, 
				f -> new SocketComms(id, ci, localDiscoveryListener, 
						remoteDiscoveryNotifier, esf));
		if(!comms.isBound()) {
			ClusterNetworkInformation fni = networkInfos.get(clusterName);
			if(fni != null) {
				comms.bind(fni, config);
				advertiseDiscoveryData(clusterName, comms.getUdpPort());
			}
		}
		return comms;
	}

	@Override
	public Set<String> clusters() {
		return filter.getClusters();
	}

	@Override
	public Set<String> scopes() {
		return filter.getScopes();
	}

	@Override
	public void addScope(String name) {
		filter.addScope(name);
		remoteDiscoveryNotifier.filterChange();
		advertiseDiscoveryData();
	}

	@Override
	public void removeScope(String name) {
		filter.removeScope(name);
		remoteDiscoveryNotifier.filterChange();
		advertiseDiscoveryData();
	}

}
