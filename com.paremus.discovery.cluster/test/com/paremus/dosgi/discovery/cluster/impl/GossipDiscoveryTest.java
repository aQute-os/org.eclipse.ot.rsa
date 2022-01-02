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

import static com.paremus.cluster.listener.Action.ADDED;
import static com.paremus.dosgi.discovery.cluster.impl.ClusterDiscoveryImpl.PAREMUS_DISCOVERY_DATA;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.util.converter.Converters.standardConverter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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

import com.paremus.cluster.ClusterInformation;
import com.paremus.cluster.listener.Action;
import com.paremus.dosgi.discovery.cluster.comms.EndpointSerializer;
import com.paremus.dosgi.discovery.cluster.comms.MessageType;
import com.paremus.dosgi.discovery.cluster.local.LocalDiscoveryListener;
import com.paremus.dosgi.discovery.cluster.local.RemoteDiscoveryEndpoint;
import com.paremus.dosgi.discovery.cluster.scope.EndpointFilter;
import com.paremus.net.info.ClusterNetworkInformation;
import com.paremus.netty.tls.ParemusNettyTLS;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

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
	ClusterNetworkInformation fni;

	@Mock
	LocalDiscoveryListener ldl;
	
	@Mock
	ParemusNettyTLS tls;

	Config config;
	
	private Semaphore sem = new Semaphore(0);
	
	private ClusterDiscoveryImpl gd;
	
	private EventExecutorGroup localWorker, remoteWorker;
	
	@BeforeEach
	public void setUp() throws Exception {
		config = standardConverter().convert(singletonMap("root.cluster", CLUSTER_A)).to(Config.class);
		
		Mockito.when(clusterInfo.getAddressFor(REMOTE_UUID_1)).thenReturn(InetAddress.getLoopbackAddress());
		Mockito.when(clusterInfo.getAddressFor(REMOTE_UUID_2)).thenReturn(InetAddress.getLoopbackAddress());
		
		Mockito.doAnswer(i -> {
			sem.release();
			return null;
		}).when(clusterInfo).updateAttribute(Mockito.eq(PAREMUS_DISCOVERY_DATA), Mockito.any(byte[].class));
		
		localWorker = new DefaultEventExecutorGroup(1);
		remoteWorker = new DefaultEventExecutorGroup(1);
	}
	
	@AfterEach
	public void tearDown() throws Exception {
		gd.destroy();
		
		localWorker.shutdownGracefully(10, 1000, TimeUnit.MILLISECONDS).sync();
		remoteWorker.shutdownGracefully(10, 1000, TimeUnit.MILLISECONDS).sync();
	}

	@Test
	public void testPortRegistration() {
		gd = new ClusterDiscoveryImpl(context, LOCAL_UUID, ldl, tls, config, localWorker, remoteWorker);
		
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		Mockito.when(fni.getClusterName()).thenReturn(CLUSTER_A);
		
		gd.addClusterInformation(clusterInfo);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.addNetworkInformation(fni);
		
		Mockito.verify(clusterInfo).updateAttribute(Mockito.eq(PAREMUS_DISCOVERY_DATA), Mockito.any(byte[].class));
	}
	
	private byte[] getPortPlusFilter(int port, String cluster) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (DataOutputStream dos = new DataOutputStream(baos)) {
			dos.writeShort(port);
			new EndpointFilter(cluster, new String[0]).writeOut(dos);		
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
			Mockito.when(fni.getClusterName()).thenReturn(CLUSTER_A);
			
			gd = new ClusterDiscoveryImpl(context, LOCAL_UUID, ldl, tls, config, localWorker, remoteWorker);
			
			gd.addClusterInformation(clusterInfo);
			gd.addNetworkInformation(fni);
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
		Mockito.when(fni.getClusterName()).thenReturn(CLUSTER_A);
		
		gd = new ClusterDiscoveryImpl(context, LOCAL_UUID, ldl, tls, config, localWorker, remoteWorker);
		
		gd.addClusterInformation(clusterInfo);
		gd.addNetworkInformation(fni);
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
		
		gd = new ClusterDiscoveryImpl(context, LOCAL_UUID, ldl, tls, config, localWorker, remoteWorker);
		
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		Mockito.when(fni.getClusterName()).thenReturn(CLUSTER_A);
		gd.addClusterInformation(clusterInfo);
		
		gd.addNetworkInformation(fni);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_B);
		Mockito.when(fni.getClusterName()).thenReturn(CLUSTER_B);
		gd.addClusterInformation(clusterInfo);
		
		gd.addNetworkInformation(fni);
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
		Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_1, PAREMUS_DISCOVERY_DATA)).thenReturn(new byte[] {(byte) (1234), (byte) 1234});
		Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_2, PAREMUS_DISCOVERY_DATA)).thenReturn(new byte[] {(byte) 5678, (byte) 5678});
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		Mockito.when(fni.getClusterName()).thenReturn(CLUSTER_A);
		
		gd = new ClusterDiscoveryImpl(context, LOCAL_UUID, ldl, tls, config, localWorker, remoteWorker);
		
		gd.addClusterInformation(clusterInfo);
		gd.addNetworkInformation(fni);
		gd.clusterEvent(clusterInfo, ADDED, LOCAL_UUID, emptySet(), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_1, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		gd.clusterEvent(clusterInfo, ADDED, REMOTE_UUID_2, singleton(PAREMUS_DISCOVERY_DATA), emptySet(), emptySet());
		
		gd.removeClusterInformation(clusterInfo);
		
		Mockito.verify(ldl).removeRemotesForCluster(CLUSTER_A);
	}

	@Test
	public void testReplaceCluster() throws Exception{
		Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_1, PAREMUS_DISCOVERY_DATA)).thenReturn(new byte[] {(byte) (1234), (byte) 1234});
		Mockito.when(clusterInfo.getMemberAttribute(REMOTE_UUID_2, PAREMUS_DISCOVERY_DATA)).thenReturn(new byte[] {(byte) 5678, (byte) 5678});
		Mockito.when(clusterInfo.getClusterName()).thenReturn(CLUSTER_A);
		Mockito.when(fni.getClusterName()).thenReturn(CLUSTER_A);
		
		gd = new ClusterDiscoveryImpl(context, LOCAL_UUID, ldl, tls, config, localWorker, remoteWorker);
		
		gd.addClusterInformation(clusterInfo);
		gd.addNetworkInformation(fni);
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
		ByteBuf buf = Unpooled.wrappedBuffer(dp.getData(), dp.getOffset(), dp.getLength());
		
		//Version 2, plain text
		assertEquals(2, buf.readByte());
		assertEquals(MessageType.ANNOUNCEMENT.ordinal(), buf.readByte());
		
		EndpointDescription received = EndpointSerializer.deserializeEndpoint(buf);
		assertEquals(ed.getId(), received.getId());
		assertEquals(state, buf.readInt());
		
		assertEquals(0, buf.readableBytes());
		buf.release();
	}
}
