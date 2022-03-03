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
package org.eclipse.ot.rsa.cluster.manager.provider;

import static java.util.Collections.emptyMap;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.ot.rsa.cluster.api.ClusterListener.CLUSTER_NAMES;
import static org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType.HEARTBEAT;
import static org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static org.eclipse.ot.rsa.cluster.manager.provider.WrappedClusterListener.getStringPlusProperty;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.ot.rsa.cluster.api.ClusterInformation;
import org.eclipse.ot.rsa.cluster.api.ClusterListener;
import org.eclipse.ot.rsa.cluster.gossip.api.ClusterManager;
import org.eclipse.ot.rsa.cluster.gossip.api.InternalClusterListener;
import org.eclipse.ot.rsa.cluster.gossip.config.ClusterGossipConfig;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.Snapshot;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType;
import org.eclipse.ot.rsa.logger.util.HLogger;
import org.eclipse.ot.rsa.servicecap.util.ServiceCapability;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;

@ServiceCapability(ClusterInformation.class)
public class ClusterManagerImpl implements ClusterInformation, ClusterManager {

	final BundleContext																context;
	final UUID																		id;
	final ClusterGossipConfig																	config;
	final int																		tcpPort;
	final AtomicBoolean																open		= new AtomicBoolean(
		true);
	final ConcurrentMap<ServiceReference<ClusterListener>, WrappedClusterListener>	listeners	= new ConcurrentHashMap<>();
	final ReadWriteLock																lock		= new ReentrantReadWriteLock();
	short																			stateSequence;
	ConcurrentMap<String, byte[]>													data		= new ConcurrentHashMap<>();
	final ConcurrentMap<UUID, MemberInfo>											members		= new ConcurrentHashMap<>();
	final EventExecutorGroup														gossipWorker;
	final EventExecutorGroup														listenerWorker;
	final InternalClusterListener													internalListener;
	final HLogger																	log;

	public ClusterManagerImpl(BundleContext context, UUID id, ClusterGossipConfig config, int udpPort, int tcpPort,
		InetAddress localAddress,
		org.osgi.util.function.Function<ClusterManager, InternalClusterListener> listenerFactory, HLogger log)
		throws Exception {
		this.context = context;
		this.log = log;
		this.id = id;
		this.config = config;
		this.tcpPort = tcpPort;
		this.gossipWorker = new DefaultEventExecutorGroup(1, r -> {
			Thread t = new FastThreadLocalThread(r, "Gossip worker - " + config.cluster_name());
			t.setDaemon(true);
			return t;
		});
		this.listenerWorker = new DefaultEventExecutorGroup(1, r -> {
			Thread t = new FastThreadLocalThread(r,
				"Gossip Cluster Listener notification worker - " + config.cluster_name());
			t.setDaemon(true);
			return t;
		});

		if (localAddress != null) {
			mergeSnapshot(new Snapshot(getSnapshot(PAYLOAD_UPDATE, 0), new InetSocketAddress(localAddress, udpPort)));
		} else {
			mergeSnapshot(getSnapshot(HEARTBEAT, 0));
		}

		internalListener = listenerFactory.apply(this);
		gossipWorker.scheduleAtFixedRate(this::prune, 500, 500, TimeUnit.MILLISECONDS);
	}

	@Override
	public Set<Snapshot> getMemberSnapshots(SnapshotType snapshotType) {
		return members.values()
			.stream()
			.filter(MemberInfo::isOpen)
			.map(m -> m.toSnapshot(snapshotType))
			.collect(toSet());
	}

	private void prune() {
		final long now = NANOSECONDS.toMillis(System.nanoTime());
		int probe = config.silent_node_probe_timeout();
		int evict = config.silent_node_eviction_timeout();
		internalListener.darkNodes(members.values()
			.stream()
			.filter(m -> !id.equals(m.getId()))
			.filter(m -> m.shouldResync(now, probe))
			.collect(toSet()));
		members.values()
			.removeIf((m) -> m.evictable(evict));
	}

	@Override
	public Update mergeSnapshot(Snapshot snapshot) {
		return members
			.computeIfAbsent(snapshot.getId(), (uuid) -> new MemberInfo(config, snapshot, this, listeners.values()))
			.update(snapshot);
	}

	@Override
	public Snapshot getSnapshot(SnapshotType type, int hops) {
		lock.readLock()
			.lock();
		try {
			switch (type) {
				case HEADER :
				case HEARTBEAT :
					return new Snapshot(id, tcpPort, stateSequence, type, emptyMap(), hops);
				case PAYLOAD_UPDATE :
					return new Snapshot(id, tcpPort, stateSequence, PAYLOAD_UPDATE, new HashMap<>(data), hops);
				default :
					throw new IllegalArgumentException("Unknown snapshot type " + type);
			}
		} finally {
			lock.readLock()
				.unlock();
		}
	}

	@Override
	public MemberInfo getMemberInfo(UUID uuid) {
		return members.get(uuid);
	}

