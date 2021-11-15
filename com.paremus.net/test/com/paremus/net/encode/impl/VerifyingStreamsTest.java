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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class VerifyingStreamsTest {
	
	Mac mac;
	
	@BeforeEach
	public void setUp() throws Exception {
		mac = Mac.getInstance("HmacSHA256");
		
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		mac.init(kg.generateKey());
	}

	@Test
	public void testIdentityTransform() throws IOException {
		long seed = System.currentTimeMillis();
		
		System.out.println("Using seed: " + seed);
		Random r = new Random(seed);
		
		byte[] bytes = new byte[(1 << 25) + r.nextInt(33554432)];
		
		System.out.println("Using an array size of " + (bytes.length >> 10) + "kb");
		
		r.nextBytes(bytes);
		
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (VerifiableOutputStream vos = new VerifiableOutputStream(baos, mac)) {
			long start = System.nanoTime();
			vos.write(bytes);
			System.out.println("Encode Took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
		};
		
		try (DataInputStream dis = new DataInputStream(
				new VerifyingInputStream(new ByteArrayInputStream(baos.toByteArray()), mac))) {
			byte[] roundTripped = new byte[bytes.length];
			
			long start = System.nanoTime();
			dis.readFully(roundTripped);
			System.out.println("Decode Took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
			
			assertTrue(Arrays.equals(bytes, roundTripped), "Not equal");
			assertEquals(-1, dis.read());
		}
	}
}
