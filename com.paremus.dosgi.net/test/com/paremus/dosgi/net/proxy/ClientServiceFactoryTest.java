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

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.FrameworkUtil2;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.async.delegate.AsyncDelegate;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

import com.paremus.dosgi.net.impl.ImportRegistrationImpl;
import com.paremus.dosgi.net.proxy.ClientServiceFactory;
import com.paremus.dosgi.net.proxy.MethodCallHandler;
import com.paremus.dosgi.net.proxy.MethodCallHandlerFactory;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ClientServiceFactoryTest {

    private final String[] _serviceObjectClass = new String[]{Foo.class.getName()};

    @Mock
    private MethodCallHandlerFactory _methodCallHandlerFactory;
    @Mock
    private MethodCallHandler _methodCallHandler;
    @Mock
    private ImportRegistrationImpl _importRegistration;
    @Mock
    private BundleContext _callingContext;
    @Mock
    private Bundle _callingBundle, _asyncExportingBundle;
    @Mock
    private BundleWiring _callingBundleWiring, _asyncExportingBundleWiring;
    
    private final Map<String, Class<?>> _bundleClassSpace = new HashMap<>();

    private EndpointDescription _endpointDescription;
    
    private ClientServiceFactory _csf;

    
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public void setUp() throws Exception {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(RemoteConstants.ENDPOINT_ID, "my.endpoint.id");
        map.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
        map.put(Constants.OBJECTCLASS, _serviceObjectClass);
        map.put("com.paremus.dosgi.net.methods", new String[] {"1=getBar[]","2=getName[]"});
        _endpointDescription = new EndpointDescription(map);
        
        Map<Integer, String> methods = new HashMap<>();
        methods.put(1, "getBar[]");
        methods.put(2, "getName[]");
        when(_importRegistration.getMethodMappings()).thenReturn(methods);

        _csf = new ClientServiceFactory(_importRegistration, _endpointDescription, _methodCallHandlerFactory, 3000);
        

        when(_callingBundle.getBundleContext()).thenReturn(_callingContext);
        when(_callingBundle.getSymbolicName()).thenReturn("RequestingBundle");
        when(_callingBundle.adapt(BundleWiring.class)).thenReturn(_callingBundleWiring);
        when(_callingBundle.loadClass(Foo.class.getName())).thenReturn((Class)Foo.class);
        when(_callingBundleWiring.getClassLoader()).thenReturn(new ClassLoader() {
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if(name.startsWith("java")) {
					return super.loadClass(name, resolve);
				}
				return ofNullable(_bundleClassSpace.get(name))
						.orElseThrow(() -> new ClassNotFoundException(name));
			}
        });
        _bundleClassSpace.put(Foo.class.getName(), Foo.class);

        when(_callingContext.getBundle()).thenReturn(_callingBundle);
        
        when(_asyncExportingBundle.adapt(BundleWiring.class)).thenReturn(_asyncExportingBundleWiring);
        
        when(_asyncExportingBundleWiring.getCapabilities("osgi.wiring.package")).thenReturn(
        		asList(new PackageCapability("org.osgi.service.async.delegate", new Version(1,0,0))));
        when(_asyncExportingBundleWiring.getClassLoader()).thenReturn(new ClassLoader() {
        	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if(name.startsWith("java")) {
					return super.loadClass(name, resolve);
				} else if (name.equals(AsyncDelegate.class.getName())) {
					return AsyncDelegate.class;
				} else if (name.equals(Promise.class.getName())) {
					return Promise.class;
				}
				throw new ClassNotFoundException(name);
			}
        });

        
        when(_methodCallHandlerFactory.create(_callingBundle)).thenReturn(_methodCallHandler);
    }
    
    public void tearDown() {
    	FrameworkUtil2.clear();
    }

    @Test
    public void testGetServiceNoPromiseOrAsync() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	
    	when(_callingContext.getBundles()).thenReturn(new Bundle[]{_asyncExportingBundle, _callingBundle});
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
        assertTrue(o instanceof Foo);
        assertTrue(o instanceof AsyncDelegate);
    }

    @Test
    public void testGetServiceNoPromiseOrAsyncAtAll() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    	.thenThrow(new ClassNotFoundException());
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    	.thenThrow(new ClassNotFoundException());
    	
    	when(_callingContext.getBundles()).thenReturn(new Bundle[]{_callingBundle});
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
    	assertTrue(o instanceof Foo);
    	assertFalse(o instanceof AsyncDelegate);
    }
    
    @Test
    public void testGetServiceNoPromiseOrAsyncWithBarInClassSpace() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	_bundleClassSpace.put(Bar.class.getName(), Bar.class);
    	
    	when(_callingContext.getBundles()).thenReturn(new Bundle[]{_asyncExportingBundle, _callingBundle});
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
        assertTrue(o instanceof Foo);
        assertTrue(o instanceof AsyncDelegate);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public void testGetServiceSamePromiseNoAsync() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenReturn((Class)Promise.class);
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	
    	_bundleClassSpace.put(Promise.class.getName(), Promise.class);
    	
    	when(_callingContext.getBundles()).thenReturn(new Bundle[]{_asyncExportingBundle, _callingBundle});
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
    	assertTrue(o instanceof Foo);
    	assertTrue(o instanceof AsyncDelegate);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void testGetServiceSamePromiseNoAsyncSingleBundleProvider() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenReturn((Class)Promise.class);
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	
    	_bundleClassSpace.put(Promise.class.getName(), Promise.class);
    	FrameworkUtil2.registerBundleFor(Promise.class, _asyncExportingBundle);
    	Mockito.when(_asyncExportingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenReturn((Class) AsyncDelegate.class);
    	
    	when(_callingContext.getBundles()).thenReturn(new Bundle[]{_asyncExportingBundle, _callingBundle});
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
    	assertTrue(o instanceof Foo);
    	assertTrue(o instanceof AsyncDelegate);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void testGetServiceSamePromiseAndAsync() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenReturn((Class)Promise.class);
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenReturn((Class)AsyncDelegate.class);
    	
    	_bundleClassSpace.put(Promise.class.getName(), Promise.class);
    	_bundleClassSpace.put(AsyncDelegate.class.getName(), AsyncDelegate.class);
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
    	assertTrue(o instanceof Foo);
    	assertTrue(o instanceof AsyncDelegate);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void testGetServiceDifferentPromiseAndAsync() throws Exception {
    	
    	ClassLoader differentSpace = getSeparateClassLoader();
    	
    	Class<?> differentPromise = differentSpace.loadClass(Promise.class.getName());
    	Class<?> differentDeferred = differentSpace.loadClass(Deferred.class.getName());
    	Class<?> differentAsync = differentSpace.loadClass(AsyncDelegate.class.getName());
    	
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenReturn((Class)differentPromise);
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenReturn((Class)differentAsync);
    	
    	_bundleClassSpace.put(Promise.class.getName(), differentPromise);
    	_bundleClassSpace.put(Deferred.class.getName(), differentDeferred);
    	_bundleClassSpace.put(AsyncDelegate.class.getName(), differentAsync);
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
    	assertTrue(o instanceof Foo);
    	assertFalse(o instanceof AsyncDelegate);
    	assertTrue(differentAsync.isInstance(o));
    }
    
    @Test
    public void testGetServiceNoPromiseOrAsyncDifferentPreferred() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	
    	Bundle differentAsyncBundle = getDifferentAsyncBundle();
    	
		when(_callingContext.getBundles()).thenReturn(new Bundle[]{differentAsyncBundle, _asyncExportingBundle, _callingBundle});
    	
    	Object o = _csf.getService(_callingBundle, null);
    	
        assertTrue(o instanceof Foo);
        assertFalse(o instanceof AsyncDelegate);
        assertTrue(differentAsyncBundle.adapt(BundleWiring.class).getClassLoader().loadClass(AsyncDelegate.class.getName()).isInstance(o));
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public void testGetServiceSamePromiseNoAsyncOverridesPreferral() throws Exception {
    	Mockito.when(_callingBundle.loadClass(Promise.class.getName()))
    		.thenReturn((Class)Promise.class);
    	Mockito.when(_callingBundle.loadClass(AsyncDelegate.class.getName()))
    		.thenThrow(new ClassNotFoundException());
    	
    	_bundleClassSpace.put(Promise.class.getName(), Promise.class);

    	Bundle differentAsyncBundle = getDifferentAsyncBundle();
		when(_callingContext.getBundles()).thenReturn(new Bundle[]{differentAsyncBundle, _asyncExportingBundle, _callingBundle});
		
    	Object o = _csf.getService(_callingBundle, null);
    	
    	assertTrue(o instanceof Foo);
    	assertTrue(o instanceof AsyncDelegate);
    }
    
    
    private Bundle getDifferentAsyncBundle() {
    	Bundle different = Mockito.mock(Bundle.class);
    	BundleWiring differentWiring = Mockito.mock(BundleWiring.class);
    	
        when(different.adapt(BundleWiring.class)).thenReturn(differentWiring);
        
        when(differentWiring.getCapabilities("osgi.wiring.package")).thenReturn(
        		asList(new PackageCapability("org.osgi.service.async.delegate", new Version(1,0,4))));
        
        when(differentWiring.getClassLoader()).thenReturn(getSeparateClassLoader());
        
        BundleWire wire = Mockito.mock(BundleWire.class);
        when(wire.getCapability()).thenReturn(new PackageCapability("org.osgi.service.async.delegate", 
        		new Version(1, 0, 4)));
        when(differentWiring.getProvidedWires("osgi.wiring.package")).thenReturn(asList(wire, wire));
        
        return different;
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
    			
				InputStream resourceAsStream = ClientServiceFactoryTest.this.getClass()
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

	private static class PackageCapability implements BundleCapability {

		private final String packageName;
		private final Version packageVersion;
		
		public PackageCapability(String packageName, Version packageVersion) {
			this.packageName = packageName;
			this.packageVersion = packageVersion;
		}

		@Override
		public BundleRevision getRevision() {
			return null;
		}

		@Override
		public String getNamespace() {
			return "osgi.wiring.package";
		}

		@Override
		public Map<String, String> getDirectives() {
			return Collections.emptyMap();
		}

		@Override
		public Map<String, Object> getAttributes() {
			Map<String, Object> attrs = new HashMap<>();
			attrs.put("osgi.wiring.package", packageName);
			attrs.put("version", packageVersion);
			return attrs;
		}

		@Override
		public BundleRevision getResource() {
			return null;
		}
	}
	
}