	@Override
	public Collection<MemberInfo> selectRandomPartners(int number) {
		final List<MemberInfo> c = members.values()
			.stream()
			.filter(MemberInfo::isOpen)
			.filter((m) -> !id.equals(m.getId()))
			.collect(Collectors.toCollection(() -> new ArrayList<>()));

		return selectRandomPartners(number, c);
	}

	public static <T> Collection<T> selectRandomPartners(int number, final List<T> mutableList) {
		int size = mutableList.size();
		if (number > size) {
			number = size;
		}

		if (number == 0) {
			return Collections.emptyList();
		} else {
			Collection<T> partners = new ArrayList<>(number);
			ThreadLocalRandom current = ThreadLocalRandom.current();
			while (number > 0) {
				partners.add(mutableList.remove(current.nextInt(size)));
				size--;
				number--;
			}

			return partners;
		}
	}

	@Override
	public void listenerChange(ServiceReference<ClusterListener> ref, int state) {

		Set<String> clusters = getStringPlusProperty(ref, CLUSTER_NAMES);

		boolean forThisCluster = clusters.isEmpty() || clusters.contains(config.cluster_name());

		if (state == ServiceEvent.UNREGISTERING || !forThisCluster) {
			listeners.remove(ref);
		} else {
			try {
				listeners.computeIfAbsent(ref, (r) -> new WrappedClusterListener(context.getService(r), listenerWorker))
					.update(ref);
			} catch (IllegalStateException ise) {
				// The service wasn't valid any more
				return;
			}
		}

		members.values()
			.forEach((m) -> m.updateListeners(listeners.values()));
	}

	@Override
	public void destroy() {
		if (!open.getAndSet(false))
			return;

		List<Future<?>> l = new ArrayList<>();
		l.addAll(internalListener.destroy());

		members.values()
			.forEach(MemberInfo::close);
		members.clear();

		listeners.keySet()
			.removeIf((r) -> context.ungetService(r) || true);

		l.add(gossipWorker.shutdownGracefully(500, 1000, TimeUnit.MILLISECONDS));
		l.add(listenerWorker.shutdownGracefully(500, 1000, TimeUnit.MILLISECONDS));

		try {
			for (Future<?> f : l) {
				boolean ok = f.await(15_000);
				if (!ok) {
					log.warn("not terminating %s after 15 secs", f);
				}
			}
		} catch (InterruptedException e) {
			// Just exit now
		}
	}

	@Override
	public Collection<UUID> getKnownMembers() {
		return members.values()
			.stream()
			.filter(MemberInfo::isOpen)
			.map(MemberInfo::getId)
			.collect(toList());
	}

	@Override
	public Map<UUID, InetAddress> getMemberHosts() {
		return members.values()
			.stream()
			.filter(MemberInfo::isOpen)
			.collect(toMap(MemberInfo::getId, MemberInfo::getAddress));
	}

	@Override
	public String getClusterName() {
		return config.cluster_name();
	}

	@Override
	public InetAddress getAddressFor(UUID member) {
		return ofNullable(members.get(member)).filter(MemberInfo::isOpen)
			.map(MemberInfo::getAddress)
			.orElse(null);
	}

	@Override
	public UUID getLocalUUID() {
		return id;
	}

	@Override
	public byte[] getMemberAttribute(UUID member, String key) {
		return ofNullable(members.get(member)).filter(MemberInfo::isOpen)
			.map(MemberInfo::getData)
			.map((m) -> m.get(key))
			.map(b -> Arrays.copyOf(b, b.length))
			.orElse(null);
	}

	@Override
	public void updateAttribute(String key, byte[] bytes) {
		lock.writeLock()
			.lock();
		try {
			if (bytes == null) {
				data.remove(key);
			} else {
				if (bytes.length > 128) {
					log.info("A large amount of data %s is being associated with attribute %s", new Object[] {
						bytes.length, key
					});
				}
				data.put(key, Arrays.copyOf(bytes, bytes.length));
			}
			stateSequence++;
			log.debug("Updating advertised attribute %s. New state sequence is %s", key, stateSequence);

			Snapshot update = getSnapshot(PAYLOAD_UPDATE, 0);
			mergeSnapshot(update);
			internalListener.localUpdate(update);
		} finally {
			lock.writeLock()
				.unlock();
		}
	}

	@Override
	public Map<String, byte[]> getMemberAttributes(UUID member) {
		Function<Map<String, byte[]>, Map<String, byte[]>> copy = m -> m.entrySet()
			.stream()
			.collect(toMap(Entry::getKey, e -> Arrays.copyOf(e.getValue(), e.getValue().length)));

		return ofNullable(members.get(member)).filter(MemberInfo::isOpen)
			.map(MemberInfo::getData)
			.map(copy)
			.orElse(null);
	}

	@Override
	public void leavingCluster(Snapshot update) {
		ofNullable(members.get(update.getId())).ifPresent(MemberInfo::close);
	}

	@Override
	public void markUnreachable(MemberInfo member) {
		member.markUnreachable();
	}

	@Override
	public EventExecutorGroup getEventExecutorGroup() {
		return gossipWorker;
	}
}
