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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.IvParameterSpec;

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
import com.paremus.net.encode.EncryptionDetails;

import aQute.bnd.annotation.metatype.Configurable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SpecialTCPReplicatorTest {

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

		replicatorA = new SpecialTCPReplicator(IDA, gossipA, encodingSchemeA,
				socketCommsA);
		replicatorB = new SpecialTCPReplicator(IDB, gossipB, encodingSchemeB,
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

	private void setupEncryption(EncodingScheme es, Key k) {
		Mockito.when(es.isConfidential()).thenReturn(true);
		Mockito.when(es.encryptingStream(Mockito.any(OutputStream.class))).then(
				i -> {
					Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
					c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(
							new byte[16]));
					return new DataOutputStream(new CipherOutputStream((OutputStream) i
							.getArguments()[0], c));
				});
		Mockito.doAnswer(i -> { 
				OutputStream os = (OutputStream)i.getArguments()[0];
				os.write(new byte[32]); 
				os.flush();
				return null; 
			}).when(es)
			.forceFlush(Mockito.any());
	}

	private void setupDecryption(EncodingScheme es, Key k) {
		Mockito.when(es.decryptingStream(Mockito.any(InputStream.class), Mockito.any(EncryptionDetails.class))).then(
				i -> {
					Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
					c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(
							new byte[16]));
					return new DataInputStream(new CipherInputStream(
							(InputStream) i.getArguments()[0], c));
				});
		Mockito.doAnswer(i -> { 
				((DataInputStream)i.getArguments()[0]).readFully(new byte[32]); 
				return null; 
			}).when(es)
			.skipForcedFlush(Mockito.any());
	}

	private void setupDecryption(EncodingScheme es) {
		Mockito.when(es.decryptingStream(Mockito.any(InputStream.class), Mockito.isNotNull(EncryptionDetails.class))).then(
				i -> {
					Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
					c.init(Cipher.DECRYPT_MODE, ((EncryptionDetails) i.getArguments()[1]).getKey(), 
							new IvParameterSpec(new byte[16]));
					return new DataInputStream(new CipherInputStream(
							(InputStream) i.getArguments()[0], c));
				});
		Mockito.doAnswer(i -> { 
			((DataInputStream)i.getArguments()[0]).readFully(new byte[32]); 
			return null; 
		}).when(es)
		.skipForcedFlush(Mockito.any());
	}

	@Test
	public void testSecureReplicatePreSharedInfo() throws Exception {
	
		KeyGenerator gen = KeyGenerator.getInstance("AES");
	
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
	
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA, keyB);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB, keyA);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
	
		Mockito.when(
				socketCommsA.getEncryptionDetailsFor(new InetSocketAddress(
						InetAddress.getLocalHost(), 3456))).thenReturn(
				new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000,
						TimeUnit.SECONDS));
		Mockito.when(
				socketCommsB.getEncryptionDetailsFor(new InetSocketAddress(
						InetAddress.getLocalHost(), 2345))).thenReturn(
				new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000,
						TimeUnit.SECONDS));
	
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
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		
		Promise<Long> out;
		Promise<Long> in;
		
		out = replicatorA.outgoingExchangeSnapshots(ses, memberB, Arrays.asList(memberA.toSnapshot(HEADER), 
				memberB.toSnapshot(HEADER)),
				InetAddress.getLoopbackAddress());

		in = replicatorB.incomingExchangeSnapshots(ses, server.accept());
		
		out.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "requested an insecure exchange but this node is secure")) {
				semA.release();
			}
		});
		in.then(null, p -> semB.release());
		
		assertTrue(semA.tryAcquire(2, SECONDS));
		assertTrue(semB.tryAcquire(2, SECONDS));
	}

	@Test
	public void testInsecureReplicateSecurePartner() throws Exception {
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		
		Promise<Long> out;
		Promise<Long> in;
		
		out = replicatorA.outgoingExchangeSnapshots(ses, memberB, Arrays.asList(memberA.toSnapshot(HEADER), 
				memberB.toSnapshot(HEADER)),
				InetAddress.getLoopbackAddress());
		
		in = replicatorB.incomingExchangeSnapshots(ses, server.accept());
		
		out.then(null, p -> { 
				semA.release();
		});
		in.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "requested an insecure exchange but this node is secure")) {
				semB.release();
			}
		});
		
		assertTrue(semA.tryAcquire(2, SECONDS));
		assertTrue(semB.tryAcquire(2, SECONDS));
	}
	
	@Test
	public void testSecureReplicateNoKeyExchangePossibleA() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		
		Promise<Long> out;
		Promise<Long> in;
		
		out = replicatorA.outgoingExchangeSnapshots(ses, memberB, Arrays.asList(memberA.toSnapshot(HEADER), 
				memberB.toSnapshot(HEADER)),
				InetAddress.getLoopbackAddress());
		
		in = replicatorB.incomingExchangeSnapshots(ses, server.accept());
		
		out.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "is configured with a static key and cannot exchange it securely")) {
				semA.release();
			}
		});
		in.then(null, p -> semB.release());
		
		assertTrue(semA.tryAcquire(2, SECONDS));
		assertTrue(semB.tryAcquire(2, SECONDS));
	}

	@Test
	public void testSecureReplicateNoKeyExchangePossibleB() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		Mockito.when(encodingSchemeB.dynamicKeyGenerationSupported()).thenReturn(true);
		Mockito.when(encodingSchemeB.requiresCertificates()).thenReturn(true);
		Mockito.when(encodingSchemeB.getCertificate()).thenReturn(cert);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		
		Promise<Long> out;
		Promise<Long> in;
		
		out = replicatorA.outgoingExchangeSnapshots(ses, memberB, Arrays.asList(memberA.toSnapshot(HEADER), 
				memberB.toSnapshot(HEADER)),
				InetAddress.getLoopbackAddress());
		
		in = replicatorB.incomingExchangeSnapshots(ses, server.accept());
		
		out.then(null, p -> { 
			if(checkExceptionMessagesContain(p.getFailure(), "requested a certificate but none is available locally")) {
				semA.release();
			}
		});
		in.then(null, p -> semB.release());
		
		assertTrue(semA.tryAcquire(2, SECONDS));
		assertTrue(semB.tryAcquire(2, SECONDS));
	}
	
	private void setupCert(EncodingScheme es, Certificate cert) {
		Mockito.when(es.dynamicKeyGenerationSupported()).thenReturn(true);
		Mockito.when(es.requiresCertificates()).thenReturn(true);
		Mockito.when(es.getCertificate()).thenReturn(cert);
	}

	@Test
	public void testSecureReplicateWithKeyExchangeAndCertsExchange() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(encodingSchemeA.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 4;
			return b;
		});

		Mockito.when(encodingSchemeB.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 3;
			return b;
		});
		
		Mockito.when(encodingSchemeA.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
			.thenAnswer(i -> {
				return (((byte[])i.getArguments()[1])[2] == 3) ? new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
			});

		Mockito.when(encodingSchemeB.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 4) ? new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}
	
	@Test
	public void testSecureReplicateWithKeyExchangeAndPreExchangedCerts() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(socketCommsA.getCertificateFor(memberB.getUdpAddress())).thenReturn(cert);
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		Mockito.when(socketCommsB.getCertificateFor(memberA.getUdpAddress())).thenReturn(cert);
		
		Mockito.when(encodingSchemeA.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 4;
			return b;
		});

		Mockito.when(encodingSchemeB.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 3;
			return b;
		});
		
		Mockito.when(encodingSchemeA.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
			.thenAnswer(i -> {
				return (((byte[])i.getArguments()[1])[2] == 3) ? new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
			});

		Mockito.when(encodingSchemeB.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 4) ? new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}

	@Test
	public void testSecureReplicateWithKeyExchangeAndOnePreExchangedCertA() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(socketCommsA.getCertificateFor(memberB.getUdpAddress())).thenReturn(cert);
		
		Mockito.when(encodingSchemeA.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 4;
			return b;
		});
		
		Mockito.when(encodingSchemeB.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 3;
			return b;
		});
		
		Mockito.when(encodingSchemeA.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
		.thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 3) ? new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		Mockito.when(encodingSchemeB.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 4) ? new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}

	@Test
	public void testSecureReplicateWithKeyExchangeAndOnePreExchangedCertB() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		Mockito.when(socketCommsB.getCertificateFor(memberA.getUdpAddress())).thenReturn(cert);
		
		Mockito.when(encodingSchemeA.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 4;
			return b;
		});
		
		Mockito.when(encodingSchemeB.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 3;
			return b;
		});
		
		Mockito.when(encodingSchemeA.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
		.thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 3) ? new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		Mockito.when(encodingSchemeB.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 4) ? new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}

	@Test
	public void testSecureReplicateWithOneKeyExchangeAAndPreExchangedCerts() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(socketCommsA.getCertificateFor(memberB.getUdpAddress())).thenReturn(cert);
		Mockito.when(socketCommsA.getEncryptionDetailsFor(memberB.getUdpAddress()))
			.thenReturn(new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS));
		
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		Mockito.when(socketCommsB.getCertificateFor(memberA.getUdpAddress())).thenReturn(cert);
		
		Mockito.when(encodingSchemeA.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 4;
			return b;
		});

		Mockito.when(encodingSchemeB.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 4) ? new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}

	@Test
	public void testSecureReplicateWithOneKeyExchangeBAndPreExchangedCerts() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(socketCommsA.getCertificateFor(memberB.getUdpAddress())).thenReturn(cert);
		
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		Mockito.when(socketCommsB.getCertificateFor(memberA.getUdpAddress())).thenReturn(cert);
		Mockito.when(socketCommsB.getEncryptionDetailsFor(memberA.getUdpAddress()))
			.thenReturn(new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS));
		
		Mockito.when(encodingSchemeB.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 3;
			return b;
		});
		
		Mockito.when(encodingSchemeA.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 3) ? new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}
	
	@Test
	public void testSecureReplicateWithOneKeyExchangeAndOnePreExchangedCertA() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(gossipA.getInfoFor(IDB)).thenReturn(memberB);
		Mockito.when(socketCommsA.getCertificateFor(memberB.getUdpAddress())).thenReturn(cert);
		Mockito.when(socketCommsA.getEncryptionDetailsFor(memberB.getUdpAddress()))
			.thenReturn(new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS));
		
		Mockito.when(encodingSchemeA.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 4;
			return b;
		});

		Mockito.when(encodingSchemeB.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 4) ? new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}

	@Test
	public void testSecureReplicateWithOneKeyExchangeAndOnePreExchangedCertB() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		
		Key keyA = gen.generateKey();
		Key keyB = gen.generateKey();
		
		setupEncryption(encodingSchemeA, keyA);
		setupDecryption(encodingSchemeA);
		setupEncryption(encodingSchemeB, keyB);
		setupDecryption(encodingSchemeB);
		
		setupCert(encodingSchemeA, cert);
		setupCert(encodingSchemeB, cert);
		
		Mockito.when(gossipB.getInfoFor(IDA)).thenReturn(memberA);
		Mockito.when(socketCommsB.getCertificateFor(memberA.getUdpAddress())).thenReturn(cert);
		Mockito.when(socketCommsB.getEncryptionDetailsFor(memberA.getUdpAddress()))
			.thenReturn(new EncryptionDetails(keyA, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS));
		
		Mockito.when(encodingSchemeB.outgoingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.eq(cert))).thenAnswer(i -> {
			byte[] header = (byte[]) i.getArguments()[0];
			byte[] b = Arrays.copyOf(header, header.length + 1);
			b[header.length] = 3;
			return b;
		});
		
		Mockito.when(encodingSchemeA.incomingKeyExchangeMessage(Mockito.any(byte[].class), Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(i -> {
			return (((byte[])i.getArguments()[1])[2] == 3) ? new EncryptionDetails(keyB, "CBC/PKCS5Padding", 1, 100000, TimeUnit.SECONDS) : null;
		});
		
		testReplicate();
	}
}
