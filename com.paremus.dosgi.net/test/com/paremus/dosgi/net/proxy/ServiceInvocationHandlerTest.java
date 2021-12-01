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
package com.paremus.dosgi.net.proxy;

import static com.paremus.dosgi.net.proxy.MethodCallHandler.CallType.FIRE_AND_FORGET;
import static com.paremus.dosgi.net.proxy.MethodCallHandler.CallType.WITH_RETURN;
import static java.util.Arrays.asList;
import static java.util.Arrays.deepEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceException;
import org.osgi.service.async.delegate.AsyncDelegate;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;

import com.paremus.dosgi.net.impl.ImportRegistrationImpl;

@ExtendWith(MockitoExtension.class)
public class ServiceInvocationHandlerTest {

    @Mock
    private ImportRegistrationImpl _importRegistration;
    @Mock
    MethodCallHandler _mch;
    @Mock
    Bundle _callingContext;
    
    
	private EndpointDescription _endpointDescription;
	
	private Class<?> _proxyClass;
	private List<Class<?>> _proxyClassInterfaces;
	private Class<?> _proxyClassWithDifferentAsyncDelegate;
	private List<Class<?>> _proxyClassWithDifferentAsyncDelegateInterfaces;
	private Class<?> _differentPromise;
    
	@BeforeEach
    public void setUp() throws ClassNotFoundException {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(RemoteConstants.ENDPOINT_ID, "my.endpoint.id");
        map.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
        map.put(Constants.OBJECTCLASS, new String[] {CharSequence.class.getName()});
        map.put("com.paremus.dosgi.net.methods", new String[] {"1=length[]","2=subSequence[int,int]"});
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

    
	private ClassLoader getSeparateClassLoader() {
		return new ClassLoader() {
			private final Map<String, Class<?>> cache = new HashMap<String, Class<?>>();
			
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
				return (o.length == 0 && item == null) || 
						(item instanceof Object[] ? deepEquals(o, (Object[]) item) : false);
			}
		};
	}

    @Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testSuccessfulInvocation() {
    	
        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, _mch, 3000);

        CharSequence proxy = createProxy(_proxyClass, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(1), argThat(isArrayOf()), eq(3000)))
        	.thenReturn((Promise) Promises.resolved(30));
        
        assertEquals(30, proxy.length());
    }
	
    @Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testSuccessfulAsyncInvocation() throws Exception {
    	
        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, _mch, 3000);

        CharSequence proxy = createProxy(_proxyClass, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(2), argThat(isArrayOf(5,10)), eq(3000)))
        	.thenReturn((Promise) Promises.resolved("Hello"));
        
        assertEquals("Hello", ((AsyncDelegate)proxy).async(
        		CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10})
        		.getValue());
    }

    @Test
	public void testSuccessfulFireAndForget() throws Exception {
		
		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
				_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, _mch, 3000);
		
		CharSequence proxy = createProxy(_proxyClass, sih);
		
		assertTrue(((AsyncDelegate)proxy).execute(
				CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10}));
		
		verify(_mch).call(eq(FIRE_AND_FORGET), eq(2), argThat(isArrayOf(5, 10)), eq(3000));
	}

    @Test
    public void testInvocationFailureWithUndeclaredThrowable() {
    	ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, _mch, 3000);

        CharSequence proxy = createProxy(_proxyClass, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(1), argThat(isArrayOf()), eq(3000)))
        	.thenReturn(Promises.failed(new ClassNotFoundException("missing.class")));
        
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
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, _mch, 3000);

        CharSequence proxy = createProxy(_proxyClass, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(1), argThat(isArrayOf()), eq(3000)))
        	.thenReturn(Promises.failed(new IllegalStateException("length")));

        try {
            proxy.length();
            fail("expected IllegalStateException");
        }
        catch (IllegalStateException iex) {
            // expected
        }

    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
	public void testMethodsInObjectClassAreNotPropagated() {
        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, true, _mch, 3000);

        CharSequence proxy = createProxy(_proxyClass, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(1), argThat(isArrayOf()), eq(3000)))
        	.thenReturn((Promise) Promises.resolved(30));

        // length() is allowed to be forwarded
        assertEquals(30, proxy.length());
        
        Mockito.verify(_mch).call(eq(WITH_RETURN), eq(1), argThat(isArrayOf()), eq(3000));

        // toString() generates a proxy instance description containing the list of
        // proxied interfaces
        String toString = proxy.toString();
        assertTrue(toString.contains(("[java.lang.CharSequence, org.osgi.service.async.delegate.AsyncDelegate]")), ()->toString);

        // hashCode()is alway mapped to the associated InvocationHandler
        int hashCode = proxy.hashCode();
        assertEquals(sih.hashCode(), hashCode);

        // equals() with null is always false
        assertFalse(proxy.equals(null));

        // equals() with itself is always true
        assertEquals(proxy, proxy);

        // make sure no methods were triggered
        Mockito.verifyNoMoreInteractions(_mch);
    }
    
    @Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testSuccessfulAsyncInvocationDifferentAsync() throws Exception {
    	
        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClassWithDifferentAsyncDelegate, 
        		_proxyClassWithDifferentAsyncDelegateInterfaces, _differentPromise, true, _mch, 3000);

        CharSequence proxy = createProxy(_proxyClassWithDifferentAsyncDelegate, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(2), argThat(isArrayOf(5,10)), eq(3000)))
        	.thenReturn((Promise) Promises.resolved("Hello"));
        
        Method m = _proxyClassWithDifferentAsyncDelegate.getMethod("async", Method.class, Object[].class);
        
        Object returnedPromise = m.invoke(proxy, new Object[] {
        		CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10}});
		
        assertEquals("Hello", _differentPromise.getMethod("getValue").invoke(returnedPromise));
    }

    @Test
	public void testSuccessfulFireAndForgetDifferentAsync() throws Exception {
		
		ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClassWithDifferentAsyncDelegate, 
        		_proxyClassWithDifferentAsyncDelegateInterfaces, _differentPromise, true, _mch, 3000);

        CharSequence proxy = createProxy(_proxyClassWithDifferentAsyncDelegate, sih);
		
        Method m = _proxyClassWithDifferentAsyncDelegate.getMethod("execute", Method.class, Object[].class);
        
		assertTrue((Boolean) m.invoke(proxy, new Object[] {
				CharSequence.class.getMethod("subSequence", int.class, int.class), new Object[] {5, 10}}));
		
		verify(_mch).call(eq(FIRE_AND_FORGET), eq(2), argThat(isArrayOf(5, 10)), eq(3000));
	}
}
