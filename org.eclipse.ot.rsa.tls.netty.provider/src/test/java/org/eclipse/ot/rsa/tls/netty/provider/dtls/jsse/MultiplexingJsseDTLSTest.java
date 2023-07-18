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
package org.eclipse.ot.rsa.tls.netty.provider.dtls.jsse;

import java.security.SecureRandom;
import java.util.function.Supplier;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.ot.rsa.tls.netty.provider.dtls.adapter.DtlsEngine;
import org.eclipse.ot.rsa.tls.netty.provider.dtls.adapter.JdkDtlsEngineAdapter;
import org.eclipse.ot.rsa.tls.netty.provider.test.AbstractMultiplexingDTLSTest;
import org.junit.jupiter.api.Disabled;

import io.netty.channel.ChannelHandler;

@Disabled
public class MultiplexingJsseDTLSTest extends AbstractMultiplexingDTLSTest {

	@Override
	protected ChannelHandler getMultiplexingHandler(KeyManagerFactory kmf, TrustManagerFactory tmf,
		SSLParameters parameters) throws Exception {

		SSLContext instance = SSLContext.getInstance("DTLSv1.2");

		instance.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

		Supplier<DtlsEngine> sslEngineSupplier = () -> {
			SSLEngine engine = instance.createSSLEngine();
			engine.setSSLParameters(parameters);
			return new JdkDtlsEngineAdapter(engine);
		};
		return new ParemusDTLSHandler(sslEngineSupplier);
	}
}
