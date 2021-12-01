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

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.security.cert.Certificate;

public class CertificateWrapper {

	private final Certificate certificate;
	
	private volatile long lastRetrieved = NANOSECONDS.toMillis(System.nanoTime()); 
	
	public CertificateWrapper(Certificate certificate) {
		this.certificate = certificate;
	}

	public long lastRetrieved() {
		return lastRetrieved;
	}
	
	public Certificate getCertificate() {
		lastRetrieved = NANOSECONDS.toMillis(System.nanoTime());
		return certificate;
	}

	public void touch() {
		lastRetrieved = NANOSECONDS.toMillis(System.nanoTime());
	}
	
}
