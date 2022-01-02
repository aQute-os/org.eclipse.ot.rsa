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
import java.security.SecureRandom;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import io.netty.handler.ssl.SslHandler;

public abstract class AbstractSSLClientConnectionManagerTest extends AbstractClientConnectionManagerTest {

	protected KeyManagerFactory keyManagerFactory;
	protected TrustManagerFactory trustManagerFactory;

	@BeforeEach
	public final void setUpSSL() throws Exception {
		keyManagerFactory = KeyManagerFactory
				.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		
		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(new FileInputStream("test-resources/fabric.keystore"), "paremus".toCharArray());
		keyManagerFactory.init(ks, "paremus".toCharArray());
		
		trustManagerFactory = TrustManagerFactory
				.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		
		ks = KeyStore.getInstance("JKS");
		ks.load(new FileInputStream("test-resources/fabric.truststore"), "paremus".toCharArray());
		trustManagerFactory.init(ks);

		SSLContext instance = SSLContext.getInstance("TLSv1.2");
		instance.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());
		
		instance.createSSLEngine();
		
		Mockito.when(tls.hasTrust()).thenReturn(true);
		Mockito.when(tls.getTLSClientHandler()).then(i -> {
				SSLEngine engine = instance.createSSLEngine();
				engine.setUseClientMode(true);
				return new SslHandler(engine);
			});
	}
}
