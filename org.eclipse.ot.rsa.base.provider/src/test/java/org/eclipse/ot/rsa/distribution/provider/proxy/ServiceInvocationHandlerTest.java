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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.osgi.framework.ServiceException;
import org.osgi.service.async.delegate.AsyncDelegate;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.promise.Promise;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceInvocationHandlerTest {

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
	private Class<?> _proxyClassWithDifferentAsyncDelegate;
	private List<Class<?>> _proxyClassWithDifferentAsyncDelegateInterfaces;
	private Class<?> _differentPromise;

    private EventExecutor executor;

    private Timer timer;

    @BeforeEach
	public void setUp() throws Exception {

        executor = new DefaultEventExecutor();
        timer = new HashedWheelTimer();

        Mockito.when(_ch.newPromise()).then(x -> new DefaultChannelPromise(_ch, executor));

        Map<String, Object> map = new HashMap<>();
        map.put(RemoteConstants.ENDPOINT_ID, "my.endpoint.id");
        map.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
        map.put(Constants.OBJECTCLASS, new String[] {CharSequence.class.getName()});
        map.put(RSAConstants.DISTRIBUTION_METHODS, new String[] {"1=length[]","2=subSequence[int,int]"});
        _endpointDescription = new EndpointDescription(map);

        _proxyClass = Proxy.getProxyClass(new ClassLoader(){}, CharSequence.class, AsyncDelegate.class);
        _proxyClassInterfaces = asList(CharSequence.class, AsyncDelegate.class);

        ClassLoader differentClassLoader = getSeparateClassLoader();

        _proxyClassWithDifferentAsyncDelegate = Proxy.getProxyClass(differentClassLoader,
        		CharSequence.class, differentClassLoader.loadClass(AsyncDelegate.class.getName()));
        _proxyClassWithDifferentAsyncDelegateInterfaces = asList(CharSequence.class,
        		differentClassLoader.loadClass(AsyncDelegate.class.getName()));
        _differentPromise = differentClassLoader.loadClass(Promise.class.getName());

        Map<Integer, String> methods = new HashMap<>();
        methods.put(1, "length[]");
        methods.put(2, "subSequence[int,int]");
        when(_importRegistration.getMethodMappings()).thenReturn(methods);
    }

    @AfterEach
    public void teardown() throws InterruptedException {
    	timer.stop();
    	executor.shutdownGracefully(500, 1000, MILLISECONDS).await(1, SECONDS);
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

				InputStream resourceAsStream = ServiceInvocationHandlerTest.this.getClass()
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

	private CharSequence createProxy(Class<?> proxyClass, ServiceInvocationHandler handler) {
		try {
			return (CharSequence) proxyClass.getConstructor(InvocationHandler.class).newInstance(handler);
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
	public void testSuccessfulInvocation() throws Exception {

        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClass, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(ClientMessageType.WITH_RETURN,
        		CharSequence.class.getMethod("length").toString(), new Object[0])), any()))
	        .then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
	    			.setSuccess(30);
				return null;
			});

        assertEquals(30, proxy.length());
    }

    @Test
	public void testSuccessfulAsyncInvocation() throws Exception {

        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClass, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(ClientMessageType.WITH_RETURN,
        		CharSequence.class.getMethod("subSequence", int.class, int.class).toString(),
        		new Object[] {5,10})), any()))
	        .then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
	    			.setSuccess("Hello");
				return null;
			});

        assertEquals("Hello", ((AsyncDelegate)proxy).async(
        		CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10})
        		.getValue());
    }

    @Test
	public void testSuccessfulFireAndForget() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
				_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


		CharSequence proxy = createProxy(_proxyClass, sih);

		assertTrue(((AsyncDelegate)proxy).execute(
				CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10}));

		verify(_ch).writeAndFlush(argThat(isInvocationWith(ClientMessageType.FIRE_AND_FORGET,
				CharSequence.class.getMethod("subSequence", int.class, int.class).toString(),
				new Object[] {5, 10})), any());
	}

    @Test
	public void testInvocationFailureWithUndeclaredThrowable() throws Exception {
    	ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClass, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(ClientMessageType.WITH_RETURN,
        		CharSequence.class.getMethod("length").toString(), new Object[0])), any()))
	        .then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
	    			.setFailure(new ClassNotFoundException("missing.class"));
				return null;
			});

        try {
        	proxy.length();
        	fail();
        }
        catch (UndeclaredThrowableException ute) {
            fail("expected ServiceException(REMOTE)");
        }
        catch (ServiceException sex) {
            // expected, verify type
            assertEquals(ServiceException.REMOTE, sex.getType());
            assertEquals(ClassNotFoundException.class, sex.getCause().getClass());
        }
    }

    @Test
    public void testInvocationFailureWithForwardedException() {
    	ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClass, sih);

        when(_ch.writeAndFlush(any(), any()))
        .then(i -> {
			i.<ClientInvocation>getArgument(0).getResult()
				.setFailure(new IllegalStateException("length"));
			return null;
		});

        try {
            proxy.length();
            fail("expected IllegalStateException");
        }
        catch (IllegalStateException iex) {
            // expected
        }

    }

    @Test
	public void testMethodsInObjectClassAreNotPropagated() throws Exception {
        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClass, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(ClientMessageType.WITH_RETURN,
        		CharSequence.class.getMethod("length").toString(), new Object[0])), any()))
	        .then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
	    			.setSuccess(30);
				return null;
			});

        // length() is allowed to be forwarded
        assertEquals(30, proxy.length());

        // toString() generates a proxy instance description containing the list of
        // proxied interfaces
        String toString = proxy.toString();
        assertTrue(toString.contains(("[java.lang.CharSequence, org.osgi.service.async.delegate.AsyncDelegate]")), toString);

        // hashCode()is alway mapped to the associated InvocationHandler
        int hashCode = proxy.hashCode();
        assertEquals(sih.hashCode(), hashCode);

        // equals() with null is always false
        assertFalse(proxy.equals(null));

        // equals() with itself is always true
        assertEquals(proxy, proxy);

        // make sure no more methods were triggered
        Mockito.verify(_ch, Mockito.times(1)).writeAndFlush(any(), any());
    }

    @Test
	public void testSuccessfulAsyncInvocationDifferentAsync() throws Exception {

        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClassWithDifferentAsyncDelegate,
        		_proxyClassWithDifferentAsyncDelegateInterfaces, _differentPromise, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClassWithDifferentAsyncDelegate, sih);

        when(_ch.writeAndFlush(argThat(isInvocationWith(ClientMessageType.WITH_RETURN,
        		CharSequence.class.getMethod("subSequence", int.class, int.class).toString(),
        		new Object[] {5,10})), any()))
	        .then(i -> {
				i.<ClientInvocation>getArgument(0).getResult()
	    			.setSuccess("Hello");
				return null;
			});

        Method m = _proxyClassWithDifferentAsyncDelegate.getMethod("async", Method.class, Object[].class);

        Object returnedPromise = m.invoke(proxy, new Object[] {
        		CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10}});

        assertEquals("Hello", _differentPromise.getMethod("getValue").invoke(returnedPromise));
    }

    @Test
	public void testSuccessfulFireAndForgetDifferentAsync() throws Exception {

		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClassWithDifferentAsyncDelegate,
        		_proxyClassWithDifferentAsyncDelegateInterfaces, _differentPromise, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClassWithDifferentAsyncDelegate, sih);

        Method m = _proxyClassWithDifferentAsyncDelegate.getMethod("execute", Method.class, Object[].class);

		assertTrue((Boolean) m.invoke(proxy, new Object[] {
				CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10}}));

		verify(_ch).writeAndFlush(argThat(isInvocationWith(ClientMessageType.FIRE_AND_FORGET,
        		CharSequence.class.getMethod("subSequence", int.class, int.class).toString(),
        		new Object[] {5, 10})), any());
	}

    @Test
    public void testSendFailure() throws Exception {

    	ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, null, null, _ch,
        		_serializer, () -> 1, new AtomicLong(3000), executor, timer);


        CharSequence proxy = createProxy(_proxyClass, sih);

        Exception e = new RuntimeException("BANG!");

        when(_ch.writeAndFlush(argThat(isInvocationWith(ClientMessageType.WITH_RETURN,
        		CharSequence.class.getMethod("length").toString(), new Object[0])), any()))
	        .then(i -> {
				i.<ChannelPromise>getArgument(1).tryFailure(e);
				return null;
			});

        try {
        	proxy.length();
        	fail("Should explode");
        } catch (ServiceException se) {
        	assertEquals(ServiceException.REMOTE, se.getType());
        	assertSame(e, se.getCause());
        }
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
