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

import static aQute.bnd.annotation.metatype.Configurable.createConfigurable;
import static com.paremus.gossip.cluster.listener.Action.ADDED;
import static com.paremus.gossip.cluster.listener.Action.REMOVED;
import static com.paremus.gossip.cluster.listener.Action.UPDATED;
import static com.paremus.gossip.v1.messages.SnapshotType.HEARTBEAT;
import static com.paremus.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;

import com.paremus.gossip.InternalClusterListener;
import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.listener.Action;
import com.paremus.gossip.cluster.listener.ClusterListener;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.gossip.v1.messages.SnapshotType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ClusterManagerImplTest {

	static final String CLUSTER = "myCluster";
	static final String FOO = "foo";
	static final String BAR = "bar";
	static final String BAZ = "baz";
	static final byte[] PAYLOAD = {1, 2, 3, 4};
	static final byte[] PAYLOAD_2 = {5, 6, 7, 8};
	
	static final int UDP = 1234;
	static final int TCP = 1235;
	
	static final UUID ID = new UUID(1234, 5678);

	
	static final byte[] INCOMING_ADDRESS = {4, 3, 2, 1};
	static final int INCOMING_UDP = 2345;
	static final int INCOMING_TCP = 2346;
	
	static final UUID INCOMING_ID = new UUID(8765, 4321);
	
	@Mock
	BundleContext context;

	@Mock
	InternalClusterListener listener;
	
	ClusterManagerImpl impl;
	
	
	private void assertSnapshot(Snapshot s, int time, int sequence, int tcp, 
			UUID id, byte[] payload, SnapshotType type) {
		assertSnapshot(s, time, sequence, null, -1, tcp, id, payload, type);
	}

	private void assertSnapshot(Snapshot s, int time, int sequence, InetAddress address, int udp, int tcp, 
			UUID id, byte[] payload, SnapshotType type) {
		// Due to the 24 bit nature of the timestamp 1ms is actually 256 higher
		assertEquals(time, s.getSnapshotTimestamp(), 257d, ()->"No data yet");
		assertEquals(sequence, s.getStateSequenceNumber(), ()->"Snapshot should be the current time");
		if(address == null) {
			assertNull(s.getAddress(), ()->"The address we send is always null");
		} else {
			assertEquals(address, s.getAddress());
		}
		assertEquals(udp, s.getUdpPort(), ()->"Wrong UDP port");
		assertEquals(tcp, s.getTcpPort(), ()->"Wrong TCP port");
		assertEquals(id, s.getId(), ()->"Wrong id");
		if(type == HEARTBEAT) {
			assertTrue(s.getData().isEmpty(), ()->"No payload for a heartbeat");
		} else if (payload == null) {
			assertTrue(s.getData().isEmpty(), ()->"No payload expected");
		} else {
			assertTrue(Arrays.equals(payload, s.getData().get(FOO)), ()->"Wrong payload");
		}
		assertEquals(type, s.getMessageType());
	}
	
	@BeforeEach
	public void setUp() {
		
		impl = new ClusterManagerImpl(context, ID, 
				createConfigurable(Config.class, singletonMap("cluster.name", CLUSTER)), UDP, TCP, null);
	}
	
	@Test
	public void testHeartbeatSnapshot() {
		int now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		assertSnapshot(impl.getSnapshot(HEARTBEAT, 1), now, 0, TCP, ID, null, HEARTBEAT);
		
		impl.updateAttribute(FOO, PAYLOAD);
		
		now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		assertSnapshot(impl.getSnapshot(HEARTBEAT, 1), now, 1, TCP, ID, null, HEARTBEAT);
	}

	@Test
	public void testUpdateSnapshot() {
		int now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		assertSnapshot(impl.getSnapshot(PAYLOAD_UPDATE, 1), now, 0, TCP, ID, null, PAYLOAD_UPDATE);
		
		impl.updateAttribute(FOO, PAYLOAD);
		
		now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		assertSnapshot(impl.getSnapshot(PAYLOAD_UPDATE, 1), now, 1, TCP, ID, PAYLOAD, PAYLOAD_UPDATE);
	}
	
	@Test
	public void testPayloadUpdateTriggersListener() {
		
		impl.setInternalListener(listener);
		int now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;

		impl.updateAttribute(FOO, PAYLOAD);
		
		ArgumentCaptor<Snapshot> captor = ArgumentCaptor.forClass(Snapshot.class);
		
		Mockito.verify(listener).localUpdate(captor.capture());
		
		assertSnapshot(captor.getValue(), now, 1, TCP, ID, PAYLOAD, PAYLOAD_UPDATE);
	}

	@Test
	public void testDestroyTriggersListener() {
		impl.setInternalListener(listener);
		impl.destroy();
		
		Mockito.verify(listener).destroy();
	}
	
	@Test
	public void testBasicAddAndRemove() throws UnknownHostException {
		
		impl.mergeSnapshot(new Snapshot(impl.getSnapshot(PAYLOAD_UPDATE, 1), 
				new InetSocketAddress(InetAddress.getLocalHost(), 123)));
		
		assertTrue(impl.getKnownMembers().contains(ID), ()->"Should know about ourselves");
		assertFalse(impl.getKnownMembers().contains(INCOMING_ID), ()->"Should not know about the new guy yet");
		
		int now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		impl.mergeSnapshot(new Snapshot(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, PAYLOAD_UPDATE, 
				Collections.singletonMap(FOO, PAYLOAD), 1), 
				new InetSocketAddress(InetAddress.getByAddress(INCOMING_ADDRESS), INCOMING_UDP)));
		
		assertTrue(impl.getKnownMembers().contains(INCOMING_ID), ()->"Should now know about the new guy");
		assertEquals(InetAddress.getByAddress(INCOMING_ADDRESS), 
				impl.getAddressFor(INCOMING_ID), ()->"Should have the right address");
		
		assertSnapshot(impl.getMemberInfo(INCOMING_ID).toSnapshot(), now, 0, InetAddress.getByAddress(INCOMING_ADDRESS), 
				INCOMING_UDP, INCOMING_TCP, INCOMING_ID, PAYLOAD, PAYLOAD_UPDATE);
		
		impl.leavingCluster(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, HEARTBEAT, null, 1));
		
		assertFalse(impl.getKnownMembers().contains(INCOMING_ID), ()->"New guy should be gone now");
		assertNull(impl.getAddressFor(INCOMING_ID), ()->"Should have no address");
	}
	
	@Test
	public void testMarkUnreachable() {
		MemberInfo info = Mockito.mock(MemberInfo.class);
		impl.markUnreachable(info);
		
		Mockito.verify(info).markUnreachable();
	}
	
	@Test
	public void testGetMemberAttributes() throws UnknownHostException {
		Map<String, byte[]> attrs = new HashMap<>();
		attrs.put(FOO, PAYLOAD);
		attrs.put(BAR, PAYLOAD_2);
		
		impl.mergeSnapshot(new Snapshot(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, PAYLOAD_UPDATE, 
				attrs, 1), new InetSocketAddress(InetAddress.getByAddress(INCOMING_ADDRESS), INCOMING_UDP)));
		
		Function<Map<String, byte[]>, Map<String, String>> equalsSafe = 
				m -> m.entrySet().stream()
					.collect(toMap(Entry::getKey, e -> Arrays.toString(e.getValue())));
		
		assertEquals(equalsSafe.apply(attrs), equalsSafe.apply(impl.getMemberAttributes(INCOMING_ID)));
		assertEquals(Arrays.toString(PAYLOAD), Arrays.toString(impl.getMemberAttribute(INCOMING_ID, FOO)));
		assertEquals(Arrays.toString(PAYLOAD_2), Arrays.toString(impl.getMemberAttribute(INCOMING_ID, BAR)));
		assertNull(impl.getMemberAttribute(INCOMING_ID, BAZ));
	}

	@Test
	public void testGetMemberSnapshots() throws UnknownHostException {
		//Just ourselves
		int now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		impl.mergeSnapshot(new Snapshot(impl.getSnapshot(PAYLOAD_UPDATE, 0), new InetSocketAddress(getLoopbackAddress(), UDP)));
		
		Iterator<Snapshot> it = impl.getMemberSnapshots(PAYLOAD_UPDATE).iterator();
		assertSnapshot(it.next(), now, 0, getLoopbackAddress(), UDP, TCP, ID, null, PAYLOAD_UPDATE);
		assertFalse(it.hasNext());
		
		now = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		impl.updateAttribute(FOO, PAYLOAD);
		it = impl.getMemberSnapshots(PAYLOAD_UPDATE).iterator();
		
		assertSnapshot(it.next(), now, 1, getLoopbackAddress(), UDP, TCP, ID, PAYLOAD, PAYLOAD_UPDATE);
		assertFalse(it.hasNext());
		
		//Add a new member
		int now2 = (int) (0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8;
		impl.mergeSnapshot(new Snapshot(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, PAYLOAD_UPDATE, 
				Collections.singletonMap(FOO, PAYLOAD_2), 1), 
				new InetSocketAddress(InetAddress.getByAddress(INCOMING_ADDRESS), INCOMING_UDP)));
		
		it = impl.getMemberSnapshots(PAYLOAD_UPDATE).stream()
				.sorted((a,b) -> a.getId().compareTo(b.getId())).iterator();
		
		assertSnapshot(it.next(), now, 1, getLoopbackAddress(), UDP, TCP, ID, PAYLOAD, PAYLOAD_UPDATE);
		assertSnapshot(it.next(), now2, 0, InetAddress.getByAddress(INCOMING_ADDRESS), 
				INCOMING_UDP, INCOMING_TCP, INCOMING_ID, PAYLOAD_2, PAYLOAD_UPDATE);
		assertFalse(it.hasNext());
		
		
		impl.leavingCluster(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, HEARTBEAT, null, 1));
		
		it = impl.getMemberSnapshots(PAYLOAD_UPDATE).iterator();
		assertSnapshot(it.next(), now, 1, getLoopbackAddress(), UDP, TCP, ID, PAYLOAD, PAYLOAD_UPDATE);
		assertFalse(it.hasNext());
	}

	@Test
	public void testSelectRandomPartners() throws UnknownHostException {
		//Just ourselves
		impl.updateAttribute(FOO, PAYLOAD);
		
		Collection<MemberInfo> sample = impl.selectRandomPartners(2);
		
		assertTrue(sample.isEmpty());
		
		//Add a new member
		impl.mergeSnapshot(new Snapshot(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, PAYLOAD_UPDATE, 
				Collections.singletonMap(FOO, PAYLOAD_2), 1), 
				new InetSocketAddress(InetAddress.getByAddress(INCOMING_ADDRESS), INCOMING_UDP)));
		
		sample = impl.selectRandomPartners(2);
		
		assertEquals(1, sample.size());
		assertEquals(INCOMING_ID, sample.iterator().next().getId());
		
		impl.leavingCluster(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, HEARTBEAT, null, 1));
		
		sample = impl.selectRandomPartners(2);
		assertTrue(sample.isEmpty());
	}
	
	@Test
	public void testClusterListener() throws UnknownHostException, InterruptedException {
		
		impl.mergeSnapshot(new Snapshot(impl.getSnapshot(PAYLOAD_UPDATE, 0), new InetSocketAddress(getLoopbackAddress(), UDP)));
		
		Semaphore sem = new Semaphore(0);
		ClusterListener listener = Mockito.mock(ClusterListener.class);
		@SuppressWarnings("unchecked")
		ServiceReference<ClusterListener> ref = Mockito.mock(ServiceReference.class);
		Mockito.when(context.getService(ref)).thenReturn(listener);
		Mockito.doAnswer(new Answer<Void>() {
			@Override
			public Void answer(InvocationOnMock invocation) throws Throwable {
				sem.release();
				return null;
			}
		}).when(listener).clusterEvent(any(ClusterInformation.class), any(Action.class), 
				any(UUID.class), anySet(), anySet(), anySet());
		
		impl.listenerChange(ref, ServiceEvent.REGISTERED);
		assertTrue(sem.tryAcquire(1000, TimeUnit.MILLISECONDS));
		
		Mockito.verify(listener).clusterEvent(impl, ADDED, ID, emptySet(), emptySet(), emptySet());
		

		impl.updateAttribute(FOO, PAYLOAD);
		assertTrue(sem.tryAcquire(1000, TimeUnit.MILLISECONDS));
		
		Mockito.verify(listener).clusterEvent(impl, UPDATED, ID, singleton(FOO), emptySet(), emptySet());
		
		//Sleep to make sure the next snapshot isn't ignored
		Thread.sleep(20);
		
		impl.updateAttribute(FOO, PAYLOAD_2);
		
		assertTrue(sem.tryAcquire(1000, TimeUnit.MILLISECONDS));
		Mockito.verify(listener).clusterEvent(impl, UPDATED, ID, emptySet(), emptySet(), singleton(FOO));
		
		//Add a new member
		Map<String, byte[]> attrs = new HashMap<>();
		attrs.put(FOO, PAYLOAD);
		attrs.put(BAR, PAYLOAD_2);
		
		//Sleep to make sure the next snapshot isn't ignored
		Thread.sleep(20);
		impl.mergeSnapshot(new Snapshot(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, PAYLOAD_UPDATE, 
				attrs, 1), new InetSocketAddress(InetAddress.getByAddress(INCOMING_ADDRESS), INCOMING_UDP)));
		
		assertTrue(sem.tryAcquire(1000, TimeUnit.MILLISECONDS));
		Mockito.verify(listener).clusterEvent(impl, ADDED, INCOMING_ID, new HashSet<>(Arrays.asList(FOO, BAR)), 
				emptySet(), emptySet());

		
		//Update the member
		attrs.remove(FOO);
		attrs.put(BAR, PAYLOAD);
		attrs.put(BAZ, PAYLOAD_2);
		
		//Sleep to make sure the next snapshot isn't ignored
		Thread.sleep(20);
		impl.mergeSnapshot(new Snapshot(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 1, PAYLOAD_UPDATE, 
				attrs, 1), new InetSocketAddress(InetAddress.getByAddress(INCOMING_ADDRESS), INCOMING_UDP)));
		
		assertTrue(sem.tryAcquire(500000, TimeUnit.MILLISECONDS));
		Mockito.verify(listener).clusterEvent(impl, UPDATED, INCOMING_ID, singleton(BAZ), singleton(FOO), singleton(BAR));
		
		//Sleep to make sure the next snapshot isn't ignored
		Thread.sleep(20);
		impl.leavingCluster(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, HEARTBEAT, null, 1));
		
		assertTrue(sem.tryAcquire(1000, TimeUnit.MILLISECONDS));
		Mockito.verify(listener).clusterEvent(impl, REMOVED, INCOMING_ID, emptySet(), 
				new HashSet<>(Arrays.asList(BAR, BAZ)), emptySet());
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void testKeyUpdate() throws Exception {
		
		@SuppressWarnings("rawtypes")
		ArgumentCaptor<Stream> captor = ArgumentCaptor.forClass(Stream.class);
		impl.setInternalListener(listener);
		
		Mockito.verify(listener, never()).sendKeyUpdate(any());

		impl.notifyKeyChange();
		Mockito.verify(listener).sendKeyUpdate(captor.capture());
		assertTrue(((Stream<InetAddress>)captor.getValue()).collect(toList()).isEmpty(), ()->"Should be empty");
		
		
		impl.mergeSnapshot(new Snapshot(impl.getSnapshot(PAYLOAD_UPDATE, 1), 
				new InetSocketAddress(InetAddress.getLocalHost(), 123)));
		
		InetSocketAddress udpAddress = new InetSocketAddress(InetAddress.getByAddress(INCOMING_ADDRESS), INCOMING_UDP);
		impl.mergeSnapshot(new Snapshot(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, PAYLOAD_UPDATE, 
				Collections.singletonMap(FOO, PAYLOAD), 1), udpAddress));
		
		impl.notifyKeyChange();
		Mockito.verify(listener, times(2)).sendKeyUpdate(captor.capture());
		assertEquals(Collections.singletonList(udpAddress), ((Stream<InetAddress>)captor.getValue())
				.collect(toList()));
		
		
		impl.leavingCluster(new Snapshot(INCOMING_ID, INCOMING_TCP, (short) 0, HEARTBEAT, null, 1));
		
		impl.notifyKeyChange();
		Mockito.verify(listener, times(3)).sendKeyUpdate(captor.capture());
		assertTrue(((Stream<InetAddress>)captor.getValue()).collect(toList()).isEmpty(), ()->"Should be empty");
	}
}
