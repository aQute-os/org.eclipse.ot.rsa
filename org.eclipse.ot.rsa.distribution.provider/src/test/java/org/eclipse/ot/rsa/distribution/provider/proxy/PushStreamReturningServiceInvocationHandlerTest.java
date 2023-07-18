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
package org.eclipse.ot.rsa.distribution.provider.proxy;

import static java.util.Arrays.asList;
import static java.util.Arrays.deepEquals;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.STREAMING_RESPONSE_OPEN;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.WITH_RETURN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.ot.rsa.constants.RSAConstants;
import org.eclipse.ot.rsa.distribution.provider.client.AbstractClientInvocationWithResult;
import org.eclipse.ot.rsa.distribution.provider.client.ClientInvocation;
import org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType;
import org.eclipse.ot.rsa.distribution.provider.impl.ImportRegistrationImpl;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class PushStreamReturningServiceInvocationHandlerTest {

	@Mock
	private ImportRegistrationImpl	_importRegistration;
	@Mock
	Channel							_ch;
	@Mock
	ChannelPromise					_chPromise;
	@Mock
	Serializer						_serializer;
	@Mock
	Bundle							_callingContext;

	ByteBuf							_trueBuf	= Unpooled.buffer();
	ByteBuf							_falseBuf	= Unpooled.buffer();
	ByteBuf							_oneBuf		= Unpooled.buffer();
	ByteBuf							_twoBuf		= Unpooled.buffer();
	ByteBuf							_nullBuf	= Unpooled.buffer();

	private EndpointDescription		_endpointDescription;

	private Class<?>				_proxyClass;
	private List<Class<?>>			_proxyClassInterfaces;
	private Class<?>				_proxyClassWithDifferentPushStream;
	private List<Class<?>>			_proxyClassWithDifferentPushStreamInterfaces;
	private Class<?>				_differentPromise;
	private Class<?>				_differentPushStream;
	private Class<?>				_differentPushEventSource;

	private EventExecutor			executor;

	private Timer					timer;

	@BeforeEach
	public void setUp() throws Exception {
		executor = ImmediateEventExecutor.INSTANCE;
		timer = new HashedWheelTimer();

		Mockito.when(_ch.newPromise())
			.then(x -> new DefaultChannelPromise(_ch, executor));

		Map<String, Object> map = new HashMap<>();
		map.put(RemoteConstants.ENDPOINT_ID, new UUID(123, 456).toString());
		map.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
		map.put(Constants.OBJECTCLASS, new String[] {
			TestReturnsPushStreamTypes.class.getName()
		});
		map.put(RSAConstants.DISTRIBUTION_CONFIG_METHODS, new String[] {
			"1=booleans[]"
		});
		_endpointDescription = new EndpointDescription(map);

		_proxyClass = Proxy.getProxyClass(new ClassLoader() {}, TestReturnsPushStreamTypes.class);
		_proxyClassInterfaces = asList(TestReturnsPushStreamTypes.class);

		ClassLoader differentClassLoader = getSeparateClassLoader();

		_proxyClassWithDifferentPushStream = Proxy.getProxyClass(differentClassLoader,
			differentClassLoader.loadClass(TestReturnsPushStreamTypes.class.getName()));
		_proxyClassWithDifferentPushStreamInterfaces = asList(
			differentClassLoader.loadClass(TestReturnsPushStreamTypes.class.getName()));
		_differentPromise = differentClassLoader.loadClass(Promise.class.getName());
		_differentPushStream = differentClassLoader.loadClass(PushStream.class.getName());
		_differentPushEventSource = differentClassLoader.loadClass(PushEventSource.class.getName());

		Map<Integer, String> methods = new HashMap<>();
		methods.put(1, "booleans[]");
		methods.put(2, "integers[]");
		when(_importRegistration.getMethodMappings()).thenReturn(methods);
		when(_importRegistration.getId()).thenReturn(new UUID(123, 456));

		when(_serializer.deserializeReturn(_trueBuf)).thenReturn(Boolean.TRUE);
		when(_serializer.deserializeReturn(_falseBuf)).thenReturn(Boolean.FALSE);
		when(_serializer.deserializeReturn(_oneBuf)).thenReturn(1);
		when(_serializer.deserializeReturn(_twoBuf)).thenReturn(2);
		when(_serializer.deserializeReturn(_nullBuf)).thenReturn(null);
	}

	@AfterEach
	public void tearDown() throws Exception {
		timer.stop();
		executor.shutdownGracefully();
		executor.awaitTermination(1, TimeUnit.SECONDS);
	}

	private ClassLoader getSeparateClassLoader() {
		return new ClassLoader() {
			private final Map<String, Class<?>> cache = new HashMap<>();

			@Override
			public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("java")) {
					return super.loadClass(name, resolve);
				}
				Class<?> c = cache.get(name);
				if (c != null)
					return c;

				String resourceName = name.replace('.', '/') + ".class";

				InputStream resourceAsStream = PushStreamReturningServiceInvocationHandlerTest.this.getClass()
					.getClassLoader()
					.getResourceAsStream(resourceName);
				if (resourceAsStream == null)
					throw new ClassNotFoundException(name);
				try (InputStream is = resourceAsStream) {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					byte[] b = new byte[4096];

					int i = 0;
					while ((i = is.read(b)) > -1) {
						baos.write(b, 0, i);
					}
					c = defineClass(name, baos.toByteArray(), 0, baos.size());
				} catch (IOException e) {
					throw new ClassNotFoundException(name, e);
				}
				cache.put(name, c);
				if (resolve) {
					resolveClass(c);
				}
				return c;
			}
		};
	}

	private Object createProxy(Class<?> proxyClass, ServiceInvocationHandler handler) {
		try {
			return proxyClass.getConstructor(InvocationHandler.class)
				.newInstance(handler);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	ArgumentMatcher<Object[]> isArrayOf(Object... o) {
		return new ArgumentMatcher<Object[]>() {

			@Override
			public boolean matches(Object[] item) {
				return (o.length == 0 && item == null) || deepEquals(o, item);
			}
		};
	}

	@Test
	public void testSuccessfulInvocationPushStream() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
			_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, false, PushStream.class,
			PushEventConsumer.class, _ch, _serializer, () -> 1, new AtomicLong(3000), executor, timer);

		TestReturnsPushStreamTypes proxy = (TestReturnsPushStreamTypes) createProxy(_proxyClass, sih);

		when(_ch
			.writeAndFlush(argThat(isInvocationWith(WITH_RETURN, TestReturnsPushStreamTypes.class.getMethod("booleans")
				.toString(), new Object[] {})), any())).then(i -> {
					i.<ClientInvocation> getArgument(0)
						.getResult()
						.setSuccess(new Object[] {
							new UUID(1, 2), 3
					});
					return null;
				});

		PushStream<Boolean> p = proxy.booleans();

		when(_ch.writeAndFlush(argThat(isInvocationWith(STREAMING_RESPONSE_OPEN)))).then(i -> {
			AbstractClientInvocationWithResult invocation = i.<AbstractClientInvocationWithResult> getArgument(0);
			assertEquals(new UUID(1, 2), invocation.getServiceId());
			assertEquals(3, invocation.getCallId());
			invocation.data(_trueBuf);
			invocation.data(_falseBuf);
			invocation.fail(_nullBuf);
			return _chPromise;
		});

		Promise<Long> promise = p.count();

		assertTrue(promise.isDone());
		assertEquals(2, promise.getValue()
			.longValue());
	}

	@Test
	public void testSuccessfulInvocationPushEventSource() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
			_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, false, PushStream.class,
			PushEventSource.class, _ch, _serializer, () -> 1, new AtomicLong(3000), executor, timer);

		TestReturnsPushStreamTypes proxy = (TestReturnsPushStreamTypes) createProxy(_proxyClass, sih);

		when(_ch
			.writeAndFlush(argThat(isInvocationWith(WITH_RETURN, TestReturnsPushStreamTypes.class.getMethod("integers")
				.toString(), new Object[] {})), any())).then(i -> {
					i.<ClientInvocation> getArgument(0)
						.getResult()
						.setSuccess(new Object[] {
							new UUID(1, 2), 3
					});
					return null;
				});

		PushEventSource<Integer> p = proxy.integers();

		when(_ch.writeAndFlush(argThat(isInvocationWith(STREAMING_RESPONSE_OPEN)))).then(i -> {
			AbstractClientInvocationWithResult invocation = i.<AbstractClientInvocationWithResult> getArgument(0);
			assertEquals(new UUID(1, 2), invocation.getServiceId());
			assertEquals(3, invocation.getCallId());
			invocation.data(_oneBuf);
			invocation.data(_twoBuf);
			invocation.fail(_nullBuf);
			return _chPromise;
		});

		Promise<Long> promise = new PushStreamProvider().buildStream(p)
			.unbuffered()
			.build()
			.count();

		promise.getValue();
		assertEquals(2, promise.getValue()
			.longValue());
	}

	@Test
	public void testSuccessfulInvocationDifferentPushStream() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
			_callingContext, _proxyClassWithDifferentPushStream, _proxyClassWithDifferentPushStreamInterfaces,
			_differentPromise, false, _differentPushStream, _differentPushEventSource, _ch, _serializer, () -> 1,
			new AtomicLong(3000), executor, timer);

		Object proxy = createProxy(_proxyClassWithDifferentPushStream, sih);

		when(_ch
			.writeAndFlush(argThat(isInvocationWith(WITH_RETURN, TestReturnsPushStreamTypes.class.getMethod("booleans")
				.toString(), new Object[] {})), any())).then(i -> {
					i.<ClientInvocation> getArgument(0)
						.getResult()
						.setSuccess(new Object[] {
							new UUID(1, 2), 3
					});
					return null;
				});

		when(_ch.writeAndFlush(argThat(isInvocationWith(STREAMING_RESPONSE_OPEN)))).then(i -> {
			AbstractClientInvocationWithResult invocation = i.<AbstractClientInvocationWithResult> getArgument(0);
			assertEquals(new UUID(1, 2), invocation.getServiceId());
			assertEquals(3, invocation.getCallId());
			invocation.data(_trueBuf);
			invocation.data(_falseBuf);
			invocation.fail(_nullBuf);
			return _chPromise;
		});

		Method booleans = _proxyClassWithDifferentPushStream.getMethod("booleans");
		Method count = _differentPushStream.getMethod("count");

		Object returnedPushStream = booleans.invoke(proxy);
		Object returnedPromise = count.invoke(returnedPushStream);

		assertTrue((Boolean) _differentPromise.getMethod("isDone")
			.invoke(returnedPromise));
		assertEquals(2L, _differentPromise.getMethod("getValue")
			.invoke(returnedPromise));
	}

	@Test
	public void testSuccessfulInvocationDifferentPushEventSource() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
			_callingContext, _proxyClassWithDifferentPushStream, _proxyClassWithDifferentPushStreamInterfaces,
			_differentPromise, false, _differentPushStream, _differentPushEventSource, _ch, _serializer, () -> 1,
			new AtomicLong(3000), executor, timer);

		Object proxy = createProxy(_proxyClassWithDifferentPushStream, sih);

		when(_ch
			.writeAndFlush(argThat(isInvocationWith(WITH_RETURN, TestReturnsPushStreamTypes.class.getMethod("integers")
				.toString(), new Object[] {})), any())).then(i -> {
					i.<ClientInvocation> getArgument(0)
						.getResult()
						.setSuccess(new Object[] {
							new UUID(1, 2), 3
					});
					return null;
				});

		when(_ch.writeAndFlush(argThat(isInvocationWith(STREAMING_RESPONSE_OPEN)))).then(i -> {
			AbstractClientInvocationWithResult invocation = i.<AbstractClientInvocationWithResult> getArgument(0);
			assertEquals(new UUID(1, 2), invocation.getServiceId());
			assertEquals(3, invocation.getCallId());
			invocation.data(_oneBuf);
			invocation.data(_twoBuf);
			invocation.fail(_nullBuf);
			return _chPromise;
		});

		Method integers = _proxyClassWithDifferentPushStream.getMethod("integers");
		Method open = _differentPushEventSource.getMethods()[0];

		AtomicInteger count = new AtomicInteger(0);

		Object consumer = Proxy.newProxyInstance(_differentPushEventSource.getClassLoader(), new Class<?>[] {
			open.getParameterTypes()[0]
		}, (x, y, z) -> {
			count.incrementAndGet();
			return 0L;
		});

		Object returnedPushEventSource = integers.invoke(proxy);
		open.invoke(returnedPushEventSource, consumer);

		assertEquals(3, count.get());
	}

	@Test
	public void testSuccessfulInvocationDifferentPushEventSourceEarlyTerminate() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
			_callingContext, _proxyClassWithDifferentPushStream, _proxyClassWithDifferentPushStreamInterfaces,
			_differentPromise, false, _differentPushStream, _differentPushEventSource, _ch, _serializer, () -> 1,
			new AtomicLong(3000), executor, timer);

		Object proxy = createProxy(_proxyClassWithDifferentPushStream, sih);

		when(_ch
			.writeAndFlush(argThat(isInvocationWith(WITH_RETURN, TestReturnsPushStreamTypes.class.getMethod("integers")
				.toString(), new Object[] {})), any())).then(i -> {
					i.<ClientInvocation> getArgument(0)
						.getResult()
						.setSuccess(new Object[] {
							new UUID(1, 2), 3
					});
					return null;
				});

		when(_ch.writeAndFlush(argThat(isInvocationWith(STREAMING_RESPONSE_OPEN)))).then(i -> {
			AbstractClientInvocationWithResult invocation = i.<AbstractClientInvocationWithResult> getArgument(0);
			assertEquals(new UUID(1, 2), invocation.getServiceId());
			assertEquals(3, invocation.getCallId());
			invocation.data(_oneBuf);
			invocation.data(_twoBuf);
			invocation.fail(_nullBuf);
			return _chPromise;
		});

		Method integers = _proxyClassWithDifferentPushStream.getMethod("integers");
		Method open = _differentPushEventSource.getMethods()[0];

		AtomicInteger count = new AtomicInteger(0);

		Object consumer = Proxy.newProxyInstance(_differentPushEventSource.getClassLoader(), new Class<?>[] {
			open.getParameterTypes()[0]
		}, (x, y, z) -> {
			count.incrementAndGet();
			return -1L;
		});

		Object returnedPushEventSource = integers.invoke(proxy);
		open.invoke(returnedPushEventSource, consumer);

		assertEquals(2, count.get());
	}

	private ArgumentMatcher<ClientInvocation> isInvocationWith(ClientMessageType callType, String method,
		Object[] args) {
		return new ArgumentMatcher<ClientInvocation>() {

			@Override
			public boolean matches(ClientInvocation clientInvocation) {
				return clientInvocation != null && clientInvocation.getType() == callType
					&& clientInvocation.getMethodName()
						.equals(method)
					&& deepEquals(args, clientInvocation.getArgs());
			}
		};
	}

	private ArgumentMatcher<AbstractClientInvocationWithResult> isInvocationWith(ClientMessageType callType) {
		return new ArgumentMatcher<AbstractClientInvocationWithResult>() {

			@Override
			public boolean matches(AbstractClientInvocationWithResult arg0) {
				AbstractClientInvocationWithResult invocation = arg0;
				return invocation.getType() == callType;
			}
		};
	}
}
