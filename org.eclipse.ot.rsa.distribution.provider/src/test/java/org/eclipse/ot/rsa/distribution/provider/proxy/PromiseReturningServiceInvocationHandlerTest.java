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
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.WITH_RETURN;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.ot.rsa.constants.RSAConstants;
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

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class PromiseReturningServiceInvocationHandlerTest {

    @Mock
    private ImportRegistrationImpl _importRegistration;
    @Mock
    Channel _ch;
    @Mock
    Serializer _serializer;
    @Mock
    Bundle _callingContext;


	private EndpointDescription _endpointDescription;

	private Class<?> _proxyClass;
	private List<Class<?>> _proxyClassInterfaces;
	private Class<?> _proxyClassWithDifferentPromise;
	private List<Class<?>> _proxyClassWithDifferentPromiseInterfaces;
	private Class<?> _differentPromise;

	private EventExecutor executor;

    private Timer timer;

    @BeforeEach
	public void setUp() throws Exception {
        executor = new DefaultEventExecutor();
        timer = new HashedWheelTimer();

        Mockito.when(_ch.newPromise()).then(x -> new DefaultChannelPromise(_ch, executor));

        Map<String, Object> map = new HashMap<>();
        map.put(RemoteConstants.ENDPOINT_ID, new UUID(123, 456).toString());
        map.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
        map.put(Constants.OBJECTCLASS, new String[] {TestReturnsAsyncTypes.class.getName()});
        map.put(RSAConstants.DISTRIBUTION_CONFIG_METHODS, new String[] {"1=coprime[long,long]",
        		"2=isPrime[long]", "3=countGrainsOfSand[java.lang.String]"});
        _endpointDescription = new EndpointDescription(map);

        _proxyClass = Proxy.getProxyClass(new ClassLoader(){}, TestReturnsAsyncTypes.class);
        _proxyClassInterfaces = asList(TestReturnsAsyncTypes.class);

        ClassLoader differentClassLoader = getSeparateClassLoader();

        _proxyClassWithDifferentPromise = Proxy.getProxyClass(differentClassLoader,
        		differentClassLoader.loadClass(TestReturnsAsyncTypes.class.getName()));
        _proxyClassWithDifferentPromiseInterfaces = asList(
        		differentClassLoader.loadClass(TestReturnsAsyncTypes.class.getName()));
        _differentPromise = differentClassLoader.loadClass(Promise.class.getName());

        Map<Integer, String> methods = new HashMap<>();
        methods.put(1, "coprime[long,long]");
        methods.put(2, "isPrime[long]");
        methods.put(3, "countGrainsOfSand[java.lang.String]");
        when(_importRegistration.getMethodMappings()).thenReturn(methods);
        when(_importRegistration.getId()).thenReturn(new UUID(123, 456));
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
			public Class<?> loadClass(String name) throws ClassNotFoundException {
    			if(name.startsWith("java")) {
    				return super.loadClass(name);
    			}
    			Class<?> c = cache.get(name);
    			if(c != null) return c;

    			String resourceName = name.replace('.', '/') + ".class";

				InputStream resourceAsStream = PromiseReturningServiceInvocationHandlerTest.this.getClass()
						.getClassLoader().getResourceAsStream(resourceName);
				if(resourceAsStream == null) throw new ClassNotFoundException(name);
				try(InputStream is = resourceAsStream) {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					byte[] b = new byte[4096];

					int i = 0;
					while((i = is.read(b)) > -1) {
						baos.write(b, 0, i);
					}
					c = defineClass(name, baos.toByteArray(), 0, baos.size());
				} catch (IOException e) {
					throw new ClassNotFoundException(name, e);
				}
				cache.put(name, c);
				return c;
			}
		};
	}

	private Object createProxy(Class<?> proxyClass, ServiceInvocationHandler handler) {
		try {
			return proxyClass.getConstructor(InvocationHandler.class).newInstance(handler);
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
	public void testSuccessfulInvocationPromise() throws Exception {

        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, false, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);

        TestReturnsAsyncTypes proxy = (TestReturnsAsyncTypes) createProxy(_proxyClass, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(WITH_RETURN,
        		TestReturnsAsyncTypes.class.getMethod("coprime", long.class, long.class).toString(),
        		new Object[] {7L, 42L})), any()))
			.then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
        			.setSuccess(false);
				return null;
			});

        assertFalse(proxy.coprime(7, 42).getValue());
    }

    @Test
	public void testSuccessfulInvocationFuture() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
				_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, false, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);

		TestReturnsAsyncTypes proxy = (TestReturnsAsyncTypes) createProxy(_proxyClass, sih);

		when(_ch.writeAndFlush(argThat(isInvocationWith(WITH_RETURN,
        		TestReturnsAsyncTypes.class.getMethod("isPrime", long.class).toString(),
        		new Object[] {17L})), any()))
			.then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
        			.setSuccess(false);
				return null;
			});

		assertFalse(proxy.isPrime(17).get());
	}

    @Test
	public void testSuccessfulInvocationCompletableFuture() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, false, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);

        TestReturnsAsyncTypes proxy = (TestReturnsAsyncTypes) createProxy(_proxyClass, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(WITH_RETURN,
        		TestReturnsAsyncTypes.class.getMethod("countGrainsOfSand", String.class).toString(),
        		new Object[] {"Foo"})), any()))
			.then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
        			.setSuccess(false);
				return null;
			});

        assertFalse(proxy.countGrainsOfSand("Foo").get());
	}

    @Test
	public void testSuccessfulInvocationDifferentPromise() throws Exception {

        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassWithDifferentPromiseInterfaces, _differentPromise, false, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);

        Object proxy = createProxy(_proxyClassWithDifferentPromise, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(WITH_RETURN,
        		TestReturnsAsyncTypes.class.getMethod("coprime", long.class, long.class).toString(),
        		new Object[] {14L, 15L})), any()))
			.then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
        			.setSuccess(true);
				return null;
			});

        Method m = _proxyClassWithDifferentPromise.getMethod("coprime", long.class, long.class);

        Object returnedPromise = m.invoke(proxy, new Object[] {14L, 15L});

        assertTrue((Boolean) _differentPromise.getMethod("getValue").invoke(returnedPromise));
    }

	private ArgumentMatcher<ClientInvocation> isInvocationWith(ClientMessageType callType,
			String method, Object[] args) {
		return new ArgumentMatcher<ClientInvocation>() {

				@Override
				public boolean matches(ClientInvocation clientInvocation) {
					return clientInvocation.getType() == callType &&
							clientInvocation.getMethodName().equals(method) &&
							deepEquals(args, clientInvocation.getArgs());
				}
			};
	}
}
