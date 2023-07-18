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
package org.eclipse.ot.rsa.distribution.provider.server;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

public class SSLClientAuthServerConnectionManagerTest extends AbstractSSLServerConnectionManagerTest {

	@Override
	protected Map<String, Object> getExtraConfig() {
		Map<String, Object> toReturn = new HashMap<>();
		toReturn.put("allow.insecure.transports", true);
		toReturn.put("server.protocols", "TCP_CLIENT_AUTH");
		return toReturn;
	}

	@Override
	protected SSLEngine getConfiguredSSLEngine() throws NoSuchAlgorithmException, KeyManagementException {
		SSLContext context = SSLContext.getInstance("TLSv1.2");
		context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());

		SSLEngine sslEngine = context.createSSLEngine();
		sslEngine.setUseClientMode(true);
		return sslEngine;
	}

}
