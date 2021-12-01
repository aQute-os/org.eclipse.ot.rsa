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
package com.paremus.gossip.impl;

import static com.paremus.gossip.v1.messages.SnapshotType.HEADER;
import static com.paremus.gossip.v1.messages.SnapshotType.HEARTBEAT;
import static com.paremus.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.ClusterManager;
import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipComms;
import com.paremus.gossip.InternalClusterListener;
import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.cluster.impl.Update;
import com.paremus.gossip.v1.messages.AbstractGossipMessage;
import com.paremus.gossip.v1.messages.DisconnectionMessage;
import com.paremus.gossip.v1.messages.FirstContactRequest;
import com.paremus.gossip.v1.messages.FirstContactResponse;
import com.paremus.gossip.v1.messages.ForwardableGossipMessage;
import com.paremus.gossip.v1.messages.MessageType;
import com.paremus.gossip.v1.messages.PingRequest;
import com.paremus.gossip.v1.messages.PingResponse;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.gossip.v1.messages.SnapshotType;
import com.paremus.net.info.ClusterNetworkInformation;

public class GossipImpl implements InternalClusterListener, Gossip {

	private static final Logger logger = LoggerFactory.getLogger(GossipImpl.class);
	
	private final ClusterManager manager;
	private final GossipComms comms;
	
	private final AtomicInteger updateCycles = new AtomicInteger();
	
	private final String cluster;
	
	private final Config config;
	
	private final List<SocketAddress> initialPeers;
	
	private final ScheduledExecutorService gossipWorker;
	
	private final AtomicBoolean open = new AtomicBoolean(true);
	
	private final ConcurrentMap<UUID, Snapshot> toSend = new ConcurrentHashMap<>();
	
	private final BundleContext context;
	
	private final AtomicReference<ServiceRegistration<ClusterNetworkInformation>> netInfo = new AtomicReference<>();
	
	public GossipImpl(BundleContext context, ClusterManager manager, GossipComms comms, Config config,
			List<SocketAddress> initialPeers) {
		this.context = context;
		this.manager = manager;
		this.comms = comms;
		this.cluster = config.cluster_name();
		this.config = config;
		this.initialPeers = initialPeers;
		AtomicInteger threadId = new AtomicInteger();
		this.gossipWorker = Executors.newScheduledThreadPool(4, r -> {
						Thread t = new Thread(r, "Gossip Communications worker - " 
								+ cluster + " " + threadId.incrementAndGet());
						t.setDaemon(true);
						return t;
					});
		
		gossipWorker.scheduleAtFixedRate(this::gossip, config.gossip_interval(), config.gossip_interval(), MILLISECONDS);
		
		long sync_interval = config.sync_interval();
		if(sync_interval > 0) {
			gossipWorker.scheduleAtFixedRate(() -> manager.selectRandomPartners(1).stream().findAny()
				.ifPresent(this::resynchronize), 0, sync_interval, MILLISECONDS);
		}
	}

