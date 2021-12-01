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

import static com.paremus.gossip.cluster.impl.Update.CONSUME;
import static com.paremus.gossip.cluster.impl.Update.FORWARD;
import static com.paremus.gossip.cluster.impl.Update.FORWARD_LOCAL;
import static com.paremus.gossip.cluster.impl.Update.RESYNC;
import static com.paremus.gossip.cluster.listener.Action.ADDED;
import static com.paremus.gossip.cluster.listener.Action.REMOVED;
import static com.paremus.gossip.cluster.listener.Action.UPDATED;
import static com.paremus.gossip.v1.messages.SnapshotType.HEADER;
import static com.paremus.gossip.v1.messages.SnapshotType.HEARTBEAT;
import static com.paremus.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static java.util.Collections.emptySet;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toSet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.listener.ClusterListener;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.gossip.v1.messages.SnapshotType;

public class MemberInfo {
	
	private static final Logger logger = LoggerFactory.getLogger(MemberInfo.class);
	
	private final UUID id;
	private final String cluster;
	private final ClusterInformation ci;
	
	private InetAddress address;
	private int udpPort;
	private int tcpPort;
	
	private short stateSequenceNumber;
	private int messageTimeStamp;
	
	private Map<String, byte[]> data;
	
	private long lastUpdatedTimestamp;
	
	private int unreachableCount;
	
	private boolean initialised = false;

	private boolean closed;
	
	private Collection<ClusterListener> listeners = new HashSet<>();

	public MemberInfo(Config config, Snapshot s, ClusterInformation ci, Collection<? extends ClusterListener> listeners) {
		this.ci = ci;
		this.id = s.getId();
		this.cluster = config.cluster_name();
		this.address = s.getAddress();
		this.udpPort = s.getUdpPort();
		this.tcpPort = s.getTcpPort();
		this.messageTimeStamp = s.getSnapshotTimestamp();
		
		this.listeners.addAll(listeners);
		lastUpdatedTimestamp = NANOSECONDS.toMillis(System.nanoTime());
	}

	public synchronized long getLastUpdatedTimestamp() {
		return lastUpdatedTimestamp;
	}

	public UUID getId() {
		return id;
	}

	public synchronized InetAddress getAddress() {
		return address;
	}

	public synchronized SocketAddress getUdpAddress() {
		return new InetSocketAddress(address, udpPort);
	}

	public synchronized SocketAddress getTcpAddress() {
		return new InetSocketAddress(address, tcpPort);
	}

	public synchronized Map<String, byte[]> getData() {
		if(closed) return Collections.emptyMap();
		return data;
	}

	public synchronized void updateListeners(Collection<? extends ClusterListener> listeners) {
		if(closed) return;
		
		if(initialised) {
			listeners.stream().filter(l -> !this.listeners.contains(l)).forEach(
					(l) -> l.clusterEvent(ci, ADDED, id, data.keySet(), emptySet(), emptySet()));
		}

		this.listeners = new HashSet<>(listeners);
	}
	
	public synchronized Update update(Snapshot s) {
		if(closed) {
			if(logger.isDebugEnabled()) {
				logger.debug("Ignoring snapshot for {} as the member is closed", s.getId());
			}
			return CONSUME;
		}
		
		if(address == null) {
			if(s.getAddress() != null) {
				address = s.getAddress();
			} else {
				logger.debug("Ignoring update for member {} because its address has not yet been determined.", id);
				return CONSUME;
			}
		} else if(s.getAddress() != null && !address.equals(s.getAddress())) {
			logger.warn("The Snapshot tried to change the address for node {} from {} to {}", 
					new Object[] {id, address, s.getAddress()});
		}
		
		if(!initialised) {
			return initialise(s);
		}
		
		long now = NANOSECONDS.toMillis(System.nanoTime());
		int seqDelta = s.getStateSequenceNumber() - stateSequenceNumber;
		int timeDelta = s.getSnapshotTimestamp() - messageTimeStamp;
		
		if(seqDelta < 0 || timeDelta < 0) {
			//An old message
			if(logger.isDebugEnabled()) {
				logger.debug("Ignoring snapshot {} in cluster {} with deltas as it is old. The sequence delta was {} and the time delta was {}", 
						new Object[] {s, cluster, seqDelta, timeDelta });
			}
			return FORWARD_LOCAL;
		} else {
			unreachableCount = 0;
		}
		
		if(s.getMessageType() != HEADER) {
			if(s.getUdpPort() != -1 && udpPort != s.getUdpPort()) {
				logger.warn("The Snapshot is changing the UDP port for node {} in cluster {} from {} to {}. If this occurs frequently then there is a problem with the node", 
						new Object[] {id, cluster, udpPort, s.getUdpPort()});
				udpPort = s.getUdpPort();
			}
			if(tcpPort != s.getTcpPort()) {
				logger.warn("The Snapshot is changing the TCP port for node {} in cluster {} from {} to {}. If this occurs frequently then there is a problem with the node", 
						new Object[] {id, cluster, tcpPort, s.getTcpPort()});
				tcpPort = s.getTcpPort();
			}
		}
		if(seqDelta == 0) {
			if(logger.isTraceEnabled()) {
				logger.trace("The member {} of cluster {} has received an updated heartbeat", id, cluster);
			}
			
			messageTimeStamp = s.getSnapshotTimestamp();
			lastUpdatedTimestamp = now;
			return FORWARD;
		} else if (s.getMessageType() != PAYLOAD_UPDATE){
			if(logger.isTraceEnabled()) {
				logger.trace("The member {} of cluster {} has missed a payload update", id, cluster);
			}
			return RESYNC;
		} else {
			if(logger.isTraceEnabled()) {
				logger.trace("The member {} of cluster {} has an updated payload", id, cluster);
			}
			stateSequenceNumber = s.getStateSequenceNumber();
			messageTimeStamp = s.getSnapshotTimestamp();
			Map<String, byte[]> snapshotData = s.getData();
			lastUpdatedTimestamp = now;
			
			Set<String> added = snapshotData.entrySet().stream().filter(
					(e) -> !data.containsKey(e.getKey())).map(Entry::getKey).collect(toSet());
			
			Set<String> removed = data.entrySet().stream().filter(
					(e) -> !snapshotData.containsKey(e.getKey())).map(Entry::getKey).collect(toSet());
			
			Set<String> updated = snapshotData.entrySet().stream().filter(
					(e) -> data.containsKey(e.getKey()) && !Arrays.equals(e.getValue(), data.get(e.getKey())))
					.map(Entry::getKey).collect(toSet());
			
			data = new HashMap<>(snapshotData);
			listeners.stream().forEach((c) -> c.clusterEvent(ci, UPDATED, id, added, removed, updated));
			return FORWARD;
		}
	}

