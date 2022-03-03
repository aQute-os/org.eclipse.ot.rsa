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
package org.eclipse.ot.rsa.cluster.gossip.provider;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType.HEADER;
import static org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType.HEARTBEAT;
import static org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.ot.rsa.cluster.api.ClusterNetworkInformation;
import org.eclipse.ot.rsa.cluster.gossip.api.ClusterManager;
import org.eclipse.ot.rsa.cluster.gossip.api.Gossip;
import org.eclipse.ot.rsa.cluster.gossip.api.GossipComms;
import org.eclipse.ot.rsa.cluster.gossip.api.GossipMessage;
import org.eclipse.ot.rsa.cluster.gossip.api.InternalClusterListener;
import org.eclipse.ot.rsa.cluster.gossip.config.ClusterGossipConfig;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.AbstractGossipMessage;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.DisconnectionMessage;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.FirstContactRequest;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.FirstContactResponse;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.ForwardableGossipMessage;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.PingRequest;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.PingResponse;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.Snapshot;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType;
import org.eclipse.ot.rsa.cluster.manager.provider.ClusterManagerImpl;
import org.eclipse.ot.rsa.cluster.manager.provider.MemberInfo;
import org.eclipse.ot.rsa.cluster.manager.provider.Update;
import org.eclipse.ot.rsa.servicecap.util.ServiceCapability;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseNotifier;
import io.netty.util.concurrent.ScheduledFuture;

@ServiceCapability(ClusterNetworkInformation.class)
public class GossipImpl implements InternalClusterListener, Gossip {

	private static final Logger logger = LoggerFactory.getLogger(GossipImpl.class);

	private final ClusterManager manager;
	private final GossipComms comms;

	private final AtomicInteger updateCycles = new AtomicInteger();

	private final String cluster;

	private final ClusterGossipConfig config;

	private final List<InetSocketAddress> initialPeers;

	private final AtomicBoolean open = new AtomicBoolean(true);

	private final ConcurrentMap<UUID, Snapshot> toSend = new ConcurrentHashMap<>();

	private final BundleContext context;

	private final AtomicReference<ServiceRegistration<ClusterNetworkInformation>> netInfo = new AtomicReference<>();

	private final ScheduledFuture<?> doGossip;

	private final ScheduledFuture<?> doSync;

