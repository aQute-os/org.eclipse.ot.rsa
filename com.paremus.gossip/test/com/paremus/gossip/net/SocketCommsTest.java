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

import static com.paremus.gossip.v1.messages.SnapshotType.PAYLOAD_UPDATE;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

import javax.net.ServerSocketFactory;

import org.junit.jupiter.api.AfterEach;
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

import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipReplicator;
import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncryptionDetails;

import aQute.bnd.annotation.metatype.Configurable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SocketCommsTest {

	static final UUID ID = new UUID(1234, 5678);
	
	SocketComms socketComms;
	
	Map<String, Object> config = new HashMap<>();
	
	Semaphore sem = new Semaphore(0);
	
	ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
	
	@Mock
	Gossip gossip;

	@Mock
	MemberInfo member;

	@Mock
	EncodingScheme encodingScheme;

	@Mock(answer=RETURNS_DEEP_STUBS)
	GossipReplicator replicator;
	
	@BeforeEach
	public void setUp() throws Exception {
		socketComms = new SocketComms("cluster", ID, Configurable.createConfigurable(Config.class, config), encodingScheme,
				ServerSocketFactory.getDefault());
		
		Mockito.doAnswer(this::count).when(gossip)
			.handleMessage(Mockito.any(InetSocketAddress.class), Mockito.any(DataInput.class));
	}
	
	private Object count(InvocationOnMock invocation) {
		sem.release();
		return null;
	}
	
	@AfterEach
	public void tearDown() throws Exception {
		socketComms.destroy();
	}

	@Test
	public void testStartListeningPlain() throws Exception {
		socketComms.startListening(gossip, replicator, ses);
		
		Mockito.when(encodingScheme.validateAndDecode(Mockito.any(byte[].class), Mockito.any(byte[].class), 
				Mockito.anyInt(), Mockito.anyInt(), Mockito.any()))
				.then(new Answer<DataInput>() {
					@Override
					public DataInput answer(InvocationOnMock invocation)
							throws Throwable {
						return new DataInputStream(new ByteArrayInputStream((byte[]) invocation.getArguments()[1],
								(int) invocation.getArguments()[2], (int) invocation.getArguments()[3]));
					}
				});
		
		try (DatagramSocket s = new DatagramSocket()) {
			s.send(new DatagramPacket(new byte[] {1, 1, 1, 2, 0x7F}, 5, getLoopbackAddress(), 
					socketComms.getUdpPort()));
			
			assertTrue(sem.tryAcquire(1000, MILLISECONDS));
			
			ArgumentCaptor<DataInput> captor = ArgumentCaptor.forClass(DataInput.class);
			Mockito.verify(gossip).handleMessage(Mockito.eq(new InetSocketAddress(getLoopbackAddress(), 
					s.getLocalPort())), captor.capture());
			assertEquals(1, captor.getValue().readUnsignedByte());
			assertEquals(2, captor.getValue().readUnsignedByte());
			assertEquals(0x7F, captor.getValue().readUnsignedByte());
		}
	}

	@Test
	public void testCertExchange() throws Exception {
		socketComms.destroy();
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		Mockito.when(encodingScheme.requiresCertificates()).thenReturn(true);
		Mockito.when(encodingScheme.getCertificate()).thenReturn(cert);
		Mockito.when(encodingScheme.outgoingKeyExchangeMessage(Mockito.any(byte[].class), 
				Mockito.any(Certificate.class))).thenReturn(new byte[] {1, 2, 3});
		mockEncode();
		
		socketComms = new SocketComms("cluster", ID, Configurable.createConfigurable(Config.class, config), encodingScheme,
				ServerSocketFactory.getDefault());
		socketComms.startListening(gossip, replicator, ses);
		
		
		try (DatagramSocket s = new DatagramSocket()) {
			s.setSoTimeout(1000);
			byte[] bytes = new byte[8192];
			bytes[0] = 1;
			bytes[1] = 2;
			byte[] certBytes = cert.getEncoded();
			System.arraycopy(certBytes, 0, bytes, 2, certBytes.length);
			s.send(new DatagramPacket(bytes, certBytes.length + 2, getLoopbackAddress(), 
					socketComms.getUdpPort()));
			
			DatagramPacket dp = new DatagramPacket(new byte[1024], 1024);
			s.receive(dp);
			
			Mockito.verifyNoInteractions(gossip);
			
			assertEquals(1, dp.getData()[dp.getOffset()]);
			assertEquals(3, dp.getData()[dp.getOffset() + 1]);
			
			Certificate sent = CertificateFactory.getInstance("X.509")
				.generateCertificate(new ByteArrayInputStream(dp.getData(), dp.getOffset() + 2, dp.getLength() - 2));
			sent.verify(cert.getPublicKey());
			
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(dp.getData(), dp.getOffset() + dp.getLength() - 16, 16));
			assertEquals(ID, new UUID(dis.readLong(), dis.readLong()));
			
			s.receive(dp);
			assertEquals(1, dp.getData()[dp.getOffset()]);
			assertEquals(4, dp.getData()[dp.getOffset() + 1]);
			assertEquals(2, dp.getLength());
			
			
			s.send(new DatagramPacket(new byte[] {1,4}, 2, getLoopbackAddress(), socketComms.getUdpPort()));
			
			s.receive(dp);
			
			assertEquals(1, dp.getData()[dp.getOffset()]);
			assertEquals(5, dp.getData()[dp.getOffset() + 1]);
			assertEquals(7, dp.getData()[dp.getOffset() + 2]);
			assertEquals(3, dp.getLength());
		}
	}

	private void mockEncode() {
		Mockito.when(encodingScheme.encode(Mockito.any(byte[].class), Mockito.any(byte[].class), 
				Mockito.anyInt(), Mockito.anyInt())).then(new Answer<byte[]>() {
					@Override
					public byte[] answer(InvocationOnMock invocation)
							throws Throwable {
						ByteArrayOutputStream baos = new ByteArrayOutputStream();
						baos.write((byte[]) invocation.getArguments()[0]);
						baos.write((byte[]) invocation.getArguments()[1]);
						
						return baos.toByteArray();
					}
				});

		Mockito.when(encodingScheme.validateAndDecode(Mockito.any(byte[].class), Mockito.any(byte[].class), 
				Mockito.anyInt(), Mockito.anyInt(), Mockito.any(EncryptionDetails.class))).then(new Answer<DataInput>() {
					@Override
					public DataInput answer(InvocationOnMock invocation)
							throws Throwable {
						ByteArrayInputStream bais = new ByteArrayInputStream((byte[]) invocation.getArguments()[1],
								(int) invocation.getArguments()[2], (int) invocation.getArguments()[3]);
						
						return new DataInputStream(bais);
					}
				});

		Mockito.when(encodingScheme.outgoingKeyExchangeMessage(Mockito.any(byte[].class), 
				Mockito.any(Certificate.class)))
				.then(new Answer<byte[]>() {
					@Override
					public byte[] answer(InvocationOnMock invocation)
							throws Throwable {
						ByteArrayOutputStream baos = new ByteArrayOutputStream();
						baos.write((byte[]) invocation.getArguments()[0]);
						baos.write(7);
						
						return baos.toByteArray();
					}
				});
		
	}
	
	@Test
	public void testStopListening() throws Exception {
		socketComms.startListening(gossip, replicator, ses);
		socketComms.stopListening();
		
		try (DatagramSocket s = new DatagramSocket()) {
			s.send(new DatagramPacket(new byte[] {1, 1, 1, 0x7F}, 4, getLoopbackAddress(), 
					socketComms.getUdpPort()));
		}
		//Should be no messages heard
		assertFalse(sem.tryAcquire(1000, MILLISECONDS));
	}

	@Test
	public void testDestroy() throws SocketException {
		try (DatagramSocket d = new DatagramSocket(socketComms.getUdpPort())) {
			fail("Should be prevented from binding");
		} catch (BindException be) {}
		
		socketComms.destroy();
		
		try (DatagramSocket d = new DatagramSocket(socketComms.getUdpPort())) {}
		
	}

	@Test
	public void testPublish() throws Exception {
		socketComms.startListening(gossip, replicator, ses);
		
		Mockito.when(encodingScheme.encode(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
			.then(new Answer<byte[]>() {

				@Override
				public byte[] answer(InvocationOnMock invocation)
						throws Throwable {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					
					baos.write((byte[]) invocation.getArguments()[0]);
					baos.write((byte[]) invocation.getArguments()[1], 
							(int)invocation.getArguments()[2], (int)invocation.getArguments()[3]);
					return baos.toByteArray();
				}
			});
		
		
		try (DatagramSocket s = new DatagramSocket(0, getLoopbackAddress())) {
			socketComms.publish(new byte[] {0x7F}, Arrays.asList(s.getLocalSocketAddress()));
			s.setSoTimeout(500);
			DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
			s.receive(packet);
			assertEquals(1, packet.getData()[0]);
			assertEquals(1, packet.getData()[1]);
			assertEquals(0x7F, packet.getData()[2]);
			assertEquals(3, packet.getLength());
		}
	}

	@Test
	public void testReplicateNotStarted() throws Exception {
		
		Snapshot snap1 = new Snapshot(new Snapshot(ID, 5678, (short) 1, PAYLOAD_UPDATE, singletonMap(
				"foo", new byte[] {0x7F}), 1), new InetSocketAddress(getLoopbackAddress(), 1234));
		UUID snap2Id = new UUID(2345, 6789);
		Snapshot snap2 = new Snapshot(new Snapshot(snap2Id, 2, (short) 1, PAYLOAD_UPDATE, singletonMap(
				"bar", new byte[] {0x7F}), 1), new InetSocketAddress(getLoopbackAddress(), 1));

		socketComms.replicate(member, Arrays.asList(snap1, snap2));
	}
	
	@Test
	public void testOutgoingReplicate() throws Exception {
			
		Snapshot snap1 = new Snapshot(new Snapshot(ID, 5678, (short) 1, PAYLOAD_UPDATE, singletonMap(
				"foo", new byte[] {0x7F}), 1), new InetSocketAddress(getLoopbackAddress(), 1234));
		UUID snap2Id = new UUID(2345, 6789);
		Snapshot snap2 = new Snapshot(new Snapshot(snap2Id, 2, (short) 1, PAYLOAD_UPDATE, singletonMap(
				"bar", new byte[] {0x7F}), 1), new InetSocketAddress(getLoopbackAddress(), 1));
		
		socketComms.startListening(gossip, replicator, ses);
		
		socketComms.replicate(member, Arrays.asList(snap1, snap2));
		
		Mockito.verify(replicator).outgoingExchangeSnapshots(ses, member, 
				Arrays.asList(snap1, snap2), socketComms.getBindAddress());
	}

	@Test
	public void testIncomingReplicate() throws Exception {
		
		socketComms.startListening(gossip, replicator, ses);
		
		Mockito.when(replicator.incomingExchangeSnapshots(Mockito.any(Executor.class), Mockito.any(Socket.class)))
			.thenAnswer(i -> { sem.release(); return null; });
		
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(socketComms.getBindAddress(), socketComms.getTcpPort()), 1000);
		}
	
		assertTrue(sem.tryAcquire(1, SECONDS));
	}

	public void testGetCertificate() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");

		Mockito.when(encodingScheme.requiresCertificates()).thenReturn(true);
		
		socketComms.startListening(gossip, replicator, ses);
		
		InetSocketAddress address = new InetSocketAddress(getLoopbackAddress(), 0);
		try (DatagramSocket ds = new DatagramSocket(address)) {

			assertNull(socketComms.getCertificateFor(ds.getLocalSocketAddress()));
			
			byte[] bytes = new byte[8192];
			bytes[0] = 1;
			bytes[1] = 2;
			byte[] certBytes = cert.getEncoded();
			System.arraycopy(certBytes, 0, bytes, 2, certBytes.length);
			ds.send(new DatagramPacket(bytes, certBytes.length + 2, getLoopbackAddress(), 
					socketComms.getUdpPort()));
			
			Thread.sleep(200);
			
			assertEquals(cert, socketComms.getCertificateFor(ds.getLocalSocketAddress()));
		}
	}

	public void testSendKeyUpdate() throws Exception {
		socketComms.destroy();

		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		Mockito.when(encodingScheme.requiresCertificates()).thenReturn(true);
		Mockito.when(encodingScheme.getCertificate()).thenReturn(cert);
		Mockito.when(encodingScheme.outgoingKeyExchangeMessage(Mockito.any(byte[].class), 
				Mockito.any(Certificate.class))).thenReturn(new byte[] {1, 2, 3});
		mockEncode();
		
		socketComms = new SocketComms("cluster", ID, Configurable.createConfigurable(Config.class, config), encodingScheme,
				ServerSocketFactory.getDefault());
		socketComms.startListening(gossip, replicator, ses);
		
		Mockito.when(encodingScheme.requiresCertificates()).thenReturn(true);
		
		socketComms.startListening(gossip, replicator, ses);
		
		InetSocketAddress address = new InetSocketAddress(getLoopbackAddress(), 0);
		try (DatagramSocket ds = new DatagramSocket(address)) {
			ds.setSoTimeout(1000);
			
			byte[] bytes = new byte[8192];
			bytes[0] = 1;
			bytes[1] = 2;
			byte[] certBytes = cert.getEncoded();
			System.arraycopy(certBytes, 0, bytes, 2, certBytes.length);
			ds.send(new DatagramPacket(bytes, certBytes.length + 2, getLoopbackAddress(), 
					socketComms.getUdpPort()));
			
			Thread.sleep(200);
			DatagramPacket dp = new DatagramPacket(new byte[10240], 10240);

			//A certificate response and a key exchange request
			ds.receive(dp);
			assertEquals(3, dp.getData()[dp.getOffset() + 1]);
			ds.receive(dp);
			assertEquals(4, dp.getData()[dp.getOffset() + 1]);
			
			socketComms.sendKeyUpdate(Stream.of((InetSocketAddress) ds.getLocalSocketAddress()));
			
			ds.receive(dp);
			byte[] data = dp.getData();
			int offset = dp.getOffset();
			assertEquals(1, data[offset]);
			assertEquals(6, data[offset + 1]);
		}
	}
	
}
