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

import static com.paremus.gossip.v1.messages.SnapshotType.HEARTBEAT;
import static com.paremus.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static java.util.Collections.emptyMap;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.ClusterManager;
import com.paremus.gossip.InternalClusterListener;
import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.listener.ClusterListener;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.gossip.v1.messages.SnapshotType;

public class ClusterManagerImpl implements ClusterInformation, ClusterManager {
	
	private static final Logger logger = LoggerFactory.getLogger(ClusterManagerImpl.class);
	
	private final BundleContext context;

	private final UUID id;
	
	private final Config config;
	
	private final int tcpPort;
	
	private final AtomicBoolean open = new AtomicBoolean(true);
	
	private final ConcurrentMap<ServiceReference<ClusterListener>, WrappedClusterListener> listeners = 
			new ConcurrentHashMap<>();
	
	private final ReadWriteLock lock = new ReentrantReadWriteLock();
	
	private short stateSequence;
	
	private ConcurrentMap<String, byte[]> data = new ConcurrentHashMap<String, byte[]>();
	
	private final ConcurrentMap<UUID, MemberInfo> members = new ConcurrentHashMap<>();
	
	private final ScheduledExecutorService timeoutWorker;
	
	private final ExecutorService listenerWorker;
	
	private final AtomicReference<InternalClusterListener> internalListener = new AtomicReference<>();
	
