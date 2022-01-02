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
package com.paremus.dosgi.net.impl;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.osgi.framework.Constants.FRAMEWORK_UUID;

import java.io.FilePermission;
import java.net.URI;
import java.security.Permission;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointPermission;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.converter.Converters;

import com.paremus.dosgi.net.client.ClientConnectionManager;
import com.paremus.dosgi.net.config.TransportConfig;
import com.paremus.dosgi.net.server.RemotingProvider;

import io.netty.channel.Channel;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.ImmediateEventExecutor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RemoteServiceAdminPermissionsTest {

    @Mock
    private Framework _framework, _childFramework;
    
    @Mock
    private Bundle _proxyBundle;
    @Mock
    private BundleContext _frameworkContext, _childFrameworkContext, _proxyContext;
    @Mock
    private Filter _filter;
    
    @Mock
    RemoteServiceAdminEventPublisher _publisher;
    @Mock
    RemotingProvider _insecureProvider, _secureProvider;
    @Mock
    ClientConnectionManager _clientConnectionManager;
    @Mock
    ProxyHostBundleFactory _proxyHostBundleFactory;

    EventExecutorGroup _serverWorkers = ImmediateEventExecutor.INSTANCE;

    EventExecutorGroup _clientWorkers = ImmediateEventExecutor.INSTANCE;
    @Mock
    Timer _timer;
    
    @Mock 
    RemoteServiceAdminFactoryImpl _factory;

    private RemoteServiceAdminImpl _rsa;

    @BeforeEach
    public void setUp() throws Exception {
        when(_framework.getBundleContext()).thenReturn(_frameworkContext);
        when(_frameworkContext.getProperty(FRAMEWORK_UUID)).thenReturn(new UUID(123, 456).toString());
        when(_childFramework.getBundleContext()).thenReturn(_childFrameworkContext);
        when(_childFrameworkContext.getProperty(FRAMEWORK_UUID)).thenReturn(new UUID(345, 678).toString());
        
        when(_insecureProvider.registerService(Mockito.any(), Mockito.any())).thenReturn(singleton(new URI("ptcp://127.0.0.1:1234")));
        when(_secureProvider.registerService(Mockito.any(), Mockito.any())).thenReturn(Collections.singleton(new URI("ptcp://127.0.0.1:1235")));

        // misc. RSA internals

        _rsa = new RemoteServiceAdminImpl(_factory, _framework, _publisher, asList(_insecureProvider, _secureProvider), 
        		_clientConnectionManager, Collections.singletonList("asyncInvocation"), _proxyHostBundleFactory, 
        		_serverWorkers, _clientWorkers, _timer, Converters.standardConverter().convert(
        				Collections.emptyMap()).to(TransportConfig.class));
        
        Mockito.when(_factory.getRemoteServiceAdmins()).thenReturn(Collections.singletonList(_rsa));
    }

    @Test
    public void testNoExportWithSecurityException() {
        ServiceReference<?> sref = mock(ServiceReference.class);

        try {
            // install failing SecurityManager
            System.setSecurityManager(new NoEndpointPermissionSecurityManager());

            // try to export service
            Collection<ExportRegistration> exregs = _rsa.exportService(sref, null);

            // returned ExportRegistration collection must not be null or empty
            assertNotNull(exregs);
            assertEquals(1, exregs.size());

            // one ExportRegistration must have been returned
            ExportRegistration exreg = exregs.iterator().next();
            assertNotNull(exreg);

            // ExportRegistration must have an exception
            Throwable t = exreg.getException();
            assertNotNull(t);
            assertTrue(t instanceof SecurityException, t.getClass().toString());
            assertTrue(t.getMessage().contains("export"), t.getMessage());
        }
        catch (SecurityException s) {
            fail("must not have propagated: " + s.getMessage());
        }
        finally {
            // get rid of security again
            System.setSecurityManager(null);
        }

        // service must not be exported
        assertEquals(0, _rsa.getExportedServices().size());
    }

    @Test
    public void testNoExportWithChildSecurityException() {
    	ServiceReference<?> sref = mock(ServiceReference.class);
    	
    	try {
    		// install failing SecurityManager
    		System.setSecurityManager(new NoEndpointPermissionSecurityManager());
    		
    		// try to export service
    		Collection<ExportRegistration> exregs = _rsa.exportService(_childFramework, sref, null);
    		
    		// returned ExportRegistration collection must not be null or empty
    		assertNotNull(exregs);
    		assertEquals(1, exregs.size());
    		
    		// one ExportRegistration must have been returned
    		ExportRegistration exreg = exregs.iterator().next();
    		assertNotNull(exreg);
    		
    		// ExportRegistration must have an exception
    		Throwable t = exreg.getException();
    		assertNotNull(t);
    		assertTrue(t instanceof SecurityException, t.getClass().toString());
    		assertTrue(t.getMessage().contains("export"), t.getMessage());
    	}
    	catch (SecurityException s) {
    		fail("must not have propagated: " + s.getMessage());
    	}
    	finally {
    		// get rid of security again
    		System.setSecurityManager(null);
    	}
    	
    	// service must not be exported
    	assertEquals(0, _rsa.getExportedServices().size());
    }

    @Test
    public void testNoImportWithSecurityException() {
        try {
            // install failing SecurityManager
            System.setSecurityManager(new NoEndpointPermissionSecurityManager());

            // try to import endpoint
            _rsa.importService(createEndpoint());
            fail("expected SecurityException");
        }
        catch (SecurityException t) {
        	assertTrue(t.getMessage().contains("import"), t.getMessage());
        }
        finally {
            // get rid of security again
            System.setSecurityManager(null);
        }

        // endpoint must not be imported
        assertEquals(0, _rsa.getImportedEndpoints().size());
    }

    @Test
    public void testNoChildImportWithSecurityException() {
    	try {
    		// install failing SecurityManager
    		System.setSecurityManager(new NoEndpointPermissionSecurityManager());
    		
    		// try to import endpoint
    		_rsa.importService(_childFramework, createEndpoint());
    		fail("expected SecurityException");
    	}
    	catch (SecurityException t) {
    		assertTrue(t.getMessage().contains("import"), t.getMessage());
    	}
    	finally {
    		// get rid of security again
    		System.setSecurityManager(null);
    	}
    	
    	// endpoint must not be imported
    	assertEquals(0, _rsa.getImportedEndpoints().size());
    }

    @Test
    public void getExportedServicesWithSecurityException() throws Exception {
        String serviceObject = "HelloWorld";

        Bundle serviceBundle = mock(Bundle.class);
        BundleContext serviceContext = mock(BundleContext.class);
        BundleWiring serviceWiring = mock(BundleWiring.class);

        when(serviceBundle.getBundleContext()).thenReturn(serviceContext);
        when(serviceBundle.adapt(BundleWiring.class)).thenReturn(serviceWiring);

        @SuppressWarnings("unchecked")
		ServiceReference<String> sref = mock(ServiceReference.class);

        when(serviceContext.getService(eq(sref))).thenReturn(serviceObject);

        when(sref.getBundle()).thenReturn(serviceBundle);
        when(sref.getProperty(same(Constants.OBJECTCLASS))).thenReturn(new String[]{"java.lang.String"});

        when(sref.getProperty(same(RemoteConstants.SERVICE_EXPORTED_INTERFACES))).thenReturn("*");
        when(sref.getProperty(same(Constants.SERVICE_ID))).thenReturn(1L);
        when(sref.getPropertyKeys()).thenReturn(
            new String[]{Constants.OBJECTCLASS, RemoteConstants.SERVICE_EXPORTED_INTERFACES});

        // export a service without security
        _rsa.exportService(sref, null);
        assertEquals(1, _rsa.getExportedServices().size());

        try {
            // install failing SecurityManager
            System.setSecurityManager(new NoEndpointPermissionSecurityManager());

            // try to get export services
            Collection<ExportReference> exrefs = _rsa.getExportedServices();

            // returned ExportReferences collection must not be null, but empty
            assertNotNull(exrefs);
            assertEquals(0, exrefs.size());
        }
        catch (SecurityException se) {
            fail("must not have propagated: " + se.getMessage());
        }
        finally {
            // get rid of security again
            System.setSecurityManager(null);
        }

        // try again without security: returned collection must not be empty
        assertEquals(1, _rsa.getExportedServices().size());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
    public void getImportedEndpointsWithSecurityException() {
    	
    	Channel channel = Mockito.mock(Channel.class);
    	
    	ServiceRegistration reg = Mockito.mock(ServiceRegistration.class);
    	ServiceReference ref = Mockito.mock(ServiceReference.class);
    	
    	Mockito.when(_proxyHostBundleFactory.getProxyBundle(_framework)).thenReturn(_proxyBundle);
    	Mockito.when(_proxyBundle.getBundleContext()).thenReturn(_proxyContext);
    	Mockito.when(_clientConnectionManager.getChannelFor(Mockito.any(), Mockito.any())).
    		thenReturn(channel);
    	Mockito.when(_proxyContext.registerService(Mockito.any(String[].class), Mockito.any(), Mockito.any()))
    		.thenReturn(reg);
    	Mockito.when(reg.getReference()).thenReturn(ref);
    	Mockito.when(ref.getProperty(Constants.SERVICE_ID)).thenReturn(1234L);
    	
        // import an endpoint
        assertNotNull(_rsa.importService(createEndpoint()));
        assertEquals(1, _rsa.getImportedEndpoints().size());

        try {
            // install failing SecurityManager
            System.setSecurityManager(new NoEndpointPermissionSecurityManager());

            // returned collection must not be null, but empty
            Collection<ImportReference> irefs = _rsa.getImportedEndpoints();
            assertNotNull(irefs);
            assertEquals(0, irefs.size());
        }
        catch (SecurityException se) {
            fail("must not have propagated: " + se.getMessage());
        }
        finally {
            // get rid of security again
            System.setSecurityManager(null);
        }

        // try again without security: returned collection must not be empty
        Collection<ImportReference> irefs = _rsa.getImportedEndpoints();
        assertNotNull(irefs);
        assertEquals(1, irefs.size());
    }

    private EndpointDescription createEndpoint() {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put(RemoteConstants.ENDPOINT_ID, new UUID(42, 42).toString());
        p.put(Constants.OBJECTCLASS, new String[]{"my.class"});
        p.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "com.paremus.dosgi.net");
        p.put("com.paremus.dosgi.net.methods", emptyList());
        p.put("com.paremus.dosgi.net", singletonList(URI.create("ptcp://127.0.0.1:1234")));
        return new EndpointDescription(p);
    }

    private static class NoEndpointPermissionSecurityManager extends SecurityManager {

        @Override
        public void checkPermission(Permission perm) {
            // only fail EndpointPermissions
        	if(perm instanceof FilePermission) {
        		//We may be loading the EndpointPermission class - say yes!
        		return;
        	} else if (perm instanceof EndpointPermission) {
                EndpointPermission epp = (EndpointPermission)perm;
                throw new SecurityException(epp.getName() + " is not allowed: [" + epp.getActions() + "]");
            }
        }

        @Override
        public void checkSecurityAccess(String target) {
            // disable all checks
        }

    }

}
