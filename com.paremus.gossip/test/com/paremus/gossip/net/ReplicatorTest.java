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
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.util.promise.FailedPromisesException;
import org.osgi.util.promise.Promise;

import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipReplicator;
import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.gossip.v1.messages.SnapshotType;
import com.paremus.net.encode.EncodingScheme;

import aQute.bnd.annotation.metatype.Configurable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ReplicatorTest {

	static final UUID IDA = new UUID(1234, 5678);
	static final UUID IDB = new UUID(9876, 5432);

	GossipReplicator replicatorA, replicatorB;

	Map<String, Object> config = new HashMap<>();

	Semaphore semA = new Semaphore(0), semB = new Semaphore(0);

	ExecutorService ses = Executors.newFixedThreadPool(4);

	private Snapshot snapA, snapB;
	private MemberInfo memberA, memberB;

	private ServerSocket server;

	@Mock
	Gossip gossipA, gossipB;

	@Mock
	EncodingScheme encodingSchemeA, encodingSchemeB;

	@Mock
	SocketComms socketCommsA, socketCommsB;
	
	@Mock
	ClusterInformation clusterInfo;

	@BeforeEach
	public void setUp() throws Exception {
		server = new ServerSocket();
		server.setSoTimeout(1000);
		server.bind(new InetSocketAddress(getLoopbackAddress(), 0));
		
		Mockito.when(encodingSchemeA.getSocketFactory()).thenReturn(SocketFactory.getDefault());
		Mockito.when(encodingSchemeB.getSocketFactory()).thenReturn(SocketFactory.getDefault());

		replicatorA = new TCPReplicator(IDA, gossipA, encodingSchemeA,
				socketCommsA);
		replicatorB = new TCPReplicator(IDB, gossipB, encodingSchemeB,
				socketCommsB);

		snapA = new Snapshot(new Snapshot(IDA, 4567, (short) 1, PAYLOAD_UPDATE,
				singletonMap("foo", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 2345));
		snapB = new Snapshot(new Snapshot(IDB, server.getLocalPort(),
				(short) 1, PAYLOAD_UPDATE, singletonMap("foo",
						new byte[] { 0x7F }), 1), new InetSocketAddress(
				getLoopbackAddress(), 3456));

		config.put("cluster.name", "test");
		Config cfg = Configurable.createConfigurable(Config.class, config);

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
	}

	private ArgumentMatcher<Snapshot> isSnapshotWithIdAndType(UUID id, SnapshotType type) {
		return new ArgumentMatcher<Snapshot>() {

			@Override
			public boolean matches(Snapshot item) {
				if (item instanceof Snapshot) {
					Snapshot s = (Snapshot) item;
					return id.equals(s.getId()) && type == s.getMessageType();
				}
				return false;
			}

		};
	}

	@Test
	public void testReplicate() throws Exception {
		UUID snapCId = new UUID(2345, 6789);
		Snapshot snapC = new Snapshot(new Snapshot(snapCId, 2, (short) 1,
				PAYLOAD_UPDATE, singletonMap("bar", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 1));

		MemberInfo memberC = new MemberInfo(Configurable.createConfigurable(
				Config.class, config), snapC, clusterInfo, Collections.emptyList());
		memberC.update(snapC);

		UUID snapDId = new UUID(3456, 7890);
		Snapshot snapDA = new Snapshot(new Snapshot(snapDId, 2, (short) 1,
				PAYLOAD_UPDATE, singletonMap("bar", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 1));
		
		Thread.sleep(50);
		
		Snapshot snapDB = new Snapshot(new Snapshot(snapDId, 2, (short) 1,
				PAYLOAD_UPDATE, singletonMap("bar", new byte[] { 0x7F }), 1),
				new InetSocketAddress(getLoopbackAddress(), 1));
		
		MemberInfo memberDA = new MemberInfo(Configurable.createConfigurable(
				Config.class, config), snapDA, clusterInfo, Collections.emptyList());
		memberDA.update(snapDA);
		MemberInfo memberDB = new MemberInfo(Configurable.createConfigurable(
				Config.class, config), snapDB, clusterInfo, Collections.emptyList());
		memberDB.update(snapDB);

		Mockito.when(gossipA.getInfoFor(snapCId)).thenReturn(memberC);
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipA.getInfoFor(snapDId)).thenReturn(memberDA);

		Mockito.when(gossipB.getInfoFor(snapDId)).thenReturn(memberDB);
		Mockito.when(gossipB.getAllSnapshots()).thenReturn(
				Arrays.asList(memberB.toSnapshot(HEADER), memberDB.toSnapshot(HEADER)));

		Promise<Long> out;
		Promise<Long> in;

		out = replicatorA.outgoingExchangeSnapshots(ses, memberB, Arrays.asList(memberA.toSnapshot(HEADER), 
				memberB.toSnapshot(HEADER), memberC.toSnapshot(HEADER), memberDA.toSnapshot(HEADER)),
				InetAddress.getLoopbackAddress());

		in = replicatorB.incomingExchangeSnapshots(ses, server.accept());

		out.then(
				p -> {
					semA.release();
					return null;
				},
				p -> ((FailedPromisesException) p.getFailure())
						.getFailedPromises().forEach(
								p2 -> {
									try {
										ofNullable(p2.getFailure()).ifPresent(
												Throwable::printStackTrace);
									} catch (InterruptedException ie) {
									}
								}));
		in.then(p -> {
			semB.release();
			return null;
		},
				p -> ((FailedPromisesException) p.getFailure())
						.getFailedPromises().forEach(
								p2 -> {
									try {
										ofNullable(p2.getFailure()).ifPresent(
												Throwable::printStackTrace);
									} catch (InterruptedException ie) {
									}
								}));

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

	private SSLContext getSSLContext() {
		try {
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			
			KeyStore keystore = KeyStore.getInstance("JKS");
			keystore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
			
			kmf.init(keystore, "paremus".toCharArray());
			
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				
			KeyStore truststore = KeyStore.getInstance("JKS");
			truststore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
			
			tmf.init(truststore);
			
			SSLContext context = SSLContext.getInstance("TLSv1.2");
			
			context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
			return context;
		} catch (IOException | GeneralSecurityException e) {
			throw new RuntimeException("Unable to load the SSL config", e);
		}
	}

	private void setupSSL(EncodingScheme es, SSLContext context) {
		Mockito.when(es.getServerSocketFactory()).thenReturn(context.getServerSocketFactory());
		Mockito.when(es.getSocketFactory()).thenReturn(context.getSocketFactory());
	}

	@Test
	public void testSecureReplicate() throws Exception {
	
		SSLContext context = getSSLContext();
	
		setupSSL(encodingSchemeA, context);
		setupSSL(encodingSchemeB, context);
		
		int port = server.getLocalPort();
		
		server.close();
		
		server = context.getServerSocketFactory().createServerSocket(port);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
	
		testReplicate();
	}

	private boolean checkExceptionMessagesContain(Throwable t, String messageExtract) {
		
		if(t instanceof FailedPromisesException) {
			for(Promise<?> p : ((FailedPromisesException)t).getFailedPromises()) {
				try {
					if(checkExceptionMessagesContain(p.getFailure(), messageExtract)) {
						return true;
					}
				} catch (InterruptedException e) {}
			}
		}
		
		if (t != null) {
			return t.getMessage().contains(messageExtract);
		}
		return false;
	}

	@Test
	public void testSecureReplicateInsecurePartner() throws Exception {
		
		SSLContext context = getSSLContext();
		
		setupSSL(encodingSchemeA, context);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		
		Promise<Long> out;
		Promise<Long> in;
		
		out = replicatorA.outgoingExchangeSnapshots(ses, memberB, Arrays.asList(memberA.toSnapshot(HEADER), 
				memberB.toSnapshot(HEADER)),
				InetAddress.getLoopbackAddress());

		in = replicatorB.incomingExchangeSnapshots(ses, server.accept());
		
		out.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "Unsupported or unrecognized SSL message")) {
				semA.release();
			}
		});
		in.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "Unknown protocol version")) {
				semB.release();
			}
		});
		
		assertTrue(semA.tryAcquire(2, SECONDS));
		assertTrue(semB.tryAcquire(10, SECONDS));
	}

	@Test
	public void testInsecureReplicateSecurePartner() throws Exception {
		
		SSLContext context = getSSLContext();
		
		setupSSL(encodingSchemeB, context);
		
		int port = server.getLocalPort();
		
		server.close();
		
		server = context.getServerSocketFactory().createServerSocket(port);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		
		Promise<Long> out;
		Promise<Long> in;
		
		out = replicatorA.outgoingExchangeSnapshots(ses, memberB, Arrays.asList(memberA.toSnapshot(HEADER), 
				memberB.toSnapshot(HEADER)),
				InetAddress.getLoopbackAddress());
		
		in = replicatorB.incomingExchangeSnapshots(ses, server.accept());
		
		out.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "Unknown protocol version")) {
				semA.release();
			}
		});
		in.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "Unsupported or unrecognized SSL message")) {
				semB.release();
			}
		});
		
		assertTrue(semA.tryAcquire(2, SECONDS));
		assertTrue(semB.tryAcquire(2, SECONDS));
	}
}
