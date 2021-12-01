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

import static com.paremus.dosgi.discovery.gossip.impl.GossipDiscovery.PAREMUS_DISCOVERY_DATA;
import static com.paremus.gossip.cluster.listener.Action.ADDED;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.osgi.framework.Constants.OBJECTCLASS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.BundleContext;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;

import com.paremus.dosgi.discovery.gossip.comms.EndpointSerializer;
import com.paremus.dosgi.discovery.gossip.comms.MessageType;
import com.paremus.dosgi.discovery.gossip.local.LocalDiscoveryListener;
import com.paremus.dosgi.discovery.gossip.local.RemoteDiscoveryEndpoint;
import com.paremus.dosgi.discovery.gossip.scope.EndpointFilter;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.listener.Action;
import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncodingSchemeFactory;
import com.paremus.net.info.ClusterNetworkInformation;

import aQute.bnd.annotation.metatype.Configurable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class GossipDiscoveryTest {

	private static final UUID LOCAL_UUID = new UUID(123, 456);
	private static final UUID REMOTE_UUID_1 = new UUID(987, 654);
	private static final UUID REMOTE_UUID_2 = new UUID(876, 543);
	private static final String ENDPOINT_1 = new UUID(234, 567).toString();

	public static final String CLUSTER_A = "clusterA";
	public static final String CLUSTER_B = "clusterB";
	
	@Mock
	BundleContext context;
	
	@Mock
	ClusterInformation clusterInfo;
	
	@Mock
	EncodingSchemeFactory esf;

	@Mock
	EncodingScheme es;

	@Mock
	ClusterNetworkInformation fni;

	@Mock
	LocalDiscoveryListener ldl;

	Config config;
	
	private Semaphore sem = new Semaphore(0);
	
	private GossipDiscovery gd;
	
	@BeforeEach
	public void setUp() throws Exception {
		config = Configurable.createConfigurable(Config.class, Collections.singletonMap("root.cluster", CLUSTER_A));
		
		Mockito.when(context.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(LOCAL_UUID.toString());
		Mockito.when(clusterInfo.getAddressFor(LOCAL_UUID)).thenReturn(InetAddress.getLoopbackAddress());
		Mockito.when(clusterInfo.getAddressFor(REMOTE_UUID_1)).thenReturn(InetAddress.getLoopbackAddress());
		Mockito.when(clusterInfo.getAddressFor(REMOTE_UUID_2)).thenReturn(InetAddress.getLoopbackAddress());
		
		Mockito.when(esf.createEncodingScheme(Mockito.any())).thenReturn(es);
		
		Mockito.when(es.encode(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
			.then(i -> {
					byte[] header = (byte[]) i.getArguments()[0];
					byte[] body = (byte[]) i.getArguments()[1];
					int length = (int) i.getArguments()[3];
					byte[] toReturn = new byte[header.length + length];
					System.arraycopy(header, 0, toReturn, 0, header.length);
					System.arraycopy(body, 0, toReturn, header.length, length);
					return toReturn;
				});
		
		Mockito.doAnswer(i -> {
			sem.release();
			return null;
		}).when(clusterInfo).updateAttribute(Mockito.eq(PAREMUS_DISCOVERY_DATA), Mockito.any(byte[].class));
	}
	
	@AfterEach
	public void tearDown() throws Exception {
		gd.destroy();
	}

	@Test
	public void testPortRegistration() {
		gd = new GossipDiscovery(context, LOCAL_UUID, ldl, esf, config);
		
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		
		gd.addClusterInformation(clusterInfo);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.addNetworkInformation(fni, CLUSTER_A, clusterInfo);
		
		Mockito.verify(clusterInfo).updateAttribute(Mockito.eq(PAREMUS_DISCOVERY_DATA), Mockito.any(byte[].class));
	}
	
	private byte[] getPortPlusFilter(int port, String cluster) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (DataOutputStream dos = new DataOutputStream(baos)) {
			dos.writeShort(port);
			new EndpointFilter(cluster).writeOut(dos);		
		}
		return baos.toByteArray();
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void testRegisterEndpoints() throws Exception{
		try (DatagramSocket remote = new DatagramSocket(0, InetAddress.getLoopbackAddress());
			DatagramSocket remote2 = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
			remote.setSoTimeout(2000);
			remote2.setSoTimeout(2000);
			
			Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_1, PAREMUS_DISCOVERY_DATA))
				.thenReturn(getPortPlusFilter(remote.getLocalPort(), CLUSTER_A));
			Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_2, PAREMUS_DISCOVERY_DATA))
				.thenReturn(getPortPlusFilter(remote2.getLocalPort(), CLUSTER_A));
			
			Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
			
			gd = new GossipDiscovery(context, LOCAL_UUID, ldl, esf, config);
			
			gd.addClusterInformation(clusterInfo);
			gd.addNetworkInformation(fni, CLUSTER_A, clusterInfo);
			gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
			gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
			
			assertTrue(sem.tryAcquire(1000, TimeUnit.MILLISECONDS));
			
			@SuppressWarnings("rawtypes")
			ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
			Mockito.verify(ldl).updateRemote(Mockito.eq(CLUSTER_A), Mockito.eq(REMOTE_UUID_1), 
					Mockito.eq(remote.getLocalPort()), Mockito.any(EndpointFilter.class), captor.capture());
			
			EndpointDescription ed = getTestEndpointDescription(ENDPOINT_1);
			((RemoteDiscoveryEndpoint)captor.getValue().get()).publishEndpoint(1, ed, false);
			
			DatagramPacket dp = new DatagramPacket(new byte[65535], 65535);
			remote.receive(dp);
			
			checkPlainEndpointAnnounce(ed, dp, 1);
			
			gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_2, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
			
			Mockito.verify(ldl).updateRemote(Mockito.eq(CLUSTER_A), Mockito.eq(REMOTE_UUID_2), 
					Mockito.eq(remote2.getLocalPort()), Mockito.any(EndpointFilter.class), captor.capture());
			
			((RemoteDiscoveryEndpoint)captor.getValue().get()).publishEndpoint(1, ed, false);
			
			remote2.receive(dp);
			checkPlainEndpointAnnounce(ed, dp, 1);
		}
	}

	@Test
	public void testRemoveMember() throws Exception{
		Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_1, PAREMUS_DISCOVERY_DATA))
			.thenReturn(getPortPlusFilter(1234, CLUSTER_A));
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		
		gd = new GossipDiscovery(context, LOCAL_UUID, ldl, esf, config);
		
		gd.addClusterInformation(clusterInfo);
		gd.addNetworkInformation(fni, CLUSTER_A, clusterInfo);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		
		assertTrue(sem.tryAcquire(1000, TimeUnit.MILLISECONDS));
		
		Mockito.verify(ldl).updateRemote(Mockito.eq(CLUSTER_A), Mockito.eq(REMOTE_UUID_1), 
				Mockito.eq(1234), Mockito.any(EndpointFilter.class), Mockito.any());
		
		gd.clusterEvent(clusterInfo, Action.REMOVED, REMOTE_UUID_1, emptySet(), emptySet(), emptySet());
		
		Mockito.verify(ldl).removeRemote(CLUSTER_A, REMOTE_UUID_1);
	}
	
	@Test
	public void testRevokeMemberVisibleFromTwoClusters() throws Exception{
		Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_1, PAREMUS_DISCOVERY_DATA))
			.thenReturn(getPortPlusFilter(1234, CLUSTER_A));
		
		gd = new GossipDiscovery(context, LOCAL_UUID, ldl, esf, config);
		
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		gd.addClusterInformation(clusterInfo);
		
		gd.addNetworkInformation(fni, CLUSTER_A, clusterInfo);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_B);
		gd.addClusterInformation(clusterInfo);
		
		gd.addNetworkInformation(fni, CLUSTER_B, clusterInfo);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		
		assertTrue(sem.tryAcquire(2, 1000, TimeUnit.MILLISECONDS));
		
		Mockito.verify(ldl).updateRemote(Mockito.eq(CLUSTER_A), Mockito.eq(REMOTE_UUID_1), 
				Mockito.eq(1234), Mockito.any(EndpointFilter.class), Mockito.any());
		
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		gd.clusterEvent(clusterInfo, Action.REMOVED, REMOTE_UUID_1, emptySet(), emptySet(), emptySet());
		
		Mockito.verify(ldl).removeRemote(CLUSTER_A, REMOTE_UUID_1);
		Mockito.verify(ldl, Mockito.never()).removeRemote(CLUSTER_B, REMOTE_UUID_1);
	}

	@Test
	public void testRemoveCluster() throws Exception{
		Map<String, byte[]> attrs = new HashMap<String, byte[]>();
		attrs.put(PAREMUS_DISCOVERY_DATA, new byte[] {(byte) (1234), (byte) 1234});
		Mockito.when(clusterInfo.getMemberAttributes(REMOTE_UUID_1)).thenReturn(attrs);
		attrs = new HashMap<String, byte[]>();
		attrs.put(PAREMUS_DISCOVERY_DATA, new byte[] {(byte) 5678, (byte) 5678});
		Mockito.when(clusterInfo.getMemberAttributes(REMOTE_UUID_2)).thenReturn(attrs);
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		
		gd = new GossipDiscovery(context, LOCAL_UUID, ldl, esf, config);
		
		gd.addClusterInformation(clusterInfo);
		gd.addNetworkInformation(fni, CLUSTER_A, clusterInfo);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_2, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		
		gd.removeClusterInformation(clusterInfo);
		
		Mockito.verify(ldl).removeRemotesForCluster(CLUSTER_A);
	}

	@Test
	public void testReplaceCluster() throws Exception{
		Map<String, byte[]> attrs = new HashMap<String, byte[]>();
		attrs.put(PAREMUS_DISCOVERY_DATA, new byte[] {(byte) (1234), (byte) 1234});
		Mockito.when(clusterInfo.getMemberAttributes(REMOTE_UUID_1)).thenReturn(attrs);
		attrs = new HashMap<String, byte[]>();
		attrs.put(PAREMUS_DISCOVERY_DATA, new byte[] {(byte) 5678, (byte) 5678});
		Mockito.when(clusterInfo.getMemberAttributes(REMOTE_UUID_2)).thenReturn(attrs);
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		
		gd = new GossipDiscovery(context, LOCAL_UUID, ldl, esf, config);
		
		gd.addClusterInformation(clusterInfo);
		gd.addNetworkInformation(fni, CLUSTER_A, clusterInfo);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_2, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		
		gd.addClusterInformation(clusterInfo);
		
		Mockito.verify(ldl).removeRemotesForCluster(CLUSTER_A);
	}

	private EndpointDescription getTestEndpointDescription(String endpointId) {
		Map<String, Object> m = new LinkedHashMap<String, Object>();

        // required
        m.put(OBJECTCLASS, new String[]{"com.acme.HelloService", "some.other.Service"});
        m.put(RemoteConstants.ENDPOINT_FRAMEWORK_UUID, LOCAL_UUID.toString());
        m.put(RemoteConstants.ENDPOINT_ID, endpointId);
        m.put(RemoteConstants.ENDPOINT_SERVICE_ID, Long.valueOf(42));
        m.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");

        return new EndpointDescription(m);
	}
	
	private void checkPlainEndpointAnnounce(EndpointDescription ed, DatagramPacket dp, int state)
			throws IOException {
		ByteArrayInputStream bais = new ByteArrayInputStream(dp.getData(), dp.getOffset(), dp.getLength());
		
		//Version 1, plain text
		assertEquals(1, bais.read());
		assertEquals(2, bais.read());
		assertEquals(MessageType.ANNOUNCEMENT.ordinal(), bais.read());
		
		DataInput di = new DataInputStream(bais);
		EndpointDescription received = EndpointSerializer.deserializeEndpoint(di);
		assertEquals(ed.getId(), received.getId());
		assertEquals(state, di.readInt());
	}
}
