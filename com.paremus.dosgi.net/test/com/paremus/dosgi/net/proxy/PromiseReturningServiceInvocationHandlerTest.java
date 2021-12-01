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

import static com.paremus.dosgi.net.proxy.MethodCallHandler.CallType.WITH_RETURN;
import static java.util.Arrays.asList;
import static java.util.Arrays.deepEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.internal.verification.Description;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;

import com.paremus.dosgi.net.impl.ImportRegistrationImpl;


@ExtendWith(MockitoExtension.class)
public class PromiseReturningServiceInvocationHandlerTest {

    @Mock
    private ImportRegistrationImpl _importRegistration;
    @Mock
    MethodCallHandler _mch;
    @Mock
    Bundle _callingContext;
    
    
	private EndpointDescription _endpointDescription;
	
	private Class<?> _proxyClass;
	private List<Class<?>> _proxyClassInterfaces;
	private Class<?> _proxyClassWithDifferentPromise;
	private List<Class<?>> _proxyClassWithDifferentPromiseInterfaces;
	private Class<?> _differentPromise;
    
	@BeforeEach
    public void setUp() throws ClassNotFoundException {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(RemoteConstants.ENDPOINT_ID, "my.endpoint.id");
        map.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
        map.put(Constants.OBJECTCLASS, new String[] {TestReturnsPromise.class.getName()});
        map.put("com.paremus.dosgi.net.methods", new String[] {"1=coprime[long,long]"});
        _endpointDescription = new EndpointDescription(map);

        _proxyClass = Proxy.getProxyClass(new ClassLoader(){}, TestReturnsPromise.class);
        _proxyClassInterfaces = asList(TestReturnsPromise.class);
        
        ClassLoader differentClassLoader = getSeparateClassLoader();
        
        _proxyClassWithDifferentPromise = Proxy.getProxyClass(differentClassLoader, 
        		differentClassLoader.loadClass(TestReturnsPromise.class.getName()));
        _proxyClassWithDifferentPromiseInterfaces = asList(
        		differentClassLoader.loadClass(TestReturnsPromise.class.getName()));
        _differentPromise = differentClassLoader.loadClass(Promise.class.getName());
        
        Map<Integer, String> methods = new HashMap<>();
        methods.put(1, "coprime[long,long]");
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
	
			private Object check;
			
			@Override
			public boolean matches(Object[] item) {
				return (o.length == 0 && item == null) || 
						(item instanceof Object[] ? deepEquals(o, (Object[]) item) : false);
			}
	
			public void describeTo(Description description) {
				description.description(String.format("The object arrays were not equal. Expected %s but got %s",
						Arrays.toString(o), check instanceof Object[] ? Arrays.toString((Object[]) check) :
							String.valueOf(check)));
			}
		};
	}

	@Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testSuccessfulInvocation() throws Exception {
    	
        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassInterfaces, Promise.class, false, _mch, 3000);

        TestReturnsPromise proxy = (TestReturnsPromise) createProxy(_proxyClass, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(1), argThat(isArrayOf(7L, 42L)), eq(3000)))
        	.thenReturn((Promise) Promises.resolved(false));
        
        assertFalse(proxy.coprime(7, 42).getValue());
    }
	
	@Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testSuccessfulInvocationDifferentPromise() throws Exception {
    	
        ServiceInvocationHandler sih = new ServiceInvocationHandler(_importRegistration, _endpointDescription,
        		_callingContext, _proxyClass, _proxyClassWithDifferentPromiseInterfaces, _differentPromise, false, _mch, 3000);

        Object proxy = createProxy(_proxyClassWithDifferentPromise, sih);
        
        when(_mch.call(eq(WITH_RETURN), eq(1), argThat(isArrayOf(14L,15L)), eq(3000)))
        	.thenReturn((Promise) Promises.resolved(true));
        
        Method m = _proxyClassWithDifferentPromise.getMethod("coprime", long.class, long.class);
        
        Object returnedPromise = m.invoke(proxy, new Object[] {14L, 15L});
		
        assertTrue((Boolean) _differentPromise.getMethod("getValue").invoke(returnedPromise));
    }
}
