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
package com.paremus.net.encode.impl;

import static java.util.Arrays.copyOfRange;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import javax.crypto.KeyGenerator;

import org.junit.jupiter.api.Test;
import org.osgi.service.cm.ConfigurationException;

import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncryptionDetails;
import com.paremus.net.encode.impl.EncodingSchemeImpl.EncodingType;

public class EncodingSchemeTest {
	
	private static final String DEFAULT_TRANSFORM = "CBC/PKCS5Padding";
	
	@Test
	public void testSimpleEncoding() throws ConfigurationException {
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		EncodingScheme encoder = new EncodingSchemeImpl(null, null, null, null,  () -> {}, "HmacSHA256", null, null);
		
		byte[] encoded = encoder.encode(header, raw, 0, raw.length);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.PLAIN.ordinal(), encoded[3]);
		
		assertTrue(Arrays.equals(raw, copyOfRange(encoded, 4, encoded.length)), "Mangled the bytes " + Arrays.toString(encoded));
	}

	@Test
	public void testSimpleEncodingOffset() throws ConfigurationException {
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		EncodingScheme encoder = new EncodingSchemeImpl(null, null, null, null, () -> {}, "HmacSHA256", null, null);
		
		byte[] encoded = encoder.encode(header, raw, 1, raw.length - 1);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.PLAIN.ordinal(), encoded[3]);
		
		assertTrue(Arrays.equals(copyOfRange(raw, 1, raw.length), copyOfRange(encoded, 4, encoded.length)), "Mangled the bytes " + Arrays.toString(encoded));
	}

	@Test
	public void testSignedEncodingOffset() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Key key = KeyGenerator.getInstance("AES").generateKey();
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		byte[] encoded = encoder.encode(header, raw, 1, raw.length - 1);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.ENCRYPTED.ordinal(), encoded[3]);
		
		byte[] keyExchange = encoder.outgoingKeyExchangeMessage(new byte[0], cert);
		
		DataInput input =  encoder.validateAndDecode(header, encoded, 2, encoded.length - 2, 
				encoder.incomingKeyExchangeMessage(new byte[0], keyExchange, 0, keyExchange.length));
		
		byte[] roundTrip = new byte[raw.length - 1];
		input.readFully(roundTrip);
		
		assertTrue(Arrays.equals(copyOfRange(raw, 1, raw.length), roundTrip), "Mangled the bytes " + Arrays.toString(encoded));
	}

	@Test
	public void testAESEncryptedEncoding() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		Key key = keyGen.generateKey();
		
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		EncodingScheme encoder = new EncodingSchemeImpl(null,
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		byte[] encoded = encoder.encode(header, raw, 0, raw.length);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.ENCRYPTED.ordinal(), encoded[3]);
		
		
		DataInput input =  encoder.validateAndDecode(header, encoded, 2, encoded.length - 2, 
				new EncryptionDetails(key, "AES/" + DEFAULT_TRANSFORM, 1, -1, MILLISECONDS));
		
		byte[] roundTrip = new byte[raw.length];
		input.readFully(roundTrip);
		
		assertTrue(Arrays.equals(raw, roundTrip), "Mangled the bytes " + Arrays.toString(roundTrip));
	}

	@Test
	public void testAESEncryptedEncodingOffset() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		Key key = keyGen.generateKey();

		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		EncodingScheme encoder = new EncodingSchemeImpl(null,
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		byte[] encoded = encoder.encode(header, raw, 1, raw.length -1);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.ENCRYPTED.ordinal(), encoded[3]);
		
		DataInput input =  encoder.validateAndDecode(header, encoded, 2, encoded.length - 2, 
				new EncryptionDetails(key, "AES/" + DEFAULT_TRANSFORM, 1, -1, MILLISECONDS));
		
		byte[] roundTrip = new byte[raw.length -1];
		input.readFully(roundTrip);
		
		assertTrue(Arrays.equals(copyOfRange(raw, 1, raw.length), roundTrip), "Mangled the bytes " + Arrays.toString(roundTrip));
	}
	
	@Test
	public void testBlowfishEncryptedEncoding() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("Blowfish");
		Key key = keyGen.generateKey();
		
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		EncodingScheme encoder = new EncodingSchemeImpl(null,
				new EncryptionDetails(key, "Blowfish/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		byte[] encoded = encoder.encode(header, raw, 0, raw.length);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.ENCRYPTED.ordinal(), encoded[3]);
		
		DataInput input =  encoder.validateAndDecode(header, encoded, 2, encoded.length - 2, 
				new EncryptionDetails(key, "Blowfish/" + DEFAULT_TRANSFORM, 1, -1, MILLISECONDS));
		
		byte[] roundTrip = new byte[raw.length];
		input.readFully(roundTrip);
		
		assertTrue(Arrays.equals(raw, roundTrip), "Mangled the bytes " + Arrays.toString(roundTrip));
	}
	
	@Test
	public void testBlowfishEncryptedEncodingOffset() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("Blowfish");
		Key key = keyGen.generateKey();
		
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		EncodingScheme encoder = new EncodingSchemeImpl(null,
				new EncryptionDetails(key, "Blowfish/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		byte[] encoded = encoder.encode(header, raw, 1, raw.length -1);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.ENCRYPTED.ordinal(), encoded[3]);
		
		DataInput input =  encoder.validateAndDecode(header, encoded, 2, encoded.length - 2, 
				new EncryptionDetails(key, "Blowfish/" + DEFAULT_TRANSFORM, 1, -1, MILLISECONDS));
		
		byte[] roundTrip = new byte[raw.length -1];
		input.readFully(roundTrip);
		
		assertTrue(Arrays.equals(copyOfRange(raw, 1, raw.length), roundTrip), "Mangled the bytes " + Arrays.toString(roundTrip));
	}
	
	@Test
	public void testAESEncryptedWithRSASignature() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Key key = KeyGenerator.getInstance("AES").generateKey();
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		byte[] encoded = encoder.encode(header, raw, 0, raw.length);
		
		assertEquals(0xa, encoded[0]);
		assertEquals(0xb, encoded[1]);
		assertEquals(1, encoded[2]);
		assertEquals(EncodingType.ENCRYPTED.ordinal(), encoded[3]);
		
		byte[] keyExchange = encoder.outgoingKeyExchangeMessage(new byte[0], cert);
		
		DataInput input =  encoder.validateAndDecode(header, encoded, 2, encoded.length - 2, 
				encoder.incomingKeyExchangeMessage(new byte[0], keyExchange, 0, keyExchange.length));
		
		byte[] roundTrip = new byte[raw.length];
		input.readFully(roundTrip);
		
		assertTrue(Arrays.equals(raw, roundTrip), "Mangled the bytes " + Arrays.toString(roundTrip));
	}
	
	@Test
	public void testAESAndRSASignatureEncryptingStream() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Key key = KeyGenerator.getInstance("AES").generateKey();
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		byte[] bytes;
		try (OutputStream output = 
				encoder.encryptingStream(baos)) {
		
			long seed = System.currentTimeMillis();
			
			System.out.println("Using seed: " + seed);
			Random r = new Random(seed);
			
			bytes = new byte[5000 + r.nextInt(16384)];
			r.nextBytes(bytes);
			
			output.write(bytes);
		}
		
		try (DataInputStream dis = new DataInputStream(encoder.decryptingStream(
				new ByteArrayInputStream(baos.toByteArray()), 
				new EncryptionDetails(key, "AES/" + DEFAULT_TRANSFORM, 1, -1, MILLISECONDS)))) {
			byte[] roundTrip = new byte[bytes.length];
			dis.readFully(roundTrip, 0, roundTrip.length);
			assertEquals(-1, dis.read());
			assertTrue(Arrays.equals(bytes, roundTrip), "Mangled the bytes " + Arrays.toString(roundTrip));
		}
	}

	@Test
	public void testAESAndRSASignatureEncryptingStreamWithFlush() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Key key = KeyGenerator.getInstance("AES").generateKey();
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		byte[] firstBytes;
		byte[] secondBytes;

		try (OutputStream output = 
				encoder.encryptingStream(baos)) {
			
			long seed = System.currentTimeMillis();
			
			System.out.println("Using seed: " + seed);
			Random r = new Random(seed);
			
			firstBytes = new byte[1 + r.nextInt(50)];
			r.nextBytes(firstBytes);
			secondBytes = new byte[1 + r.nextInt(50)];
			r.nextBytes(secondBytes);
			
			output.write(firstBytes);
			encoder.forceFlush(output);
			output.write(secondBytes);
		}
		
		try (DataInputStream dis = encoder.decryptingStream(
				new ByteArrayInputStream(baos.toByteArray()), 
				new EncryptionDetails(key, "AES/" + DEFAULT_TRANSFORM, 1, -1, MILLISECONDS))) {
			byte[] roundTrip = new byte[firstBytes.length];
			dis.readFully(roundTrip, 0, roundTrip.length);
			
			assertTrue(Arrays.equals(firstBytes, roundTrip), "Mangled the bytes " + Arrays.toString(roundTrip));
			
			encoder.skipForcedFlush(dis);
			
			roundTrip = new byte[secondBytes.length];
			dis.readFully(roundTrip, 0, roundTrip.length);
			
			assertTrue(Arrays.equals(secondBytes, roundTrip), "Mangled the second bytes " + Arrays.toString(roundTrip));
			
			assertEquals(-1, dis.read());
		}
	}
	
	@Test
	public void testKeyExchange() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Supplier<EncryptionDetails> keyGen = () -> {
			try {
				return new EncryptionDetails(KeyGenerator.getInstance("AES").generateKey(), "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS);
			} catch (NoSuchAlgorithmException nsae) {
				throw new RuntimeException(nsae);
			}
		};
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				keyGen.get(), new SecureRandom(), keyGen, () -> {}, "HmacSHA256", null, null);
		
		assertTrue(encoder.dynamicKeyGenerationSupported());
		byte [] message = encoder.outgoingKeyExchangeMessage(new byte[] {1, 2, 3}, cert);
		
		EncryptionDetails ed = encoder.incomingKeyExchangeMessage(new byte[] {1, 2, 3}, message, 3, message.length - 3);
		
		message = encoder.encode(new byte[] {2, 3, 4}, new byte[] {3, 4, 5}, 0, 3);

		DataInput di = encoder.validateAndDecode(new byte[] {2, 3, 4}, message, 3, message.length -3, ed);
		
		assertEquals(3, di.readByte());
		assertEquals(4, di.readByte());
		assertEquals(5, di.readByte());
		
	}

	@Test
	public void testKeyUpdateNotification() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Supplier<EncryptionDetails> keyGen = () -> {
			try {
				return new EncryptionDetails(KeyGenerator.getInstance("AES").generateKey(), "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS);
			} catch (NoSuchAlgorithmException nsae) {
				throw new RuntimeException(nsae);
			}
		};
		
		Semaphore s = new Semaphore(0);
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				keyGen.get(), new SecureRandom(), keyGen, () -> s.release(1), "HmacSHA256", null, null);
		
		assertTrue(encoder.dynamicKeyGenerationSupported());
		
		encoder.regenerateKey();
		
		assertTrue(s.tryAcquire(1, 500, MILLISECONDS));
	}

	@Test
	public void testKeyUpdateNotificationTimeout() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Supplier<EncryptionDetails> keyGen = () -> {
			try {
				return new EncryptionDetails(KeyGenerator.getInstance("AES").generateKey(), "AES/CBC/PKCS5Padding", 1, 100, MILLISECONDS);
			} catch (NoSuchAlgorithmException nsae) {
				throw new RuntimeException(nsae);
			}
		};
		
		Semaphore s = new Semaphore(0);
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				keyGen.get(), new SecureRandom(), keyGen, () -> s.release(1), "HmacSHA256", null, null);
		
		assertTrue(encoder.dynamicKeyGenerationSupported());
		
		assertFalse(s.tryAcquire(1, 500, MILLISECONDS));
		
		encoder.encode(new byte[] {2, 3, 4}, new byte[] {3, 4, 5}, 0, 3);
		
		assertTrue(s.tryAcquire(1, 500, MILLISECONDS));
	}
	
	@Test
	public void testSignNoCert() throws ConfigurationException {
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		EncodingScheme encoder = new EncodingSchemeImpl(null, null, null, null,  () -> {}, "HmacSHA256", null, null);
		try {
			encoder.sign(raw);
			fail("No cert so should IllegalStateException");
		} catch (IllegalStateException ise) {}
	}

	@Test
	public void testSignWithCert() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Key key = KeyGenerator.getInstance("AES").generateKey();
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				() -> {}, "HmacSHA256", null, null);
		
		byte[] raw = new byte[] {0x1a, 0x2b, 0x3c, 0x4d};
		
		byte[] signature = encoder.sign(raw);
		
		assertNotNull(signature);
		assertTrue(encoder.verifySignature(raw, signature, cert));
		
		raw[0] = 0x5e;
		assertFalse(encoder.verifySignature(raw, signature, cert));
	}
	
}
