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
package org.eclipse.ot.rsa.distribution.provider.client;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

public class ClientConnectionManagerTest extends AbstractClientConnectionManagerTest {

	@Override
	protected Map<String, Object> getConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put("client.protocols", "TCP");
		config.put("allow.insecure.transports", true);
		return config;
	}

	@Override
	protected String getPrefix() {
		return "ptcp://127.0.0.1:";
	}

	@Override
	protected ServerSocket getConfiguredSocket() throws Exception {
		return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
	}
}