	private Update initialise(Snapshot s) {
		if(s.getMessageType() != PAYLOAD_UPDATE) {
			if(logger.isDebugEnabled()) {
				logger.debug("Received a first update that was a heartbeat - requesting resynchronization with {}", s.getId());
			}
			return RESYNC;
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("Initialising member {} with snapshot {}", s.getId(), s);
			}
			stateSequenceNumber = s.getStateSequenceNumber();
			messageTimeStamp = s.getSnapshotTimestamp();
			data = new HashMap<>(s.getData());
			lastUpdatedTimestamp = NANOSECONDS.toMillis(System.nanoTime());
			initialised = true;
			
			address = s.getAddress();
			udpPort = s.getUdpPort();
			tcpPort = s.getTcpPort();
			
			listeners.stream().forEach((l) -> l.clusterEvent(ci, ADDED, id, data.keySet(), emptySet(), emptySet()));
			return FORWARD;
		}
	}
	
	public synchronized Snapshot toSnapshot() {
		return new Snapshot(id, new InetSocketAddress(address, udpPort), tcpPort, stateSequenceNumber, 
				messageTimeStamp, PAYLOAD_UPDATE, data, 1);
	}

	public synchronized Snapshot toSnapshot(int hops) {
		return new Snapshot(id, new InetSocketAddress(address, udpPort), tcpPort, stateSequenceNumber, 
				messageTimeStamp, PAYLOAD_UPDATE, data, hops);
	}

	public synchronized Snapshot toSnapshot(SnapshotType type) {
		return toSnapshot(type, 1);
	}
	
	public synchronized Snapshot toSnapshot(SnapshotType type, int hops) {
		switch(type) {
			case PAYLOAD_UPDATE: return toSnapshot(hops);
			case HEARTBEAT: return new Snapshot(id, new InetSocketAddress(address, udpPort), tcpPort, 
					stateSequenceNumber, messageTimeStamp, HEARTBEAT, null, hops);
			case HEADER: return new Snapshot(id, null, -1, 
					stateSequenceNumber, messageTimeStamp, HEADER, null, hops);
			default:
				throw new IllegalArgumentException("Unknown snapshot type");
		}
	}

	public synchronized void markUnreachable() {
		unreachableCount++;
		//TODO configure
		if(unreachableCount > 5) {
			close();
		}
	}

	public synchronized boolean shouldResync(long now, int threshold) {
		return !closed && (unreachableCount > 0 || (now - lastUpdatedTimestamp) > threshold);
	}

	public synchronized void close() {
		closed = true;
		lastUpdatedTimestamp = NANOSECONDS.toMillis(System.nanoTime());
		if(initialised) {
			listeners.stream().forEach((l) -> l.clusterEvent(ci, REMOVED, id, emptySet(), 
				data == null ? emptySet() : data.keySet(), emptySet()));
		}
	}
	
	public synchronized boolean isOpen() {
		return !closed && initialised;
	}

	public synchronized boolean evictable(int threshold) {
		return closed && (NANOSECONDS.toMillis(System.nanoTime()) - lastUpdatedTimestamp) > threshold;
	}
}
