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


import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.DataInput;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Random;

import javax.crypto.KeyGenerator;

import org.junit.jupiter.api.Test;

import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncryptionDetails;

public class EncodingSchemePerformanceTest {
	
	@Test
	public void testSimpleEncoding() throws Exception {
		EncodingScheme encoder = new EncodingSchemeImpl(null, null, null, null, () -> {}, "HmacSHA256", null, null);
		
		runEncodingTest(encoder, 16384, 1000, 0);
	}

	@Test
	public void testSimpleDecoding() throws Exception {
		EncodingScheme encoder = new EncodingSchemeImpl(null, null, null, null,  () -> {}, "HmacSHA256", null, null);
		
		runDecodingTest(encoder, 16384, 1000, 0, null);
	}
	
	@Test
	public void testEncodeRSASignature() throws Exception {
		
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Key key = KeyGenerator.getInstance("AES").generateKey();
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null,
				 () -> {}, "HmacSHA256", null, null);
		
		long start = System.currentTimeMillis();
		runEncodingTest(encoder, 16384, 100, 0);
		
		System.out.println("Took " + (System.currentTimeMillis() - start) + " millis");
	}
	
	@Test
	public void testDecodeRSASignature() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		keyStore.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		Certificate cert = keyStore.getCertificate("servicefabricsamplecertificate");
		PrivateKey signingKey = (PrivateKey) keyStore.getKey("servicefabricsamplecertificate", "paremus".toCharArray());
		
		Key key = KeyGenerator.getInstance("AES").generateKey();
		
		EncodingScheme encoder = new EncodingSchemeImpl(new FibreCertificateInfo(cert, signingKey, null, null),
				new EncryptionDetails(key, "AES/CBC/PKCS5Padding", 1, -1, MILLISECONDS), new SecureRandom(), null, 
				 () -> {}, "HmacSHA256", null, null);
		
		runDecodingTest(encoder, 16384, 100, 0, cert);
	}

	private void runEncodingTest(EncodingScheme encoder, int messages, int repeat, int delay) {
		byte[] header = new byte[] {0x0a, 0x0b};
		byte[][] raw = new byte[messages][];
		
		Random r = new Random();
		System.out.println("Generating data");
		for(int i = 0; i < raw.length; i++) {
			byte[] bytes = new byte[(32 + r.nextInt(16)) * (r.nextInt(5) + 1)];
			r.nextBytes(bytes);
			raw[i] = bytes;
		}
		
		System.out.println("Generated data");
		
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		System.out.println("Starting");
		
		
		for(int i = 0; i < repeat; i ++) {
			for(byte[] bytes : raw) {
				byte[] encoded = encoder.encode(header, bytes, 0, bytes.length);
				i += encoded.length;
				i -= encoded.length;
			}
		}
	}

	private void runDecodingTest(EncodingScheme encoder, int messages, int repeat, int delay, Certificate cert) throws IOException {
		byte[] header = new byte[] {};
		byte[][] raw = new byte[messages][];
		
		Random r = new Random();
		System.out.println("Generating data");
		for(int i = 0; i < raw.length; i++) {
			byte[] bytes = new byte[(32 + r.nextInt(16)) * (r.nextInt(5) + 1)];
			r.nextBytes(bytes);
			raw[i] = encoder.encode(header, bytes, 0, bytes.length);
		}
		
		System.out.println("Generated data");
		
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		System.out.println("Starting");
		
		EncryptionDetails ed = null;
		if(cert != null) {
			byte[] keyExchange = encoder.outgoingKeyExchangeMessage(new byte[0], cert);
			ed = cert == null ? null : encoder.incomingKeyExchangeMessage(new byte[0], 
				keyExchange, 0, keyExchange.length);
		}
		
		for(int i = 0; i < repeat; i ++) {
			for(byte[] bytes : raw) {
				DataInput di = encoder.validateAndDecode(header, bytes, 0, bytes.length, ed);
				di.readLong();
			}
		}
	}
}
