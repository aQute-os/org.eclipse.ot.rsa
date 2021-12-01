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

import static com.paremus.gossip.cluster.impl.Update.CONSUME;
import static com.paremus.gossip.cluster.impl.Update.FORWARD;
import static com.paremus.gossip.cluster.impl.Update.FORWARD_LOCAL;
import static com.paremus.gossip.cluster.impl.Update.RESYNC;
import static com.paremus.gossip.v1.messages.MessageType.DISCONNECTION;
import static com.paremus.gossip.v1.messages.MessageType.FIRST_CONTACT_REQUEST;
import static com.paremus.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static java.net.InetAddress.getByAddress;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.BundleContext;
import org.osgi.util.promise.Promise;

import com.paremus.gossip.ClusterManager;
import com.paremus.gossip.GossipComms;
import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.impl.MemberInfo;
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

import aQute.bnd.annotation.metatype.Configurable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class GossipImplTest {

	static final byte[] INCOMING_ADDRESS = {4, 3, 2, 1};
	
	static final String CLUSTER = "cluster";
	static final String ANOTHER_CLUSTER = "another-cluster";
	
	static final String FOO = "foo";
	static final String BAR = "bar";
	static final String BAZ = "baz";
	static final byte[] PAYLOAD = {1, 2, 3, 4};
	static final byte[] PAYLOAD_2 = {5, 6, 7, 8};
	
	static final int UDP = 1234;
	static final int TCP = 1235;

	static final int UDP_2 = 2345;
	static final int TCP_2 = 2346;
	
	static final UUID LOCAL_ID = new UUID(1, 2);
	static final UUID ID = new UUID(1234, 5678);
	static final UUID ID_2 = new UUID(2345, 6789);
	static final UUID ID_3 = new UUID(3456, 7890);
	
	@Mock
	ClusterManager mgr;

	@Mock
	GossipComms comms;

	InetSocketAddress sockA = new InetSocketAddress(9876);

	InetSocketAddress sockB = new InetSocketAddress(8765);

	@Mock
	MemberInfo info;

	@SuppressWarnings("rawtypes")
	@Mock
	Promise promise;

	@Mock
	BundleContext context;
	
	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception {
		Mockito.when(mgr.getClusterName()).thenReturn(CLUSTER);
		Mockito.when(mgr.getLocalUUID()).thenReturn(LOCAL_ID);
		Mockito.when(mgr.getSnapshot(Mockito.any(SnapshotType.class), Mockito.anyInt())).thenAnswer((i) ->
			new Snapshot(ID_2, TCP_2, (short) 0, (SnapshotType) i.getArguments()[0], singletonMap(BAR, PAYLOAD_2), 1));
		
		promise = Mockito.mock(Promise.class, Mockito.RETURNS_MOCKS);
		Mockito.when(comms.replicate(Mockito.any(MemberInfo.class), Mockito.anyCollectionOf(Snapshot.class))).thenReturn(promise);
	}

	private GossipImpl getGossipImpl() {
		Config config = Configurable.createConfigurable(Config.class, Collections.singletonMap("cluster.name", CLUSTER));
		return new GossipImpl(context, mgr, comms, config, Arrays.asList(sockA, sockB));
	}

	@Test
	public void testHandleFirstContactMessage() throws Exception {
		GossipImpl gossip = getGossipImpl();
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		gossip.handleMessage(incoming, toDataInput(new FirstContactRequest(CLUSTER, 
						new Snapshot(ID, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD), 1))));
		gossip.destroy();
		
		ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
		
		verify(comms).publish(captor.capture(), 
				eq(Collections.singleton(incoming)));
		
		byte[] bytes = captor.getValue();
		
		assertEquals(1, 0xFF & bytes[0]);
		assertEquals(MessageType.FIRST_CONTACT_RESPONSE.ordinal(), 0xFF & bytes[1]);
		FirstContactResponse fcr = new FirstContactResponse(new DataInputStream(
				new ByteArrayInputStream(bytes, 2, bytes.length - 2)));
		
		assertEquals(CLUSTER, fcr.getClusterName());
		assertEquals(incoming, fcr.getFirstContactInfo().getUdpAddress());
	}
	
	@Test
	public void testHandleFirstContactResponse() throws Exception {
		
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		Mockito.when(mgr.getMemberInfo(Mockito.eq(ID_2))).thenReturn(info);
		
		GossipImpl gossip = getGossipImpl();
		Snapshot original = new Snapshot(new Snapshot(ID, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD), 1), 
				new InetSocketAddress(InetAddress.getLoopbackAddress(), UDP));
		gossip.handleMessage(incoming, toDataInput(new FirstContactResponse(CLUSTER, 
				mgr.getSnapshot(PAYLOAD_UPDATE, 1), original)));
		
		gossip.destroy();
		
		verify(comms).replicate(Mockito.same(info), Mockito.anyCollectionOf(Snapshot.class));
	}

	@Test
	public void testHandleForwardableHeartbeat() throws Exception {
		
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		Mockito.when(mgr.mergeSnapshot(Mockito.any(Snapshot.class))).thenReturn(FORWARD);
		Mockito.when(mgr.selectRandomPartners(Mockito.anyInt())).thenReturn(singletonList(info));
		Mockito.when(info.getUdpAddress()).thenReturn(new InetSocketAddress(UDP));
		
		Semaphore s = new Semaphore(0);
		Mockito.doAnswer((i) -> { s.release(); return null; }).when(comms)
			.publish(Mockito.any(byte[].class), Mockito.any());
		
		GossipImpl gossip = getGossipImpl();
		
		gossip.handleMessage(incoming, toDataInput(new ForwardableGossipMessage(CLUSTER, 
				new Snapshot(ID, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD), 3), 
				singletonList(new Snapshot(ID_3, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(BAR, PAYLOAD_2), 1)))));
		
		assertTrue(s.tryAcquire(2, 1000, TimeUnit.MILLISECONDS));
		
		gossip.destroy();
		
		ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
		
		verify(comms, Mockito.atLeastOnce()).publish(captor.capture(), Mockito.anyCollectionOf(SocketAddress.class));
		
		for(byte[] bytes : captor.getAllValues()) {
			assertEquals(1, 0xFF & bytes[0]);
			
			if(FIRST_CONTACT_REQUEST.ordinal() == (0xFF & bytes[1]) ||
					DISCONNECTION.ordinal() == (0xFF & bytes[1])) continue;
			
			assertEquals(MessageType.FORWARDABLE.ordinal(), 0xFF & bytes[1]);
			ForwardableGossipMessage fgm = new ForwardableGossipMessage(new DataInputStream(
					new ByteArrayInputStream(bytes, 2, bytes.length - 2)));
			
			assertEquals(CLUSTER, fgm.getClusterName());
			assertEquals(ID_2, fgm.getUpdate(incoming).getId());
			// This if block catches any gossip messages sent while we were waiting, and after the first peer arrived, as well as the forward
			List<Snapshot> allSnapshots = fgm.getAllSnapshots(incoming);
			if(allSnapshots.size() > 1) {
				assertEquals(2, allSnapshots.size());
				assertEquals(ID, allSnapshots.get(0).getId());
				assertEquals(ID_2, allSnapshots.get(1).getId());
			} else {
				assertEquals(ID_2, allSnapshots.get(0).getId());
			}
		}
	}

	@Test
	public void testHandleForwardableHeartbeatForwardLocal() throws Exception {
		
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		Mockito.when(mgr.mergeSnapshot(Mockito.any(Snapshot.class))).thenReturn(FORWARD_LOCAL);
		Mockito.when(mgr.selectRandomPartners(Mockito.anyInt())).thenReturn(singletonList(info));
		
		Snapshot a = new Snapshot(ID, new InetSocketAddress(InetAddress.getLocalHost(), 12), 
				TCP, (short) 1, 0xB00B00, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD_2), 2);
		MemberInfo m = Mockito.mock(MemberInfo.class);
		Mockito.when(mgr.getMemberInfo(ID)).thenReturn(m);
		Mockito.when(m.toSnapshot(PAYLOAD_UPDATE, 2)).thenReturn(a);
		
		Mockito.when(info.getUdpAddress()).thenReturn(new InetSocketAddress(UDP));
		
		Semaphore s = new Semaphore(0);
		Mockito.doAnswer((i) -> { s.release(); return null; }).when(comms)
			.publish(Mockito.any(byte[].class), Mockito.any());
		
		GossipImpl gossip = getGossipImpl();
		
		gossip.handleMessage(incoming, toDataInput(new ForwardableGossipMessage(CLUSTER, 
				new Snapshot(ID, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD), 3), 
				singletonList(new Snapshot(ID_3, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(BAR, PAYLOAD_2), 1)))));
		
		assertTrue(s.tryAcquire(2, 1000, TimeUnit.MILLISECONDS));
		
		gossip.destroy();
		
		ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
		
		verify(comms, Mockito.atLeastOnce()).publish(captor.capture(), Mockito.anyCollectionOf(SocketAddress.class));
		
		for(byte[] bytes : captor.getAllValues()) {
			assertEquals(1, 0xFF & bytes[0]);
			
			if(FIRST_CONTACT_REQUEST.ordinal() == (0xFF & bytes[1]) ||
					DISCONNECTION.ordinal() == (0xFF & bytes[1])) continue;
			
			assertEquals(MessageType.FORWARDABLE.ordinal(), 0xFF & bytes[1]);
			ForwardableGossipMessage fgm = new ForwardableGossipMessage(new DataInputStream(
					new ByteArrayInputStream(bytes, 2, bytes.length - 2)));
			
			assertEquals(CLUSTER, fgm.getClusterName());
			Snapshot update = fgm.getUpdate(incoming);
			assertEquals(ID_2, update.getId());
			// This if block catches any gossip messages sent while we were waiting, and after the first peer arrived, as well as the forward
			List<Snapshot> allSnapshots = fgm.getAllSnapshots(incoming);
			if(allSnapshots.size() > 1) {
				assertEquals(2, allSnapshots.size());
				assertEquals(ID, allSnapshots.get(0).getId());
				assertEquals(a.getSnapshotTimestamp(), allSnapshots.get(0).getSnapshotTimestamp());
				assertEquals(ID_2, allSnapshots.get(1).getId());
			} else {
				assertEquals(ID_2, allSnapshots.get(0).getId());
			}
		}
	}
	
	@Test
	public void testHandleDisconnection() throws Exception {
		
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		GossipImpl gossip = getGossipImpl();
		
		gossip.handleMessage(incoming, toDataInput(new DisconnectionMessage(CLUSTER, 
				new Snapshot(ID, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD), 1))));
		
		gossip.destroy();
		
		verify(mgr).leavingCluster(Mockito.argThat(hasSnapshotWith(ID)));
	}

	@Test
	public void testHandlePingRequest() throws Exception {
		
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		GossipImpl gossip = getGossipImpl();
		
		gossip.handleMessage(incoming, toDataInput(new PingRequest(CLUSTER, 
				new Snapshot(ID, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD), 1))));
		
		gossip.destroy();
		
		ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
		
		Mockito.verify(comms).publish(captor.capture(), Mockito.eq(Collections.singleton(incoming)));
		
		assertEquals(1, captor.getValue()[0]);
		assertEquals(MessageType.PING_RESPONSE.ordinal(), captor.getValue()[1]);
	}

	@Test
	public void testHandlePingResponse() throws Exception {
		
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		GossipImpl gossip = getGossipImpl();
		
		gossip.handleMessage(incoming, toDataInput(new PingResponse(CLUSTER, 
				new Snapshot(ID, TCP, (short) 0, PAYLOAD_UPDATE, singletonMap(FOO, PAYLOAD), 1))));
		
		gossip.destroy();
		
		verify(mgr).mergeSnapshot(Mockito.argThat(hasSnapshotWith(ID)));
	}

	private static ArgumentMatcher<Snapshot> hasSnapshotWith(UUID id) {
		return new ArgumentMatcher<Snapshot>() {
			
			@Override
			public boolean matches(Snapshot item) {
				if(item instanceof Snapshot) {
					return id.equals(((Snapshot) item).getId());
				}
				return false;
			}
			
		};
	}
	
	private DataInput toDataInput(AbstractGossipMessage message) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutput dataOutput = new DataOutputStream(baos);
		
		dataOutput.writeByte(1);
		dataOutput.writeByte(message.getType().ordinal());
		message.writeOut(dataOutput);
		
		return new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
	}
	
	@Test
	public void testMergeForwardLocal() {
		
		Snapshot a = Mockito.mock(Snapshot.class);
		UUID id = UUID.randomUUID();
		Mockito.when(a.getId()).thenReturn(id);
		
		MemberInfo m = Mockito.mock(MemberInfo.class);
		Mockito.when(mgr.getMemberInfo(id)).thenReturn(m);
		Snapshot aPrime = Mockito.mock(Snapshot.class);
		Mockito.when(m.toSnapshot()).thenReturn(aPrime);
		Mockito.when(mgr.mergeSnapshot(a)).thenReturn(FORWARD_LOCAL);
		
		
		GossipImpl gossip = getGossipImpl();
		
		assertEquals(aPrime, gossip.merge(a));
		Mockito.verify(comms).preventIndirectDiscovery();
		Mockito.verifyNoMoreInteractions(comms);
		
		gossip.destroy();
	}

	@Test
	public void testMergeForward() {
		
		Snapshot a = Mockito.mock(Snapshot.class);
		Mockito.when(mgr.mergeSnapshot(a)).thenReturn(FORWARD);
		
		
		GossipImpl gossip = getGossipImpl();
		
		assertSame(a, gossip.merge(a));
		Mockito.verify(comms).preventIndirectDiscovery();
		Mockito.verifyNoMoreInteractions(comms);
		
		gossip.destroy();
	}

	@Test
	public void testMergeResync() {
		
		Snapshot a = Mockito.mock(Snapshot.class);
		InetSocketAddress sa = new InetSocketAddress(0);
		Mockito.when(a.getUdpAddress()).thenReturn(sa);
		Mockito.when(mgr.mergeSnapshot(a)).thenReturn(RESYNC);
		
		GossipImpl gossip = getGossipImpl();
		
		assertSame(a, gossip.merge(a));
		
		Mockito.verify(comms).publish(Mockito.any(byte[].class), Mockito.eq(Collections.singleton(sa)));
		
		gossip.destroy();
	}

	@Test
	public void testConsume() {
		
		Snapshot a = Mockito.mock(Snapshot.class);
		Mockito.when(mgr.mergeSnapshot(a)).thenReturn(CONSUME);
		
		GossipImpl gossip = getGossipImpl();
		
		assertNull(gossip.merge(a));
		
		gossip.destroy();
	}

	@Test
	public void testDestroy() throws UnknownHostException {
		InetSocketAddress incoming = new InetSocketAddress(getByAddress(INCOMING_ADDRESS), UDP);
		Snapshot a = Mockito.mock(Snapshot.class);
		Mockito.when(a.getUdpAddress()).thenReturn(incoming);
		Mockito.when(mgr.getMemberSnapshots(Mockito.any(SnapshotType.class))).thenReturn(Arrays.asList(a));
		
		GossipImpl gossip = getGossipImpl();
		gossip.destroy();
		
		ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
		
		verify(comms, Mockito.atLeastOnce()).publish(captor.capture(), 
				eq(Collections.singletonList(incoming)));
		
		byte[] bytes = captor.getValue();
		
		assertEquals(1, 0xFF & bytes[0]);
		assertEquals(MessageType.DISCONNECTION.ordinal(), 0xFF & bytes[1]);
		DisconnectionMessage dm = new DisconnectionMessage(new DataInputStream(
				new ByteArrayInputStream(bytes, 2, bytes.length - 2)));
		
		assertEquals(CLUSTER, dm.getClusterName());
		assertEquals(ID_2, dm.getUpdate(incoming).getId());
		
	}

	@Test
	public void testDarkNodes() throws UnknownHostException {
		MemberInfo m = Mockito.mock(MemberInfo.class);
		Mockito.when(m.getUdpAddress()).thenReturn(new InetSocketAddress(UDP));
		GossipImpl gossip = getGossipImpl();
		gossip.darkNodes(Arrays.asList(m));
		gossip.destroy();
		
		ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
		
		Mockito.verify(comms).publish(captor.capture(), Mockito.eq(Collections.singleton(new InetSocketAddress(UDP))));
		
		assertEquals(1, captor.getValue()[0]);
		assertEquals(MessageType.PING_REQUEST.ordinal(), captor.getValue()[1]);
	}

	@Test
	public void testSendKeyUpdate() throws UnknownHostException {
		GossipImpl gossip = getGossipImpl();
		Stream<InetSocketAddress> stream = Stream.of(sockA, sockB);
		gossip.sendKeyUpdate(stream);
		gossip.destroy();
		
		Mockito.verify(comms).sendKeyUpdate(stream);
	}


}
