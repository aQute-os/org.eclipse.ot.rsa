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
package com.paremus.dosgi.net.client;

import java.io.FileInputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

public abstract class AbstractSSLClientConnectionManagerTest extends AbstractClientConnectionManagerTest {

	protected KeyManagerFactory keyManagerFactory;
	protected TrustManagerFactory trustManagerFactory;

	@BeforeEach
	public void setUp() throws Exception {
		super.setUp();
		
		keyManagerFactory = KeyManagerFactory
				.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		
		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		keyManagerFactory.init(ks, "paremus".toCharArray());
		
		Mockito.when(esf.getSSLKeyManagerFactory()).thenReturn(keyManagerFactory);

		trustManagerFactory = TrustManagerFactory
				.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		
		ks = KeyStore.getInstance("JKS");
		ks.load(new FileInputStream("test-resources/fabric.truststore"), "paremus".toCharArray());
		trustManagerFactory.init(ks);
		
		Mockito.when(esf.getSSLTrustManagerFactory()).thenReturn(trustManagerFactory);
	}
}
