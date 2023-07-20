package org.eclipse.ot.rsa.distribution.provider.promise;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.ot.rsa.distribution.provider.promise.PromiseHandler.ProxyDeferred;
import org.eclipse.ot.rsa.distribution.provider.promise.PromiseHandler.ProxyPromise;
import org.eclipse.ot.rsa.distribution.provider.promise.PromiseHandler.ProxyPromiseFactory;
import org.junit.jupiter.api.Test;

import aQute.lib.io.IO;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

class PromiseFactoryTest {

	private static final ExecutorService POOL = Executors.newCachedThreadPool();

	@Test
	void testIsPromise() throws InterruptedException {

		assertThat(PromiseFactory.isPromise(org.osgi.util.promise.Promise.class)).isTrue();
	}

	@Test
	void testFailureNettyToOSGi() throws InterruptedException, ClassNotFoundException {
		testFailureNettyToOSGi(org.osgi.util.promise.Promise.class);
	}

	@Test
	void testSuccessNettyToOSGi() throws InterruptedException, InvocationTargetException {
		testSuccessNettyToOSGi(org.osgi.util.promise.Promise.class);
	}

	@Test
	void testFailureOSGiToNetty() throws InterruptedException, ExecutionException {
		testFailureOSGiToNetty(org.osgi.util.promise.Promise.class);
	}

	@Test
	void testSuccessOSGiToNetty() throws InterruptedException, InvocationTargetException, ExecutionException {
		testSuccessOSGiToNetty(org.osgi.util.promise.Promise.class);
	}

	@Test
	void testNonOSGiClass() throws InterruptedException, InvocationTargetException, ExecutionException {
		assertThat(PromiseFactory.isPromise(String.class)).isFalse();
		assertThat(PromiseFactory.isPromise(InputStream.class)).isFalse();
		assertThat(PromiseFactory.isPromise(org.osgi.util.promise.Promise.class)).isTrue();
		org.osgi.util.promise.PromiseFactory pf = new org.osgi.util.promise.PromiseFactory(null);

		assertThat(PromiseFactory.isPromise(pf.deferred()
			.getPromise()
			.getClass())).isTrue();
	}

	@Test
	void testDifferentClassSpaces() throws Exception {
		File[] dirs = IO.getFile("test-resources/promises")
			.listFiles();

		for (File f : dirs) {
			URL[] target = Stream.of(f.listFiles())
				.map(x -> {
					try {
						return x.toURI()
							.toURL();
					} catch (MalformedURLException e) {
					}
					return null;
				})
				.toArray(URL[]::new);

			try (URLClassLoader c = new URLClassLoader(target, null)) {
				Class<?> ptype = c.loadClass("org.osgi.util.promise.Promise");
				assertThat(ptype).isNotEqualTo(Promise.class);

				testFailureNettyToOSGi(ptype);
				testSuccessNettyToOSGi(ptype);
				testFailureOSGiToNetty(ptype);
				testSuccessOSGiToNetty(ptype);
			}

		}
	}

	private void testFailureNettyToOSGi(Class<?> ptype) throws ClassNotFoundException, InterruptedException {
		Promise<Object> netty = ImmediateEventExecutor.INSTANCE.newPromise();
		Function<Future<?>, Object> nettyToOSGi = PromiseFactory.nettyToOSGi(ptype, POOL);

		Object promise = nettyToOSGi.apply(netty);
		ProxyPromise wrap = PromiseHandler.getHandler(promise.getClass())
			.wrap(promise);

		Thread.sleep(100);

		assertThat(wrap.isDone()).isFalse();
		RuntimeException ex = new RuntimeException();
		netty.setFailure(ex);
		assertThat(wrap.getFailure()).isEqualTo(ex);
	}

	void testSuccessNettyToOSGi(Class<?> ptype) throws InterruptedException, InvocationTargetException {
		Promise<Object> netty = ImmediateEventExecutor.INSTANCE.newPromise();
		Function<Future<?>, Object> nettyToOSGi = PromiseFactory.nettyToOSGi(ptype, POOL);
		Object promise = nettyToOSGi.apply(netty);
		ProxyPromise wrap = PromiseHandler.getHandler(promise.getClass())
			.wrap(promise);
		Thread.sleep(100);
		assertThat(wrap.isDone()).isFalse();
		netty.setSuccess("hello");
		assertThat(wrap.getValue()).isEqualTo("hello");
	}

	void testFailureOSGiToNetty(Class<?> type) throws InterruptedException, ExecutionException {
		ProxyPromiseFactory factory = PromiseHandler.getHandler(type).factory;
		ProxyDeferred deferred = factory.deferred();

		Function<Object, Future<Object>> osgiToNetty = PromiseFactory.osgiToNetty(type);

		Future<Object> netty = osgiToNetty.apply(deferred.getPromise()
			.getActual());
		Thread.sleep(100);
		assertThat(netty.isDone()).isFalse();
		deferred.resolve("hello");
		assertThat(netty.get()).isEqualTo("hello");

	}

	void testSuccessOSGiToNetty(Class<?> type)
		throws InterruptedException, InvocationTargetException, ExecutionException {
		ProxyPromiseFactory factory = PromiseHandler.getHandler(type).factory;
		ProxyDeferred deferred = factory.deferred();

		Function<Object, Future<Object>> osgiToNetty = PromiseFactory.osgiToNetty(type);

		Future<Object> netty = osgiToNetty.apply(deferred.getPromise()
			.getActual());
		Thread.sleep(100);
		assertThat(netty.isDone()).isFalse();
		RuntimeException ex = new RuntimeException();
		deferred.fail(ex);
		netty.await();
		assertThat(netty.cause()).isEqualTo(ex);
	}

}
