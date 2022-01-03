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
package com.paremus.gossip.net;

import static com.paremus.gossip.v1.messages.SnapshotType.HEADER;
import static com.paremus.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.osgi.util.converter.Converters.standardConverter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.paremus.cluster.ClusterInformation;
import com.paremus.gossip.Gossip;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.netty.Config;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.gossip.v1.messages.SnapshotType;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;

@Disabled
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ReplicatorTest extends AbstractLeakCheckingTest {

	static final UUID IDA = new UUID(1234, 5678);
	static final UUID IDB = new UUID(9876, 5432);

	Map<String, Object> config = new HashMap<>();

	Semaphore semA = new Semaphore(0), semB = new Semaphore(0);

	ExecutorService ses = Executors.newFixedThreadPool(4);

	private Snapshot snapA, snapB;
	private MemberInfo memberA, memberB;

	private ServerSocketChannel server;
	
	private ChannelGroup group;

	@Mock
	Gossip gossipA, gossipB;

	@Mock
	NettyComms socketCommsA, socketCommsB;
	
	@Mock
	ClusterInformation clusterInfo;
	
	private ServerBootstrap sb;
	private Bootstrap b;
	private NioEventLoopGroup nioEventLoopGroup;

	@BeforeEach
	public void setUp() throws Exception {
		nioEventLoopGroup = new NioEventLoopGroup();
		group = new DefaultChannelGroup(nioEventLoopGroup.next());
		
		b = new Bootstrap()
				.group(nioEventLoopGroup)
				.channel(NioSocketChannel.class);
		
		sb = new ServerBootstrap()
				.group(nioEventLoopGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<Channel>() {

					@Override
					protected void initChannel(Channel ch) throws Exception {
						group.add(ch);
						ch.pipeline().addLast(new IncomingTCPReplicator(ch, IDB, gossipB));
					}
				});
		
		server = (ServerSocketChannel) sb.bind(new InetSocketAddress(getLoopbackAddress(), 0)).sync().channel();

		snapA = new Snapshot(new Snapshot(IDA, 4567, (short) 1, PAYLOAD_UPDATE,
				singletonMap("foo", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 2345));
		snapB = new Snapshot(new Snapshot(IDB, server.localAddress().getPort(),
				(short) 1, PAYLOAD_UPDATE, singletonMap("foo",
						new byte[] { 0x7F }), 1), new InetSocketAddress(
				getLoopbackAddress(), 3456));

		config.put("cluster.name", "test");
		Config cfg = standardConverter().convert(config).to(Config.class);

		memberA = new MemberInfo(cfg, snapA, clusterInfo, Collections.emptyList());
		memberA.update(snapA);
		Mockito.when(gossipA.getInfoFor(IDA)).thenReturn(memberA);

		memberB = new MemberInfo(cfg, snapB, clusterInfo, Collections.emptyList());
		memberB.update(snapB);
		Mockito.when(gossipB.getInfoFor(IDB)).thenReturn(memberB);
	}

	@AfterEach
	public void tearDown() throws IOException {
		server.close();
		group.close();
		nioEventLoopGroup.shutdownGracefully();
	}

	private ArgumentMatcher<Snapshot> isSnapshotWithIdAndType(UUID id, SnapshotType type) {
		return new ArgumentMatcher<Snapshot>() {

			@Override
			public boolean matches(Snapshot item) {
				return id.equals(item.getId()) && type == item.getMessageType();
			}
		};
	}

	@Test
	public void testReplicate() throws Exception {
		UUID snapCId = new UUID(2345, 6789);
		Snapshot snapC = new Snapshot(new Snapshot(snapCId, 2, (short) 1,
				PAYLOAD_UPDATE, singletonMap("bar", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 1));

		MemberInfo memberC = new MemberInfo(standardConverter().convert(
				config).to(Config.class), snapC, clusterInfo, Collections.emptyList());
		memberC.update(snapC);

		UUID snapDId = new UUID(3456, 7890);
		Snapshot snapDA = new Snapshot(new Snapshot(snapDId, 2, (short) 1,
				PAYLOAD_UPDATE, singletonMap("bar", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 1));
		
		Thread.sleep(50);
		
		Snapshot snapDB = new Snapshot(new Snapshot(snapDId, 2, (short) 1,
				PAYLOAD_UPDATE, singletonMap("bar", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 1));
		
		MemberInfo memberDA = new MemberInfo(standardConverter().convert(
				config).to(Config.class), snapDA, clusterInfo, Collections.emptyList());
		memberDA.update(snapDA);
		MemberInfo memberDB = new MemberInfo(standardConverter().convert(
				config).to(Config.class), snapDB, clusterInfo, Collections.emptyList());
		memberDB.update(snapDB);

		Mockito.when(gossipA.getInfoFor(snapCId)).thenReturn(memberC);
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipA.getInfoFor(snapDId)).thenReturn(memberDA);

		Mockito.when(gossipB.getInfoFor(snapDId)).thenReturn(memberDB);
		Mockito.when(gossipB.getAllSnapshots()).thenReturn(
				Arrays.asList(memberB.toSnapshot(HEADER), memberDB.toSnapshot(HEADER)));

		Channel client = b.handler(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) throws Exception {
				ch.pipeline().addLast(new OutgoingTCPReplicator(ch, IDA, gossipA, IDB, 72, 
						Arrays.asList(memberA.toSnapshot(HEADER), memberB.toSnapshot(HEADER), 
								memberC.toSnapshot(HEADER), memberDA.toSnapshot(HEADER)), ch.newSucceededFuture()));
			}
		}).connect(server.localAddress()).sync().channel();
		
		
		Future<Void> clientSync = client.closeFuture();
		Future<Void> serverSync = group.newCloseFuture();
		
		clientSync.addListener(f -> semA.release());
		serverSync.addListener(f -> semB.release());

		assertTrue(semA.tryAcquire(2, SECONDS));
		assertTrue(semB.tryAcquire(2, SECONDS));

		Mockito.verify(gossipB).merge(
				Mockito.argThat(isSnapshotWithIdAndType(IDA, PAYLOAD_UPDATE)));
		Mockito.verify(gossipB)
				.merge(Mockito.argThat(isSnapshotWithIdAndType(snapCId,
						PAYLOAD_UPDATE)));
		
		Mockito.verify(gossipA, Mockito.never()).merge(Mockito.argThat(isSnapshotWithIdAndType(snapDId, PAYLOAD_UPDATE)));
		Mockito.verify(gossipB, Mockito.never()).merge(Mockito.argThat(isSnapshotWithIdAndType(snapDId, PAYLOAD_UPDATE)));
		
		assertEquals(snapDB.getSnapshotTimestamp(), memberDA.toSnapshot(HEADER).getSnapshotTimestamp());
	}

}
