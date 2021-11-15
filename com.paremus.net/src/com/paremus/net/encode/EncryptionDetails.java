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
package com.paremus.net.encode;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.security.Key;
import java.util.concurrent.TimeUnit;

public final class EncryptionDetails {
	
	private final Key key;
	
	private final String transform;

	private final int keyGenerationCounter;

	private final long expiryTimeNanos;

	private final boolean permanent;
	
	public EncryptionDetails(Key key, String transform, int keyGenerationCounter, long timeToLive, TimeUnit unit) {
		this.key = key;
		this.transform = transform;
		this.keyGenerationCounter = keyGenerationCounter;
		this.permanent = timeToLive < 0;
		//Use nanos because we should be unaffected by clock changes after the fact
		this.expiryTimeNanos = System.nanoTime() + unit.toNanos(timeToLive);
	}
	
	public Key getKey() {
		return key;
	}

	public String getTransform() {
		return transform;
	}

	public int getKeyGenerationCounter() {
		return keyGenerationCounter;
	}

	public boolean hasExpired() {
		return permanent ? false : (expiryTimeNanos - System.nanoTime()) <= 0;
	}
	
	public long getRemainingTime(TimeUnit unit) {
		if(permanent) return -1;
		
		long nanosRemaining = (expiryTimeNanos - System.nanoTime());
		return Math.max(0, unit.convert(nanosRemaining, NANOSECONDS));
	}
}
