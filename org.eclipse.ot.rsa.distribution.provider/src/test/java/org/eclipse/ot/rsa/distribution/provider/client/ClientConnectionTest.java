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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.eclipse.ot.rsa.distribution.provider.impl.ImportRegistrationImpl;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.java.JavaSerializer;
import org.eclipse.ot.rsa.distribution.provider.test.AbstractLeakCheckingTest;
import org.eclipse.ot.rsa.test.RSATestServer;
import org.eclipse.ot.rsa.tls.netty.provider.tls.NettyTLS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.util.converter.Converters;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Promise;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class ClientConnectionTest extends AbstractLeakCheckingTest {

	ClientConnectionManager		clientConnectionManager;

	@Mock
	NettyTLS					tls;
	@Mock
	ImportRegistrationImpl		ir;
	@Mock
	EndpointDescription			ed;
	@Mock
	Bundle						classSpace;

	EventLoopGroup				ioWorker;
	EventExecutorGroup			executor;

	Timer						timer;

	Supplier<Promise<Object>>	nettyPromiseSupplier;

	@BeforeEach
	public final void setUp() throws Exception {

		Map<String, Object> config = getConfig();

		Mockito.when(ed.getId())
			.thenReturn(new UUID(12, 34).toString());
		Mockito.when(ir.getId())
			.thenReturn(new UUID(12, 34));
		ioWorker = new NioEventLoopGroup(1);
		executor = new DefaultEventExecutorGroup(1);
		timer = new HashedWheelTimer();

		nettyPromiseSupplier = () -> executor.next()
			.newPromise();

		clientConnectionManager = new ClientConnectionManager(Converters.standardConverter()
			.convert(config)
			.to(TransportConfig.class), tls, PooledByteBufAllocator.DEFAULT, ioWorker, executor, timer);
	}

	@AfterEach
	public final void tearDown() throws IOException {
		clientConnectionManager.close();

		ioWorker.shutdownGracefully();
		executor.shutdownGracefully();
		timer.stop();
	}

	protected abstract Map<String, Object> getConfig();

	interface TestService {
		void noArgs();
	}

	class TestImpl implements TestService {
		@Override
		public void noArgs() {}
	}

	// @ParameterizedTest
	// @MethodSource("getServers")
	// public void testSimpleNoArgsCallVoidReturnTCP(RSATestServer test) throws
	// Exception {
	// TestService service = new TestImpl();
	// try (Reg<TestService> reg = test.exported(service, TestService.class)) {
	// Client mock = Mockito.mock(Client.class);
	// for (URI uri : reg.getEndpoints()) {
	// ImportRegistration importReg = test.imported(uri, reg.uuid);
	//
	// test.write(importReg, new ClientInvocation(true,
	// UUID.fromString(ed.getId()), 7, 0, null, new int[0],
	// new int[0], test., null, p, new AtomicLong(3000),
	// "testing"));
	//
	// assertNull(p.get());
	//
	// }
	// }
	// }

	static Stream<RSATestServer> getServers() {
		List<RSATestServer> servers = new ArrayList<>();
		Serializer javaSerializer = new JavaSerializer();
		Serializer vanilla = new VanillaRMISerializer();
		for (Serializer serializer : new Serializer[] {
			javaSerializer, vanilla
		}) {

			servers.add(getSimpleTCP(serializer));
		}

		return servers.stream();
	}

	static RSATestServer getSimpleTCP(Serializer serializer) {
		return new RSATestServer.Builder("test")//
			.set("allow.insecure.transports", true)
			.set("server.protocols", "TCP")
			.serializer(serializer)
			.tcp()
			.build();
	}
}