	public GossipImpl(BundleContext context, ClusterManager manager, Function<Gossip,GossipComms> commsCreator, ClusterGossipConfig config,
			List<InetSocketAddress> initialPeers) throws Exception {
		this.context = context;
		this.manager = manager;
		this.cluster = config.cluster_name();
		this.config = config;
		this.initialPeers = initialPeers;

		this.comms = commsCreator.apply(this);

		EventExecutorGroup gossipWorker = manager.getEventExecutorGroup();

		doGossip = gossipWorker.scheduleAtFixedRate(this::gossip, config.gossip_interval(), config.gossip_interval(), MILLISECONDS);

		long sync_interval = config.sync_interval();
		if(sync_interval > 0) {
			doSync = gossipWorker.scheduleAtFixedRate(() -> manager.selectRandomPartners(1).stream().findAny()
				.ifPresent(this::resynchronize), 0, sync_interval, MILLISECONDS);
		} else {
			doSync = null;
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ot.rsa.cluster.gossip.api.gossip.provider.Gossip#handleMessage(java.net.InetAddress, java.io.DataInput)
	 */
	@Override
	public void handleMessage(InetSocketAddress sender, GossipMessage message) {
		try {
			if(!open.get())
				return;

			AbstractGossipMessage gossip = (AbstractGossipMessage) message;
			Runnable action;

			switch(message.getType()) {
				case FIRST_CONTACT_REQUEST:
					action = () -> respondToFirstContact(gossip.getUpdate(sender));
					break;
				case FIRST_CONTACT_RESPONSE:
					action = () -> handleFirstContactResponse(sender, (FirstContactResponse) message);
					break;
				case FORWARDABLE:
					action = () -> handleGossip(sender, (ForwardableGossipMessage) message);
					break;
				case DISCONNECTION:
					action = () -> manager.leavingCluster(gossip.getUpdate(sender));
					break;
				case PING_REQUEST:
					action = () -> handlePingRequest(gossip.getUpdate(sender));
					break;
				case PING_RESPONSE:
					action = () -> handlePingResponse(gossip.getUpdate(sender));
					break;
				default:
					throw new IllegalArgumentException("Unknown message type " + message.getType());
			}

			if(cluster.equals(gossip.getClusterName())) {
				action.run();
			} else {
				logger.warn("Receieved a message with the wrong cluster host at the node {}. The clusters {} and {} run on the same hosts and have overlapping port ranges",
						new Object[] {manager.getLocalUUID(), gossip.getClusterName(), cluster});
			}

		} catch (Exception e) {
			logger.error("There was an error processing the gossip message", e);
		}
	}

	private void handleGossip(InetSocketAddress sender, ForwardableGossipMessage gm) {
		gm.getAllSnapshots(sender).stream().forEach((s) -> {

			if(comms.preventIndirectDiscovery() && manager.getMemberInfo(s.getId()) == null) {
				if(logger.isDebugEnabled()) {
					logger.debug("The node {} in cluster {} is not currently known and must be directly pinged.",
							new Object[] {s.getId(), cluster});
				}
				ping(s.getUdpAddress());
				return;
			}

			if(logger.isTraceEnabled()) {
				logger.debug("The node {} in cluster {} has received an update.",
						new Object[] {s.getId(), cluster});
			}

			Update u = manager.mergeSnapshot(s);
			if(manager.getLocalUUID().equals(s.getId()) && s.getUdpAddress() != null) {
				registerClusterNetworkInfo(s.getUdpAddress().getAddress());
			}

			switch(u) {
				case RESYNC:
					if(logger.isTraceEnabled()) { logger.trace("Out of sync with node {}", s.getId()); }
					ping(s.getUdpAddress());
				case FORWARD:
					if(s.forwardable()) {
						if(logger.isTraceEnabled()) { logger.trace("Forwardable snapshot from {}", s.getId()); }
						toSend.merge(s.getId(), s, (o, n) -> (o.getSnapshotTimestamp() - n.getSnapshotTimestamp()) > 0 ? o : n);
					}
					break;
				case CONSUME:
					break;
				case FORWARD_LOCAL:
					if(s.forwardable() && !toSend.containsKey(s.getId())) {
						if(logger.isTraceEnabled()) { logger.trace("Forward the local snapshot for {} as it is more up to date", s.getId()); }
						MemberInfo info = manager.getMemberInfo(s.getId());
						Snapshot s2 = info.toSnapshot(PAYLOAD_UPDATE, s.getRemainingHops());

						if(s.getMessageType() != PAYLOAD_UPDATE && s2.getStateSequenceNumber() == s.getStateSequenceNumber()) {
							s2 = info.toSnapshot(HEARTBEAT, s.getRemainingHops());
						}

						toSend.merge(s.getId(), s2, (o, n) -> (o.getSnapshotTimestamp() - n.getSnapshotTimestamp()) > 0 ? o : n);
					}
					break;
				default:
					logger.warn("Unhandled snapshot update state {}, the snapshot will not be forwarded", u);
			}
		});
	}

	private void respondToFirstContact(Snapshot s) {
		if(logger.isDebugEnabled()) {
			logger.debug("Responding to first contact from {} at {} in cluster {}",
					new Object[] {s.getId(), s.getUdpAddress(), cluster});
		}
		manager.mergeSnapshot(s);
		comms.publish(new FirstContactResponse(manager.getClusterName(), manager.getSnapshot(PAYLOAD_UPDATE, 0), s),
				Collections.singleton(s.getUdpAddress()));
	}

	private void handlePingResponse(Snapshot s) {
		if(logger.isDebugEnabled()) {
			logger.debug("Received reply to ping request from {} at {}", s.getId(), s.getUdpAddress());
		}
		manager.mergeSnapshot(s);
	}

	private void handlePingRequest(Snapshot s) {
		if(logger.isDebugEnabled()) {
			logger.debug("Received ping request from {} at {}", s.getId(), s.getUdpAddress());
		}
		manager.mergeSnapshot(s);
		comms.publish(new PingResponse(cluster, manager.getSnapshot(PAYLOAD_UPDATE, 0)),
				Collections.singleton(s.getUdpAddress()));
	}

	private void handleFirstContactResponse(InetSocketAddress sender, FirstContactResponse response) {
		Snapshot firstContactInfo = response.getFirstContactInfo();
		manager.mergeSnapshot(firstContactInfo);
		Snapshot remote = response.getUpdate(sender);

		if(logger.isDebugEnabled()) {
			logger.debug("Received reply to first contact from {} at {}", remote.getId(), remote.getUdpAddress());
		}
		manager.mergeSnapshot(remote);
		if(!manager.getLocalUUID().equals(remote.getId())) {
			resynchronize(manager.getMemberInfo(remote.getId()));
		}
		registerClusterNetworkInfo(firstContactInfo.getAddress());
	}

	private void registerClusterNetworkInfo(InetAddress localAddress) {
		if (netInfo.get() == null) {
			if(logger.isDebugEnabled()) {
				logger.debug("Registering network information for {} in the cluster {}",
						new Object[] {manager.getLocalUUID(), cluster});
			}

			ClusterNetworkInformationImpl impl = new ClusterNetworkInformationImpl(localAddress, cluster, comms,
					manager.getLocalUUID());

			Dictionary<String, Object> props = new Hashtable<>();
			props.put("cluster.name", cluster);

			ServiceRegistration<ClusterNetworkInformation> reg = context.registerService(ClusterNetworkInformation.class,
					impl, props);
			if(!netInfo.compareAndSet(null, reg)) {
				reg.unregister();
			}
			if(!open.get()) {
				reg = netInfo.getAndSet(null);
				if(reg != null) {
					reg.unregister();
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ot.rsa.cluster.gossip.api.gossip.provider.Gossip#merge(java.util.Collection)
	 */
	@Override
	public void merge(Snapshot snapshot) {
		InetSocketAddress udpAddress = snapshot.getUdpAddress();
		if(comms.preventIndirectDiscovery() && manager.getMemberInfo(snapshot.getId()) == null) {
			if(udpAddress != null) {
				if(logger.isDebugEnabled()) {
					logger.debug("The node {} in cluster {} is not currently known and must be directly pinged.",
							new Object[] {snapshot.getId(), cluster});
				}
				ping(snapshot.getUdpAddress());
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("The node {} in cluster {} is not currently known and cannot be queried.",
							new Object[] {snapshot.getId(), cluster});
				}
			}
		} else {
			if(logger.isTraceEnabled()) {
				logger.debug("The node {} in cluster {} has received an update.",
						new Object[] {snapshot.getId(), cluster});
			}

			manager.mergeSnapshot(snapshot);

			if(manager.getLocalUUID().equals(snapshot.getId())){

				if(udpAddress == null) {
					MemberInfo info = manager.getMemberInfo(snapshot.getId());
					if(info != null) {
						udpAddress = info.getUdpAddress();
					}
				}
				if(udpAddress != null) {
					registerClusterNetworkInfo(udpAddress.getAddress());
				}
			}
		}
	}

	private void gossip() {
		try {
			if(!open.get()) {
				return;
			}

			int updateCycle = updateCycles.getAndUpdate((i) -> (i < 1) ? 0 : i - 1);
			SnapshotType type = (updateCycle > 0) ? PAYLOAD_UPDATE : HEARTBEAT;

			Collection<MemberInfo> partners =  manager.selectRandomPartners(config.gossip_fanout());

			if(logger.isTraceEnabled()) {
				logger.trace("Sending a gossip message to members {} of cluster {}",
						partners.stream().map(MemberInfo::getId).collect(toSet()), cluster);
			}

			final Snapshot s;
			Runnable action;
			if(partners.isEmpty()) {
				s = manager.getSnapshot(PAYLOAD_UPDATE, 0);
				action = () -> comms.publish(new FirstContactRequest(cluster, s), getEndpoints(partners));
			} else {
				s = manager.getSnapshot(type, config.gossip_hops());

				List<Snapshot> q = toSend.values().stream()
						.collect(toList());
				toSend.clear();

				action = () -> comms.publish(new ForwardableGossipMessage(cluster, s, q),
						getEndpoints(partners));
			}

			manager.mergeSnapshot(s);
			action.run();
		} catch (Exception e) {
			logger.error("An error occurred while gossiping", e);
		}
	}

	private Collection<InetSocketAddress> getEndpoints(Collection<MemberInfo> partners) {
		Set<InetSocketAddress> s = partners.stream().map(MemberInfo::getUdpAddress).collect(toSet());

		if(s.size() < config.gossip_fanout()) {
			ArrayList<InetSocketAddress> peers = new ArrayList<>(initialPeers);
			peers.removeIf(isa -> {
				if(isa.getPort() != config.udp_port()) {
					return false;
				}

				if(isa.getAddress().equals(comms.getBindAddress())) {
					return true;
				} else if (comms.getBindAddress().isAnyLocalAddress()) {
					try {
						return NetworkInterface.getByInetAddress(isa.getAddress()) != null;
					} catch (Exception e) {
						return false;
					}
				} else {
					return false;
				}
			});
			s.addAll(ClusterManagerImpl.selectRandomPartners(config.gossip_fanout() - s.size(),
					peers));
		}

		return s;
	}

	private void resynchronize(MemberInfo member) {
		if(!open.get()) return;
		if(logger.isDebugEnabled()) {
			logger.debug("Requesting synchronisation with {}", member.getId());
		}
		if(config.sync_interval() <= 0) {
			logger.debug("TCP synchronization has been disabled");
			return;
		}

		comms.replicate(member, manager.getMemberSnapshots(HEADER))
			.addListener(f -> {
					if(!f.isSuccess() && !f.isCancelled()) {
						retryResyncInFuture(member, config.sync_retry())
							.addListener(f2 -> {
								if(!f.isSuccess() && !f.isCancelled()) {
									manager.markUnreachable(member);
								}
							});
					}
				});
	}

	private Future<?> retryResyncInFuture(MemberInfo member, long delay) {
		Promise<Object> newPromise = manager.getEventExecutorGroup().next().newPromise();

		manager.getEventExecutorGroup().schedule(
				() -> comms.replicate(member, manager.getMemberSnapshots(HEADER)),
				delay, MILLISECONDS).addListener(new PromiseNotifier<>(newPromise));
		return newPromise;
	}

	@Override
	public void localUpdate(Snapshot s) {
		updateCycles.set(config.gossip_broadcast_rounds());
	}

	@Override
	public List<Future<?>> destroy() {
		open.set(false);

		ServiceRegistration<?> reg = netInfo.getAndSet(null);
		if(reg != null) reg.unregister();

		doGossip.cancel(false);

		if(doSync != null) {
			doSync.cancel(false);
		}

		comms.publish(new DisconnectionMessage(cluster, manager.getSnapshot(HEARTBEAT, 0)),
				manager.getMemberSnapshots(HEARTBEAT).stream().map(Snapshot::getUdpAddress).collect(toList()));
		return comms.destroy();
	}

	@Override
	public void darkNodes(Collection<MemberInfo> darkNodes) {
		if(logger.isDebugEnabled() && !darkNodes.isEmpty()) {
			logger.debug("Node {} synchronizing with members {}", manager.getLocalUUID(), darkNodes.stream().map(MemberInfo::getId).collect(toList()));
		}
		darkNodes.stream().forEach(this::ping);
	}

	private void ping(MemberInfo mi) {
		mi.markUnreachable();
		InetSocketAddress udpAddress = mi.getUdpAddress();
		ping(udpAddress);
	}

	@Override
	public void ping(InetSocketAddress udpAddress) {
		comms.publish(new PingRequest(cluster, manager.getSnapshot(PAYLOAD_UPDATE, 0)), Collections.singleton(udpAddress));
	}

	@Override
	public MemberInfo getInfoFor(UUID id) {
		return manager.getMemberInfo(id);
	}

	@Override
	public Collection<Snapshot> getAllSnapshots() {
		return manager.getMemberSnapshots(HEADER);
	}
}
