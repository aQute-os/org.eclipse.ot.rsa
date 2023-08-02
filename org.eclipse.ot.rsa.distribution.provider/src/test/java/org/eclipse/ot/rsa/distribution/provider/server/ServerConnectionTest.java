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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.java.JavaSerializer;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Client;
import org.eclipse.ot.rsa.distribution.provider.wireformat.RSAChannel;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Server;
import org.eclipse.ot.rsa.distribution.util.ClassSpace;
import org.eclipse.ot.rsa.distribution.util.ClassSpace.ActualTypeName;
import org.eclipse.ot.rsa.distribution.util.ClassSpace.Proxied;
import org.eclipse.ot.rsa.test.RSATestServer;
import org.eclipse.ot.rsa.test.RSATestServer.Reg;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ServerConnectionTest {
	final static PushStreamProvider provider = new PushStreamProvider();

	interface HardCases {
		void fail();

		void unserializableFailure();

		Test unserializable();
	}

	String text = "Hello World";

	class Test implements CharSequence, HardCases {

		@Override
		public int length() {
			return text.length();
		}

		@Override
		public char charAt(int index) {
			return text.charAt(index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return text.subSequence(start, end);
		}

		@Override
		public void fail() {
			throw new RuntimeException("fail");
		}

		@Override
		public Test unserializable() {
			return this;
		}

		@Override
		public void unserializableFailure() {
			class Exc extends RuntimeException {
				private static final long	serialVersionUID	= 1L;
				@SuppressWarnings("unused")
				Test						test				= Test.this;
			}
			throw new Exc();
		}

	}

	@ParameterizedTest
	@MethodSource("getServers")
	public void testNormalCalls(RSATestServer test) throws Exception {
		Test service = new Test();
		try (Reg<CharSequence> reg = test.exported(service, CharSequence.class, HardCases.class)) {
			Client mock = Mockito.mock(Client.class);
			for (URI uri : reg.getEndpoints()) {
				try (RSAChannel channel = test.getChannel(uri)) {
					Server server = channel.server(mock);

					server.callWithReturn(reg.uuid, 789, reg.getIndex("length"));
					server.callWithReturn(reg.uuid, 791, reg.getIndex("subSequence", int.class, int.class), 1, 10);
					server.callWithReturn(reg.uuid, 790, reg.getIndex("charAt", int.class), 5);

					server.callWithReturn(reg.uuid, 800, reg.getIndex("subSequence", int.class, int.class), -1, 10);

					server.callWithReturn(reg.uuid, 792, 9999);

					server.callWithReturn(reg.uuid, 2000, reg.getIndex("unserializable"));
					server.callWithReturn(reg.uuid, 3000, reg.getIndex("unserializableFailure"));

					UUID nonExistent = UUID.randomUUID();
					server.callWithReturn(nonExistent, 794, 0);

					ByteBuf b = Unpooled.copiedBuffer(new byte[] {
						42
					});
					server.callWithReturn(reg.uuid, 900, reg.getIndex("charAt", int.class), b);

					server.callWithReturn(reg.uuid, 1000, reg.getIndex("length"));

					verify(mock, timeout(5_000)).failureResponse(eq(reg.uuid), eq(800),
						any(StringIndexOutOfBoundsException.class));
					verify(mock, timeout(5_000)).successResponse(reg.uuid, 789, text.length());
					verify(mock, timeout(5_000)).successResponse(reg.uuid, 1000, text.length());
					verify(mock, timeout(5_000)).successResponse(reg.uuid, 790, text.charAt(5));
					verify(mock, timeout(5_000)).successResponse(reg.uuid, 791, text.subSequence(1, 10));
					verify(mock, timeout(5_000)).failureNoMethod(reg.uuid, 792);
					verify(mock, timeout(5_000)).failureNoService(nonExistent, 794);
					verify(mock, timeout(5_000)).failureToDeserialize(eq(reg.uuid), eq(900), any(String.class));
					verify(mock, timeout(5_000)).failureToSerializeSuccess(eq(reg.uuid), eq(2000), any(String.class));
					verify(mock, timeout(5_000)).failureToSerializeFailure(eq(reg.uuid), eq(3000), any(String.class));

				}
			}
		}

	}

	@ParameterizedTest
	@MethodSource("getServers")
	public void testFireAndForget(RSATestServer test) throws Exception {
		Test service = new Test();
		try (Reg<CharSequence> reg = test.exported(service, CharSequence.class, HardCases.class)) {
			Client mock = Mockito.mock(Client.class);
			for (URI uri : reg.getEndpoints()) {
				try (RSAChannel channel = test.getChannel(uri)) {
					Server server = channel.server(mock);

					server.callWithoutReturn(reg.uuid, 789, reg.getIndex("length"));
					verify(reg.getService(), timeout(1000)).length();

					server.callWithoutReturn(reg.uuid, 800, reg.getIndex("subSequence", int.class, int.class), 3, 4);
					verify(reg.getService(), timeout(1000)).subSequence(3, 4);

					server.callWithoutReturn(reg.uuid, 800, reg.getIndex("subSequence", int.class, int.class), -3, 4);
					verify(reg.getService(), timeout(1000)).subSequence(3, 4);

					verifyNoInteractions(mock);
				}
			}
		}

	}

	@ParameterizedTest
	@MethodSource("getServers")
	public void testPromiseDefaultClassSpace(RSATestServer test) throws Exception {
		ClassSpace cs = new ClassSpace(getClass());
		assertThat(cs.loadClass("org.osgi.util.promise.Promise")).isEqualTo(Promise.class);
		testPromise(cs, test);
	}

	@ParameterizedTest
	@MethodSource("getServers")
	public void testPromise_1_1(RSATestServer test) throws Exception {
		ClassLoader l = null;
		ClassSpace cs = new ClassSpace( //
			l, "target/test-classes", //
			"test-resources/promises/1.1.0/org.osgi.util.function-1.1.0.jar", //
			"test-resources/promises/1.1.0/org.osgi.util.promise-1.1.0.jar" //
		);
		assertThat(cs.loadClass("org.osgi.util.promise.Promise")).isNotEqualTo(Promise.class);
		testPromise(cs, test);
	}

	@ParameterizedTest
	@MethodSource("getServers")
	public void testPromise_1_1_1(RSATestServer test) throws Exception {
		ClassLoader l = null;
		ClassSpace cs = new ClassSpace( //
			l, "target/test-classes", //
			"test-resources/promises/1.1.0/org.osgi.util.function-1.1.0.jar", //
			"test-resources/promises/1.1.1/org.osgi.util.promise-1.1.1.jar" //
		);
		assertThat(cs.loadClass("org.osgi.util.promise.Promise")).isNotEqualTo(Promise.class);
		testPromise(cs, test);
	}

	@ParameterizedTest
	@MethodSource("getServers")
	public void testPromise_1_2_0(RSATestServer test) throws Exception {
		ClassLoader l = null;
		ClassSpace cs = new ClassSpace( //
			l, "target/test-classes", //
			"test-resources/promises/1.2.0/org.osgi.util.function-1.2.0.jar", //
			"test-resources/promises/1.2.0/org.osgi.util.promise-1.2.0.jar" //
		);
		assertThat(cs.loadClass("org.osgi.util.promise.Promise")).isNotEqualTo(Promise.class);
		testPromise(cs, test);
	}

	public interface PromiseService {
		Promise<Integer> unresolved();

		Promise<Integer> unresolvedFailure();

		Promise<Integer> failed();

		Promise<Integer> resolved();
	}

	public static class PromiseServiceImpl implements PromiseService {
		final Deferred<Integer>	success	= new Deferred<>();
		final Deferred<Integer>	failure	= new Deferred<>();

		@Override
		public Promise<Integer> unresolved() {
			return success.getPromise();
		}

		@Override
		public Promise<Integer> unresolvedFailure() {
			return failure.getPromise();
		}

		@Override
		public Promise<Integer> resolved() {
			return Promises.resolved(42);
		}

		@Override
		public Promise<Integer> failed() {
			return Promises.failed(new RuntimeException());
		}

		public void success() {
			success.resolve(4711);
		}

		public void failure() {
			failure.fail(new RuntimeException());
		}
	}

	@ActualTypeName("org.eclipse.ot.rsa.distribution.provider.server.ServerConnectionTest$PromiseServiceImpl")
	interface ProxyToControlTheServiceImpl extends Proxied {
		void success();

		void failure();
	}

	@SuppressWarnings({
		"rawtypes", "unchecked"
	})
	void testPromise(ClassSpace cs, RSATestServer test) throws Exception {

		Class promiseService = cs.loadClass(PromiseService.class.getName());
		Class promiseServiceImpl = cs.loadClass(PromiseServiceImpl.class.getName());
		Object service = promiseServiceImpl.newInstance();

		ProxyToControlTheServiceImpl proxy = cs.proxy(ProxyToControlTheServiceImpl.class, service);

		try (Reg<?> reg = test.exported(service, promiseService)) {
			Client client = Mockito.mock(Client.class);
			for (URI uri : reg.getEndpoints()) {
				try (RSAChannel channel = test.getChannel(uri)) {
					Server server = channel.server(client);

					server.callWithReturn(reg.uuid, 3000, reg.getIndex("unresolved"));
					server.callWithReturn(reg.uuid, 4000, reg.getIndex("unresolvedFailure"));
					server.callWithReturn(reg.uuid, 1000, reg.getIndex("resolved"));
					server.callWithReturn(reg.uuid, 2000, reg.getIndex("failed"));

					verify(client, timeout(5_000)).successResponse(reg.uuid, 1000, 42);
					verify(client, timeout(5_000)).failureResponse(eq(reg.uuid), eq(2000), any(RuntimeException.class));

					verifyNoMoreInteractions(client);

					proxy.success();

					verify(client, timeout(5_000)).successResponse(reg.uuid, 3000, 4711);
					verifyNoMoreInteractions(client);

					proxy.failure();
					verify(client, timeout(5_000)).failureResponse(eq(reg.uuid), eq(4000), any(RuntimeException.class));
				}
			}
		}
	}

	@ParameterizedTest
	@MethodSource("getServers")
	public void testFailure(RSATestServer test) throws Exception {
		Test service = new Test();
		try (Reg<CharSequence> reg = test.exported(service, CharSequence.class, HardCases.class)) {
			Client mock = Mockito.mock(Client.class);
			for (URI uri : reg.getEndpoints()) {
				try (RSAChannel channel = test.getChannel(uri)) {
					Server server = channel.server(mock);
					for (int i = 0; i < 2; i++) {
						ByteBuf b = Unpooled.copiedBuffer(new byte[] {
							42
						});

						server.callWithReturn(reg.uuid, 900 + i, reg.getIndex("charAt", int.class), b);
						verify(mock, timeout(5_000)).failureToDeserialize(eq(reg.uuid), eq(900 + i), any(String.class));
					}
				}
			}
		}

	}

	public interface FutureService {
		Future<Integer> unresolved();

		Future<Integer> unresolvedFailure();

		Future<Integer> failed();

		Future<Integer> resolved();
	}

	@ParameterizedTest
	@MethodSource("getServers")
	void testFuture(RSATestServer test) throws Exception {
		class FutureServiceImpl implements FutureService {
			final CompletableFuture<Integer>	success	= new CompletableFuture<>();
			final CompletableFuture<Integer>	failure	= new CompletableFuture<>();

			@Override
			public Future<Integer> unresolved() {
				return success;
			}

			@Override
			public Future<Integer> unresolvedFailure() {
				return failure;
			}

			@Override
			public Future<Integer> resolved() {
				return CompletableFuture.completedFuture(42);
			}

			@Override
			public Future<Integer> failed() {
				CompletableFuture<Integer> failure = new CompletableFuture<>();
				failure.completeExceptionally(new RuntimeException());
				return failure;
			}

			public void success() {
				success.complete(4711);
			}

			public void failure() {
				failure.completeExceptionally(new RuntimeException());
			}

		}

		FutureServiceImpl service = new FutureServiceImpl();

		try (Reg<FutureService> reg = test.exported(service, FutureService.class)) {
			Client client = Mockito.mock(Client.class);
			for (URI uri : reg.getEndpoints()) {
				try (RSAChannel channel = test.getChannel(uri)) {
					Server server = channel.server(client);

					server.callWithReturn(reg.uuid, 3000, reg.getIndex("unresolved"));
					server.callWithReturn(reg.uuid, 4000, reg.getIndex("unresolvedFailure"));
					server.callWithReturn(reg.uuid, 1000, reg.getIndex("resolved"));
					server.callWithReturn(reg.uuid, 2000, reg.getIndex("failed"));

					verify(client, timeout(5_000)).successResponse(reg.uuid, 1000, 42);
					verify(client, timeout(5_000)).failureResponse(eq(reg.uuid), eq(2000), any(RuntimeException.class));

					verifyNoMoreInteractions(client);

					service.success();

					verify(client, timeout(5_000)).successResponse(reg.uuid, 3000, 4711);
					verifyNoMoreInteractions(client);

					service.failure();
					verify(client, timeout(5_000)).failureResponse(eq(reg.uuid), eq(4000), any(RuntimeException.class));
				}
			}
		}
	}

	interface PushStreamService {
		PushStream<Integer> stream();
	}

	@ParameterizedTest
	@MethodSource("getServers")
	void testPushStream(RSATestServer test) throws Exception {
		SimplePushEventSource<Integer> source = provider.createSimpleEventSource(Integer.class);
		class PushStreamServiceImpl implements PushStreamService {

			@Override
			public PushStream<Integer> stream() {
				return provider.createStream(source);
			}

		}

		PushStreamServiceImpl service = new PushStreamServiceImpl();

		try (Reg<PushStreamService> reg = test.exported(service, PushStreamService.class)) {
			Client client = Mockito.mock(Client.class);
			for (URI uri : reg.getEndpoints()) {
				try (RSAChannel channel = test.getChannel(uri)) {
					Server server = channel.server(client);

					{
						server.callWithReturn(reg.uuid, 1000, reg.getIndex("stream"));

						verify(client, timeout(2_000)).successResponse(reg.uuid, 1000, new Object[] {
							reg.uuid, 1000
						});

						assertThat(source.isConnected()).isFalse();
						server.clientOpen(reg.uuid, 1000);

						Awaitility.await()
							.until(source::isConnected);

						source.publish(42);
						verify(client, timeout(5_000)).serverDataEvent(reg.uuid, 1000, 42);

						source.publish(44);
						source.publish(46);
						verify(client, timeout(5_000)).serverDataEvent(reg.uuid, 1000, 44);
						verify(client, timeout(5_000)).serverDataEvent(reg.uuid, 1000, 46);

						source.error(new RuntimeException());
						verify(client, timeout(5_000)).serverErrorEvent(eq(reg.uuid), eq(1000),
							any(RuntimeException.class));

						assertThat(source.isConnected()).isFalse();
					}

					{
						server.callWithReturn(reg.uuid, 2000, reg.getIndex("stream"));

						verify(client, timeout(2_000)).successResponse(reg.uuid, 2000, new Object[] {
							reg.uuid, 2000
						});

						assertThat(source.isConnected()).isFalse();
						server.clientOpen(reg.uuid, 2000);

						Awaitility.await()
							.until(source::isConnected);

						source.close();

						verify(client, timeout(2_000)).serverCloseEvent(reg.uuid, 2000);
						assertThat(source.isConnected()).isFalse();
					}
				}
			}
		}

	}

	static Stream<RSATestServer> getServers() {
		List<RSATestServer> servers = new ArrayList<>();
		Serializer javaSerializer = new JavaSerializer();
		Serializer vanilla = new VanillaRMISerializer();
		for (Serializer serializer : new Serializer[] {
			javaSerializer, vanilla
		}) {

			servers.add(getSimpleTCP(serializer));
			servers.add(getTLS(serializer));
			servers.add(getSSLClientAuth(serializer));
			servers.add(getServerConnectionManagerWithGlobalBindAndSpecificAdvertiseTestv4(serializer));
			if (hasIpV6()) {
				servers.add(getServerConnectionManagerWithGlobalBindAndSpecificAdvertiseTestv6(serializer));
			}
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

	static RSATestServer getServerConnectionManagerWithGlobalBindAndSpecificAdvertiseTestv4(Serializer serializer) {
		return new RSATestServer.Builder("bind-ip4")//
			.set("allow.insecure.transports", true)
			.set("server.protocols", "TCP;bind=127.0.0.1;advertise=localhost")
			.serializer(serializer)
			.tcp()
			.build();
	}

	static RSATestServer getServerConnectionManagerWithGlobalBindAndSpecificAdvertiseTestv6(Serializer serializer) {
		return new RSATestServer.Builder("bind-ip6")//
			.set("allow.insecure.transports", true)
			.set("server.protocols", "TCP;bind=[::];advertise=localhost")
			.serializer(serializer)
			.tcp()
			.build();
	}

	static RSATestServer getSSLClientAuth(Serializer serializer) {
		return new RSATestServer.Builder("test")//
			.set("allow.insecure.transports", true)
			.set("server.protocols", "TCP_CLIENT_AUTH")
			.serializer(serializer)
			.ssl(true)
			.build();
	}

	static RSATestServer getTLS(Serializer serializer) {
		return new RSATestServer.Builder("test")//
			.set("allow.insecure.transports", true)
			.set("server.protocols", "TCP_TLS")
			.serializer(serializer)
			.ssl(false)
			.build();
	}

	static boolean hasIpV6() {
		String protocol;
		try (DatagramSocket ds = new DatagramSocket(0, InetAddress.getByName("::"))) {
			return true;
		} catch (SocketException e) {
			return false;
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

}