	/* (non-Javadoc)
	 * @see com.paremus.gossip.impl.Gossip#handleMessage(java.net.InetAddress, java.io.DataInput)
	 */
	@Override
	public void handleMessage(InetSocketAddress sender, DataInput message) {
		try {
			if(!open.get())
				return;
			
			int version = message.readUnsignedByte();
			if(version != 1) {
				logger.error("The version {} from {} is not supported.", version, sender);
				return;
			}

			MessageType messageType;
			int type = message.readUnsignedByte();
			try {
				messageType = MessageType.values()[type];
			} catch (ArrayIndexOutOfBoundsException aioobe) {
				logger.error("The type {} from {} is not supported.", type, sender);
				return;
			}
		
			AbstractGossipMessage gossip;
			Runnable action;
			
			switch(messageType) {
				case FIRST_CONTACT_REQUEST:
					gossip = new FirstContactRequest(message);
					action = () -> respondToFirstContact(gossip.getUpdate(sender));
					break;
				case FIRST_CONTACT_RESPONSE:
					gossip = new FirstContactResponse(message);
					action = () -> handleFirstContactResponse(sender, (FirstContactResponse) gossip);
					break;
				case FORWARDABLE:
					gossip = new ForwardableGossipMessage(message);
					action = () -> handleGossip(sender, (ForwardableGossipMessage) gossip);
					break;
				case DISCONNECTION:
					gossip = new DisconnectionMessage(message);
					action = () -> manager.leavingCluster(gossip.getUpdate(sender));
					break;
				case PING_REQUEST:
					gossip = new PingRequest(message);
					action = () -> handlePingRequest(gossip.getUpdate(sender));
					break;
				case PING_RESPONSE:
					gossip = new PingResponse(message);
					action = () -> handlePingResponse(gossip.getUpdate(sender));
					break;
				default:
					throw new IllegalArgumentException("Unknown message type " + messageType);
			}
			
			if(cluster.equals(gossip.getClusterName())) {
				action.run();
			} else {
				logger.warn("Receieved a message with the wrong cluster name at the node {}. The clusters {} and {} run on the same hosts and have overlapping port ranges",
						new Object[] {manager.getLocalUUID(), gossip.getClusterName(), cluster});
			}
			
		} catch (IOException e) {
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
			if(manager.getLocalUUID().equals(s.getId())) {
				registerClusterNetworkInfo(s);
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
	
	private byte[] encode(AbstractGossipMessage gossipMessage) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
			DataOutput output = new DataOutputStream(baos);
			output.write(1);
			output.write(gossipMessage.getType().ordinal());
			gossipMessage.writeOut(output);
			return baos.toByteArray();
		} catch (IOException ioe) {
			logger.error("Unable to encode a message", ioe);
			return new byte[0];
		}
	}

	private void respondToFirstContact(Snapshot s) {
		if(logger.isDebugEnabled()) {
			logger.debug("Responding to first contact from {} at {} in cluster {}", 
					new Object[] {s.getId(), s.getUdpAddress(), cluster});
		}
		byte[] data = encode(new FirstContactResponse(manager.getClusterName(), manager.getSnapshot(PAYLOAD_UPDATE, 0), s));
		comms.publish(data, Collections.singleton(s.getUdpAddress()));
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
		byte[] data = encode(new PingResponse(cluster, manager.getSnapshot(PAYLOAD_UPDATE, 0)));
		comms.publish(data, Collections.singleton(s.getUdpAddress()));
		manager.mergeSnapshot(s);
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
		
		registerClusterNetworkInfo(firstContactInfo);
	}

	private void registerClusterNetworkInfo(Snapshot firstContactInfo) {
		if (netInfo.get() == null) {
			if(logger.isDebugEnabled()) {
				logger.debug("Registering network information for {} in the cluster {}", 
						new Object[] {manager.getLocalUUID(), cluster});
			}
			
			ClusterNetworkInformationImpl impl = new ClusterNetworkInformationImpl(firstContactInfo.getAddress(), cluster, comms, 
					manager.getLocalUUID(), initialPeers);
			
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
	 * @see com.paremus.gossip.impl.Gossip#merge(java.util.Collection)
	 */
	@Override
	public Snapshot merge(Snapshot snapshot) {
		Snapshot toReturn = null;
		
		if(comms.preventIndirectDiscovery() && manager.getMemberInfo(snapshot.getId()) == null) {
			if(logger.isDebugEnabled()) {
				logger.debug("The node {} in cluster {} is not currently known and must be directly pinged.",
						new Object[] {snapshot.getId(), cluster});
			}
			ping(snapshot.getUdpAddress());
		} else {
			if(logger.isTraceEnabled()) {
				logger.debug("The node {} in cluster {} has received an update.",
						new Object[] {snapshot.getId(), cluster});
			}
			
			switch(manager.mergeSnapshot(snapshot)) {
				case FORWARD_LOCAL :
					MemberInfo member = manager.getMemberInfo(snapshot.getId());
					if(member != null) {
						toReturn = member.toSnapshot();
					}
					break;
				case RESYNC :
					ping(snapshot.getUdpAddress());
				case FORWARD :
					toReturn = snapshot;
					break;
				case CONSUME:
					break;
			}
		}
		
		if(manager.getLocalUUID().equals(snapshot.getId())){
			registerClusterNetworkInfo(snapshot);
		}
		return toReturn;
	}
	
	private void gossip() {
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
			action = () -> comms.publish(encode(new FirstContactRequest(cluster, s)), getEndpoints(partners));
		} else {
			s = manager.getSnapshot(type, config.gossip_hops());

			List<Snapshot> q = toSend.values().stream()
					.collect(toList());
			toSend.clear();
			
			action = () -> comms.publish(encode(new ForwardableGossipMessage(cluster, s, q)), 
					getEndpoints(partners));
		}
		
		manager.mergeSnapshot(s);
		action.run();
	}
	
	private Collection<SocketAddress> getEndpoints(Collection<MemberInfo> partners) {
		Set<SocketAddress> s = partners.stream().map(MemberInfo::getUdpAddress).collect(toSet());
		s.add(initialPeers.get(ThreadLocalRandom.current().nextInt(initialPeers.size())));
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
			.recoverWith((p) -> retryResyncInFuture(member, config.sync_retry()))
			.then(null, (p) -> manager.markUnreachable(member));
	}
	
	private Promise<Void> retryResyncInFuture(MemberInfo member, long delay) {
		Deferred<Void> deferred = new Deferred<Void>();
		gossipWorker.schedule(() -> deferred.resolveWith(
				comms.replicate(member, manager.getMemberSnapshots(HEADER))), delay, MILLISECONDS);
		return deferred.getPromise();
	}

	@Override
	public void localUpdate(Snapshot s) {
		updateCycles.set(config.gossip_broadcast_rounds());
	}

	@Override
	public void destroy() {
		open.set(false);
		
		ServiceRegistration<?> reg = netInfo.getAndSet(null);
		if(reg != null) reg.unregister();
		
		gossipWorker.shutdown();
		try {
			gossipWorker.awaitTermination(500, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			//TODO log
		}
		comms.publish(encode(new DisconnectionMessage(cluster, manager.getSnapshot(HEARTBEAT, 0))), 
				manager.getMemberSnapshots(HEARTBEAT).stream().map(Snapshot::getUdpAddress).collect(toList()));
		comms.destroy();
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
		SocketAddress udpAddress = mi.getUdpAddress();
		ping(udpAddress);
	}

	@Override
	public void ping(SocketAddress udpAddress) {
		byte[] data = encode(new PingRequest(cluster, manager.getSnapshot(PAYLOAD_UPDATE, 0)));
		comms.publish(data, Collections.singleton(udpAddress));
	}

	@Override
	public MemberInfo getInfoFor(UUID id) {
		return manager.getMemberInfo(id);
	}

	@Override
	public Certificate getCertificateFor(MemberInfo member) {
		return comms.getCertificateFor(member.getUdpAddress());
	}

	@Override
	public Collection<Snapshot> getAllSnapshots() {
		return manager.getMemberSnapshots(HEADER);
	}

	@Override
	public void sendKeyUpdate(Stream<InetSocketAddress> toNotify) {
		comms.sendKeyUpdate(toNotify);
	}
}
