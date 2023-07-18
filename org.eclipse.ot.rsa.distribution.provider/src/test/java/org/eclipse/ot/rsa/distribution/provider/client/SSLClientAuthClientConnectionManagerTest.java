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

import javax.net.ssl.SSLServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

public class SSLClientAuthClientConnectionManagerTest extends AbstractSSLClientConnectionManagerTest {

	@BeforeEach
	public final void setUpClientAuth() throws Exception {
		Mockito.when(tls.hasCertificate())
			.thenReturn(true);
	}

	@Override
	protected Map<String, Object> getConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put("client.protocols", "TCP_CLIENT_AUTH");
		config.put("allow.insecure.transports", false);
		return config;
	}

	@Override
	protected String getPrefix() {
		return "ptcpca://127.0.0.1:";
	}

	@Override
	protected ServerSocket getConfiguredSocket() throws Exception {
		SslContext sslContext;
		try {
			sslContext = SslContextBuilder.forServer(keyManagerFactory)
				.trustManager(trustManagerFactory)
				.build();
		} catch (Exception e) {
			throw new RuntimeException("Unable to create the SSL Engine", e);
		}
		ServerSocket socket = ((JdkSslContext) sslContext).context()
			.getServerSocketFactory()
			.createServerSocket(0, 1, InetAddress.getLoopbackAddress());

		((SSLServerSocket) socket).setNeedClientAuth(true);
		((SSLServerSocket) socket).setWantClientAuth(true);

		return socket;
	}
}
