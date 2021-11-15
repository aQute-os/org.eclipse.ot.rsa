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

import java.security.PrivateKey;
import java.security.cert.Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

public class FibreCertificateInfo {

	private final Certificate certificate;
	private final PrivateKey signingKey;
	private KeyManagerFactory keyManagerFactory;
	private final TrustManagerFactory trustManagerFactory;
	
	public FibreCertificateInfo(Certificate certificate, PrivateKey signingKey, 
			KeyManagerFactory keyManagerFactory, TrustManagerFactory trustManagerFactory) {
		this.certificate = certificate;
		this.signingKey = signingKey;
		this.keyManagerFactory = keyManagerFactory;
		this.trustManagerFactory = trustManagerFactory;
	}

	public Certificate getCertificate() {
		return certificate;
	}

	public PrivateKey getSigningKey() {
		return signingKey;
	}
	
	public KeyManagerFactory getKeyManagerFactory() {
		return keyManagerFactory;
	}
	
	public TrustManagerFactory getTrustManagerFactory() {
		return trustManagerFactory;
	}
}
