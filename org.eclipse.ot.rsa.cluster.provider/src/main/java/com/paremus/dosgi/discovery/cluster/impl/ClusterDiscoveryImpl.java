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
package com.paremus.dosgi.discovery.cluster.impl;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.ot.rsa.cluster.api.Action;
import org.eclipse.ot.rsa.cluster.api.ClusterInformation;
import org.eclipse.ot.rsa.cluster.api.ClusterNetworkInformation;
import org.eclipse.ot.rsa.multrsa.api.ClusterDiscovery;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.discovery.cluster.comms.SocketComms;
import com.paremus.dosgi.discovery.cluster.local.LocalDiscoveryListener;
import com.paremus.dosgi.discovery.cluster.local.RemoteDiscoveryEndpoint;
import com.paremus.dosgi.discovery.cluster.remote.RemoteDiscoveryNotifier;
import com.paremus.dosgi.discovery.cluster.scope.EndpointFilter;
import com.paremus.netty.tls.ParemusNettyTLS;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;

public class ClusterDiscoveryImpl implements ClusterDiscovery {

	public static final String PAREMUS_DISCOVERY_DATA = "com.paremus.dosgi.discovery.cluster";

	private static final Logger logger = LoggerFactory.getLogger(ClusterDiscoveryImpl.class);

	private final UUID localId;

	private final ConcurrentMap<String, ClusterInformation> clusters = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, ClusterNetworkInformation> networkInfos = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, SocketComms> clusterComms = new ConcurrentHashMap<>();

	private final Lock clusterLock = new ReentrantLock();

	private final Config config;

	private final LocalDiscoveryListener localDiscoveryListener;

	private final RemoteDiscoveryNotifier remoteDiscoveryNotifier;

	private final ParemusNettyTLS ssl;

	private final EndpointFilter filter;

	private final Set<String> targetClusters;

	private final EventExecutorGroup worker;

	private final NioEventLoopGroup io;

	public ClusterDiscoveryImpl(BundleContext context, UUID id, LocalDiscoveryListener listener,
			ParemusNettyTLS ssl, Config config, EventExecutorGroup worker, EventExecutorGroup remoteEventDelivery) {
		this.localId = id;
		this.config = config;
		this.localDiscoveryListener = listener;
		this.ssl = ssl;
		this.worker = worker;

		this.io = new NioEventLoopGroup(1, r -> {
			Thread t = new FastThreadLocalThread(r, "Paremus Cluster RSA Discovery IO Worker");
			t.setDaemon(true);
			return t;
		});

		this.filter = new EndpointFilter(config.root_cluster(), config.base_scopes());

		remoteDiscoveryNotifier = new RemoteDiscoveryNotifier(filter, context, remoteEventDelivery);

		targetClusters = stream(config.target_clusters()).collect(toSet());
	}

	void addClusterInformation(ClusterInformation info) {
		clusterLock.lock();
		try {
			String clusterName = info.getClusterName();
			if((clusters.put(clusterName, info)) != null) {
				logger.warn("Two clusters exist for the same name {}. This can cause significant problems and result in unreliable topologies. Removing all members from the previous cluster.", clusterName);
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

	void addNetworkInformation(ClusterNetworkInformation svc) {
		try {
			String clusterName = svc.getClusterName();
			SocketComms comms;
			synchronized (clusterComms) {
				comms = clusterComms.computeIfAbsent(clusterName,
						f -> new SocketComms(localId, io, ssl,
								localDiscoveryListener, remoteDiscoveryNotifier, worker));
				comms.bind(svc, config);
			}
			advertiseDiscoveryData(clusterName, comms.getUdpPort());
		} catch (Exception e) {
			//TODO
			e.printStackTrace();
		}
	}

	void removeNetworkInformation(ClusterNetworkInformation svc) {
		ofNullable(clusterComms.remove(svc.getClusterName())).ifPresent(SocketComms::destroy);
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
			logger.error("A runtime error occurred for the Cluster Event {} {}", action, id, re);
		}
	}

	public Future<?> destroy() {
		for(ClusterInformation ci : clusters.values()) {
			ci.updateAttribute(PAREMUS_DISCOVERY_DATA, null);
		}
		localDiscoveryListener.destroy();

		@SuppressWarnings("deprecation")
		PromiseCombiner pc = new PromiseCombiner();
		clusterComms.values().stream()
			.map(SocketComms::destroy)
			.forEach(pc::add);
		remoteDiscoveryNotifier.destroy();
		Promise<Void> newPromise = ImmediateEventExecutor.INSTANCE.newPromise();
		pc.finish(newPromise);
		return newPromise;
	}

	private void updateRemoteDiscovery(ClusterInformation clusterInfo, UUID id, Set<String> updated, Set<String> removed) {

		String clusterName = clusterInfo.getClusterName();
		ClusterInformation ci = clusters.get(clusterName);
		if(ci == null) {
			logger.error("The node {} in gossip cluster {} is updated but the cluster information service for that cluster was not available",
					id, clusterName);
			return;
		} else if (!clusterInfo.equals(ci)) {
			logger.error("The cluster callback for node {} in {} was using a different cluster information service. Ignoring it", id, clusterName);
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
						() -> new RemoteDiscoveryEndpoint(id, clusterName, targetClusters, comms, host, portNumber, endpointFilter));
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

		SocketComms comms;
		synchronized (clusterComms) {
			comms = clusterComms.get(clusterName);
			if(comms == null) {
				comms = clusterComms.computeIfAbsent(clusterName,
					f -> new SocketComms(localId, io, ssl,
							localDiscoveryListener, remoteDiscoveryNotifier, worker));
			}
		}
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
	public Set<String> getClusters() {
		return filter.getClusters();
	}

	@Override
	public Set<String> getCurrentScopes() {
		return filter.getScopes();
	}

	@Override
	public Set<String> getBaseScopes() {
		return new HashSet<>(Arrays.asList(config.base_scopes()));
	}

	@Override
	public void addLocalScope(String name) {
		filter.addScope(name);
		remoteDiscoveryNotifier.filterChange();
		advertiseDiscoveryData();
	}

	@Override
	public void removeLocalScope(String name) {
		filter.removeScope(name);
		remoteDiscoveryNotifier.filterChange();
		advertiseDiscoveryData();
	}

	@Override
	public String getRootCluster() {
		return config.root_cluster();
	}

}
