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

import static com.paremus.net.encode.impl.Config.ClientAuth.NEED;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.Key;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.KeyGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncodingSchemeFactory;

@ExtendWith(MockitoExtension.class)
public class EncodingSchemeFactoryTest {
	
	@Mock
	private Config config;
	
	@Test
	public void testNoSecurity() throws Exception {
		EncodingSchemeFactory factory = new EncodingSchemeFactoryImpl(config);
		
		EncodingScheme es = factory.createEncodingScheme();
		
		assertNull(es.getCertificate());
		
		assertFalse(es.dynamicKeyGenerationSupported());
		assertFalse(es.requiresCertificates());
		assertFalse(es.isConfidential());
		
		
		testSockets(es);
	}

	protected void testSockets(EncodingScheme es) throws InterruptedException, IOException {
		try (ServerSocket serverSocket = es.getServerSocketFactory().createServerSocket(0)) {
			AtomicInteger serverSaw = new AtomicInteger(-1);
			Thread t = new Thread(() -> {
					try {
						serverSocket.setSoTimeout(500);
						Socket s = serverSocket.accept();
						serverSaw.set(s.getInputStream().read());
					} catch(IOException ioe) {}
				});
			t.start();
			try (Socket clientEnd = es.getSocketFactory()
					.createSocket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
				
				clientEnd.getOutputStream().write(123);
				clientEnd.getOutputStream().flush();
				t.join();
				assertEquals(123, serverSaw.get());
			}
		}
	}

	@Test
	public void testWithSignatureConfig() throws Exception {
		
		Mockito.when(config.encryption_algorithm()).thenReturn("AES");
		Mockito.when(config.encryption_transform()).thenReturn("CBC/PKCS5Padding");
		Mockito.when(config.encryption_key_length()).thenReturn(128);

		Mockito.when(config.signature_keystore_type()).thenReturn("JKS");
		Mockito.when(config.signature_keystore()).thenReturn("test-resources/fabric.keystore");
		Mockito.when(config._signature_keystore_password()).thenReturn("paremus");
		Mockito.when(config.signature_key_alias()).thenReturn("servicefabricsamplecertificate");
		Mockito.when(config.encryption_key_expiry_unit()).thenReturn(TimeUnit.MINUTES);
		Mockito.when(config.encryption_key_expiry()).thenReturn(60L);
		
		Mockito.when(config.signature_truststore_type()).thenReturn("JKS");
		Mockito.when(config.signature_truststore()).thenReturn("test-resources/fabric.truststore");
		Mockito.when(config._signature_truststore_password()).thenReturn("paremus");
		
		Mockito.when(config.socket_protocols()).thenReturn(asList("TLSv1.2"));
		Mockito.when(config.socket_ciphers()).thenReturn(asList("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"));
		Mockito.when(config.socket_client_auth()).thenReturn(NEED);
		
		EncodingSchemeFactory factory = new EncodingSchemeFactoryImpl(config);
		
		EncodingScheme es = factory.createEncodingScheme();
		
		assertEquals("CN=any", ((X509Certificate)es.getCertificate()).getSubjectX500Principal().getName());
		
		assertTrue(es.dynamicKeyGenerationSupported());
		assertTrue(es.requiresCertificates());
		assertTrue(es.isConfidential());
		es.isAcceptable(es.getCertificate(), InetAddress.getLoopbackAddress());
		
		testSockets(es);
	}

	@Test
	public void testWithAESOnlyConfig() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		Key key = keyGen.generateKey();
		
		Mockito.when(config._encryption_key()).thenReturn(toHexString(key.getEncoded()));
		Mockito.when(config.encryption_algorithm()).thenReturn("AES");
		Mockito.when(config.encryption_transform()).thenReturn("CBC/PKCS5Padding");
		
		EncodingSchemeFactory factory = new EncodingSchemeFactoryImpl(config);
		
		EncodingScheme es = factory.createEncodingScheme();
		
		assertNull(es.getCertificate());
		
		assertFalse(es.dynamicKeyGenerationSupported());
		assertFalse(es.requiresCertificates());
		assertTrue(es.isConfidential());
		
		testSockets(es);
	}

	@Test
	public void testWithBlowfishOnly() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("Blowfish");
		Key key = keyGen.generateKey();
		
		Mockito.when(config._encryption_key()).thenReturn(toHexString(key.getEncoded()));
		Mockito.when(config.encryption_algorithm()).thenReturn("Blowfish");
		Mockito.when(config.encryption_transform()).thenReturn("CBC/PKCS5Padding");
		
		EncodingSchemeFactory factory = new EncodingSchemeFactoryImpl(config);
		
		EncodingScheme es = factory.createEncodingScheme();
		
		assertNull(es.getCertificate());
		
		assertFalse(es.dynamicKeyGenerationSupported());
		assertFalse(es.requiresCertificates());
		assertTrue(es.isConfidential());
		
		testSockets(es);
	}
	
	@Test
	public void testAESEncryptedWithRSASignature() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		Key key = keyGen.generateKey();

		Mockito.when(config._encryption_key()).thenReturn(toHexString(key.getEncoded()));
		Mockito.when(config.encryption_algorithm()).thenReturn("AES");
		Mockito.when(config.encryption_transform()).thenReturn("CBC/PKCS5Padding");
		
		Mockito.when(config.signature_keystore_type()).thenReturn("JKS");
		Mockito.when(config.signature_keystore()).thenReturn("test-resources/fabric.keystore");
		Mockito.when(config._signature_keystore_password()).thenReturn("paremus");
		Mockito.when(config.signature_key_alias()).thenReturn("servicefabricsamplecertificate");
		
		Mockito.when(config.signature_truststore_type()).thenReturn("JKS");
		Mockito.when(config.signature_truststore()).thenReturn("test-resources/fabric.truststore");
		Mockito.when(config._signature_truststore_password()).thenReturn("paremus");
		
		Mockito.when(config.socket_protocols()).thenReturn(asList("TLSv1.2"));
		Mockito.when(config.socket_ciphers()).thenReturn(asList("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"));
		Mockito.when(config.socket_client_auth()).thenReturn(NEED);
		
		EncodingSchemeFactory factory = new EncodingSchemeFactoryImpl(config);
		
		EncodingScheme es = factory.createEncodingScheme();
		
		assertEquals("CN=any", ((X509Certificate)es.getCertificate()).getSubjectX500Principal().getName());
		
		assertFalse(es.dynamicKeyGenerationSupported());
		assertTrue(es.requiresCertificates());
		assertTrue(es.isConfidential());
		es.isAcceptable(es.getCertificate(), InetAddress.getLoopbackAddress());
		
		testSockets(es);
	}

	private String toHexString(byte[] encoded) {
		StringBuilder sb = new StringBuilder();
		for(byte b : encoded) {
			sb.append(Integer.toHexString((b & 0xF0) >> 4));
			sb.append(Integer.toHexString((b & 0xF)));
		}
		return sb.toString();
	}

}
