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
package org.eclipse.ot.rsa.tls.netty.provider.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Vector;

import io.netty.util.ResourceLeakDetector;

public class TestResourceLeakDetector<T> extends ResourceLeakDetector<T> {

	private static final List<String> leaks = new Vector<>();

	public TestResourceLeakDetector(Class<T> resourceType, int samplingInterval) {
		super(resourceType, samplingInterval);
	}

	public TestResourceLeakDetector(Class<T> resourceType, int samplingInterval, long l) {
		super(resourceType, samplingInterval);
	}

	@Override
	protected void reportTracedLeak(String resourceType, String records) {
		leaks.add("\nRecord:\n" + resourceType + "\n" + records + "\n");
		super.reportTracedLeak(resourceType, records);
	}

	@Override
	protected void reportUntracedLeak(String resourceType) {
		leaks.add("\nRecord:\n" + resourceType + "\n");
		super.reportUntracedLeak(resourceType);
	}

	public static void assertNoLeaks() {
		System.gc();
		assertTrue(leaks.isEmpty(), () -> leaks.toString());
		leaks.clear();
	}

}