	public ClusterManagerImpl(BundleContext context, UUID id, Config config, int udpPort, int tcpPort,
			InetAddress localAddress) {
		this.context = context;
		this.id = id;
		this.config = config;
		this.tcpPort = tcpPort;
		this.timeoutWorker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Gossip Cluster maintenance worker - " + config.cluster_name());
			t.setDaemon(true);
			return t;
		});
		this.listenerWorker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "Gossip Cluster notification worker - " + config.cluster_name());
			t.setDaemon(true);
			return t;
		});
		
		if(localAddress != null) {
			mergeSnapshot(new Snapshot(getSnapshot(PAYLOAD_UPDATE, 0), new InetSocketAddress(localAddress, udpPort)));
		} else {
			mergeSnapshot(getSnapshot(HEARTBEAT, 0));
		}
		
		timeoutWorker.scheduleAtFixedRate(this::prune, 500, 500, TimeUnit.MILLISECONDS);
	}

	public void setInternalListener(InternalClusterListener internalListner) {
		internalListener.set(internalListner);
	}
	
	public Set<Snapshot> getMemberSnapshots(SnapshotType snapshotType) {
		return members.values().stream().filter(MemberInfo::isOpen)
				.map(m -> m.toSnapshot(snapshotType)).collect(toSet());
	}

	private void prune() {
		final long now = NANOSECONDS.toMillis(System.nanoTime());
		int probe = config.silent_node_probe_timeout();
		int evict = config.silent_node_eviction_timeout();
		ofNullable(internalListener.get()).ifPresent((l) -> l.darkNodes(
				members.values().stream().filter(m -> !id.equals(m.getId()))
					.filter(m -> m.shouldResync(now, probe)).collect(toSet())));
		members.values().removeIf((m) -> m.evictable(evict));
	}

	public Update mergeSnapshot(Snapshot snapshot) {
		return members.computeIfAbsent(snapshot.getId(), 
				(uuid) -> new MemberInfo(config, snapshot, this, listeners.values()))
				.update(snapshot);
	}

	public Snapshot getSnapshot(SnapshotType type, int hops) {
		lock.readLock().lock(); 
		try {
			switch(type) {
				case HEADER:
				case HEARTBEAT:
					return new Snapshot(id, tcpPort, stateSequence, type, emptyMap(), hops);
				case PAYLOAD_UPDATE:
					return new Snapshot(id, tcpPort, stateSequence, PAYLOAD_UPDATE, new HashMap<>(data), hops);
				default:
					throw new IllegalArgumentException("Unknown snapshot type " + type);
			}
		} finally {
			lock.readLock().unlock();
		}
	}
	
	public MemberInfo getMemberInfo(UUID uuid) {
		return members.get(uuid);
	}
	
	public Collection<MemberInfo> selectRandomPartners(int number) {
		final Collection<MemberInfo> c = members.values().stream().filter(MemberInfo::isOpen)
				.filter((m) -> !id.equals(m.getId())).collect(Collectors.toSet());
		ThreadLocalRandom random = ThreadLocalRandom.current();
		
		int required = number;
		int denominator = c.size();
		Iterator<MemberInfo> iterator = c.iterator();
		
		Collection<MemberInfo> partners = new ArrayList<>(required);
		while(iterator.hasNext() && denominator > 0) {
			if(random.nextInt(denominator--) < required) {
				partners.add(iterator.next());
				if(--required == 0) break;
			}
		}
		
		return partners;
	}
	
	public void listenerChange(ServiceReference<ClusterListener> ref, int state) {
		if(state == ServiceEvent.UNREGISTERING) {
			listeners.remove(ref);
		} else {
			try {
				listeners.computeIfAbsent(ref, 
					(r) -> new WrappedClusterListener(context.getService(r), listenerWorker)).update(ref); 
			} catch (IllegalStateException ise) {
				//The service wasn't valid any more
				return;
			}
		}
		
		members.values().forEach((m) -> m.updateListeners(listeners.values()));
	}
	
	public void destroy() {
		open.set(false);
		timeoutWorker.shutdownNow();
		
		ofNullable(internalListener.get()).ifPresent(InternalClusterListener::destroy);
		
		members.values().forEach(MemberInfo::close);
		members.clear();
		
		listeners.keySet().removeIf((r) -> context.ungetService(r) || true);
	}

	@Override
	public Collection<UUID> getKnownMembers() {
		return members.values().stream().filter(MemberInfo::isOpen).map(MemberInfo::getId).collect(toList());
	}

	@Override
	public Map<UUID, InetAddress> getMemberHosts() {
		return members.values().stream().filter(MemberInfo::isOpen)
				.collect(toMap(MemberInfo::getId, MemberInfo::getAddress));
	}

	@Override
	public String getClusterName() {
		return config.cluster_name();
	}

	@Override
	public InetAddress getAddressFor(UUID member) {
		return ofNullable(members.get(member)).filter(MemberInfo::isOpen).map(MemberInfo::getAddress).orElse(null);
	}

	@Override
	public Certificate getCertificateFor(UUID member) {
		return ofNullable(members.get(member))
				.filter(MemberInfo::isOpen)
				.map(m -> ofNullable(internalListener.get()).map((l) -> l.getCertificateFor(m)).orElse(null))
				.orElse(null);
	}

	@Override
	public UUID getLocalUUID() {
		return id;
	}

	@Override
	public byte[] getMemberAttribute(UUID member, String key) {
		return ofNullable(members.get(member)).filter(MemberInfo::isOpen).map(MemberInfo::getData)
				.map((m) -> m.get(key)).map(b -> Arrays.copyOf(b, b.length)).orElse(null);
	}

	@Override
	public void updateAttribute(String key, byte[] bytes) {
		lock.writeLock().lock();
		try {
			if(bytes == null) {
				data.remove(key);
			} else if (bytes.length > 512) {
				throw new IllegalArgumentException("The supplied attribute data is too large. A maximum of 512 bytes is supported");
			} else {	
				if(bytes.length > 128) {
					logger.warn("A large amount of data {} is being associated with attribute {}", 
							new Object[] {bytes.length, key });
				}
				data.put(key, Arrays.copyOf(bytes, bytes.length));
			}
			stateSequence ++;
			if(logger.isDebugEnabled()) {
				logger.debug("Updating advertised attribute {}. New state sequence is {}", key, stateSequence);
			}
			//Eagerly update ourselves 
			Snapshot update = getSnapshot(PAYLOAD_UPDATE, 0);
			mergeSnapshot(update);
			ofNullable(internalListener.get()).ifPresent((l) -> l.localUpdate(update));
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public Map<String, byte[]> getMemberAttributes(UUID member) {
		Function<Map<String, byte[]>, Map<String, byte[]>> copy = 
				m ->  m.entrySet().stream()
				.collect(toMap(Entry::getKey, 
					e -> (byte[]) Arrays.copyOf(e.getValue(), e.getValue().length)));
		
		return ofNullable(members.get(member))
				.filter(MemberInfo::isOpen)
				.map(MemberInfo::getData)
				.map(copy)
				.orElse(null);
	}

	public void leavingCluster(Snapshot update) {
		ofNullable(members.get(update.getId())).ifPresent(MemberInfo::close);
	}

	public void markUnreachable(MemberInfo member) {
		member.markUnreachable();
	}

	@Override
	public void notifyKeyChange() {
		 ofNullable(internalListener.get()).ifPresent(il ->
		 	il.sendKeyUpdate(members.values().stream()
		 			.filter(mi -> !id.equals(mi.getId()))
		 			.filter(MemberInfo::isOpen)
		 			.map(mi -> (InetSocketAddress) mi.getUdpAddress())));
	}
}
