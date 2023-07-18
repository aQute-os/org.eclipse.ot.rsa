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
package org.eclipse.ot.rsa.cluster.gossip.net;

import static io.netty.util.ResourceLeakDetector.Level.PARANOID;

import java.io.IOException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.LoggerFactory;

import io.netty.util.HashedWheelTimer;
import io.netty.util.ResourceLeakDetector;

public abstract class AbstractLeakCheckingTest {

	@BeforeEach
	public final void setupLeakChecking(TestInfo name) throws IOException {

		System.setProperty("io.netty.leakDetection.maxRecords", "100");
		System.setProperty("io.netty.customResourceLeakDetector", TestResourceLeakDetector.class.getName());
		TestResourceLeakDetector.addResourceTypeToIgnore(HashedWheelTimer.class);

		ResourceLeakDetector.setLevel(PARANOID);
		LoggerFactory.getLogger(getClass())
			.info("Beginning test {}", name.getTestMethod()
				.map(Method::getName)
				.orElse("No Method found"));
	}

	@AfterEach
	public final void leakCheck() throws IOException {
		TestResourceLeakDetector.assertNoLeaks();
		TestResourceLeakDetector.clearIgnoredResourceTypes();
	}
}
