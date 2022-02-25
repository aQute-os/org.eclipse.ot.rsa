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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.osgi.framework.Constants.FRAMEWORK_UUID;
import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.framework.Constants.SERVICE_ID;
import static org.osgi.framework.ServiceEvent.UNREGISTERING;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.SERVICE_EXPORTED_INTERFACES;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.converter.Converters;

import com.paremus.dosgi.net.client.ClientConnectionManager;
import com.paremus.dosgi.net.config.TransportConfig;
import com.paremus.dosgi.net.server.RemotingProvider;

import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RemoteServiceAdminImplTest {

    private RemoteServiceAdminImpl _rsa;

    @Mock
    private RemoteServiceAdminFactoryImpl _factory;

    @Mock
    private Framework _framework, _childFramework;
    @Mock
    private Bundle _rsaBundle, _serviceBundle, _proxyBundle;
    @Mock
    private BundleWiring _wiring;
    @Mock
    private BundleContext _frameworkContext, _childFrameworkContext, _rsaContext, _proxyContext, _serviceContext;
    @SuppressWarnings("rawtypes")
	@Mock
    private ServiceRegistration _imported;
    @SuppressWarnings("rawtypes")
	@Mock
    private ServiceReference _importedRef;
    @Mock
    private Filter _filter;
    @Mock
    private ServiceReference<String> _serviceReference;
    @Mock
    RemoteServiceAdminEventPublisher _publisher;
    @Mock
    RemotingProvider _insecureProvider, _secureProvider;
    @Mock
    ClientConnectionManager _clientConnectionManager;
    @Mock
    ProxyHostBundleFactory _proxyHostBundleFactory;

    EventExecutorGroup _serverWorkers;
    EventExecutorGroup _clientWorkers;

    @Mock
    Timer _timer;

    UUID _rootFrameworkId = UUID.randomUUID();
    UUID _childFrameworkId = UUID.randomUUID();

	private List<String> intents;

    @SuppressWarnings("unchecked")
    @BeforeEach
	public void setUp() throws Exception {
        _serverWorkers = new DefaultEventExecutorGroup(1);
        _clientWorkers = new DefaultEventExecutorGroup(1);

        when(_serviceBundle.getBundleContext()).thenReturn(_serviceContext);
        when(_serviceBundle.adapt(BundleWiring.class)).thenReturn(_wiring);

        when(_serviceReference.getBundle()).thenReturn(_serviceBundle);
        when(_serviceReference.getPropertyKeys()).thenReturn(
            new String[]{OBJECTCLASS, SERVICE_EXPORTED_INTERFACES, SERVICE_ID});
        when(_serviceReference.getProperty(OBJECTCLASS)).thenReturn(
            new String[]{"java.lang.String", "java.lang.CharSequence"});
        when(_serviceReference.getProperty(SERVICE_EXPORTED_INTERFACES)).thenReturn("*");
        when(_serviceReference.getProperty(SERVICE_ID)).thenReturn(42L);

        when(_secureProvider.isSecure()).thenReturn(true);
        when(_secureProvider.registerService(ArgumentMatchers.any(), ArgumentMatchers.any()))
        	.thenReturn(Collections.singleton(new URI("ptcps://localhost:12345")));
        when(_insecureProvider.registerService(ArgumentMatchers.any(), ArgumentMatchers.any()))
        	.thenReturn(Collections.singleton(new URI("ptcp://localhost:23456")));

        when(_framework.getBundleContext()).thenReturn(_frameworkContext);
        when(_frameworkContext.getProperty(FRAMEWORK_UUID)).thenReturn(_rootFrameworkId.toString());
		when(_framework.getState()).thenReturn(Bundle.ACTIVE);
		when(_frameworkContext.getBundle()).thenReturn(_framework);
        when(_proxyHostBundleFactory.getProxyBundle(ArgumentMatchers.any())).thenReturn(_proxyBundle);
        when(_proxyBundle.getBundleContext()).thenReturn(_proxyContext);
        when(_proxyContext.registerService(ArgumentMatchers.any(String[].class), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(_imported);
        when(_imported.getReference()).thenReturn(_importedRef);
        when(_importedRef.getProperty(Constants.SERVICE_ID)).thenReturn(5L);

        intents = new ArrayList<>(Collections.singletonList("asyncInvocation"));

        _rsa = new RemoteServiceAdminImpl(_factory, _framework, _publisher, asList(_insecureProvider, _secureProvider),
        		_clientConnectionManager, intents, _proxyHostBundleFactory,
        		_serverWorkers, _clientWorkers, _timer, Converters.standardConverter().convert(
        				Collections.emptyMap()).to(TransportConfig.class));

        Mockito.when(_factory.getRemoteServiceAdmins()).thenReturn(Collections.singletonList(_rsa));
    }

    @AfterEach
    public void tearDown() {
    	_clientWorkers.shutdownGracefully();
    	_serverWorkers.shutdownGracefully();
    }

    @Test
    public void testNoExportOfNullService() throws Exception {
        when(_serviceContext.getService(_serviceReference)).thenReturn(null);
        Throwable t = doNoExport(_serviceReference, null);
        assertTrue(t instanceof ServiceException, "T is of the wrong type: " + t.getClass());
        assertTrue(t.getMessage().contains("service object was null"), t.getMessage());
    }

    @Test
    public void testNoExportOnServiceException() throws Exception {
        Throwable serviceException = new ServiceException("boo", ServiceException.FACTORY_EXCEPTION);
        when(_serviceContext.getService(_serviceReference)).thenThrow(serviceException);
        Throwable t = doNoExport(_serviceReference, serviceException);
        assertSame(t, serviceException);
    }

    @Test
    public void testNoExportOnSecurityException() throws Exception {
        Throwable serviceException = new SecurityException("ACCESS DENIED");
        when(_serviceContext.getService(_serviceReference)).thenThrow(serviceException);
        Throwable t = doNoExport(_serviceReference, serviceException);
        assertSame(t, serviceException);
    }

    @Test
    public void testNoExportOnIllegalStateException() throws Exception {
        Throwable serviceException = new IllegalStateException("o noes");
        when(_serviceContext.getService(_serviceReference)).thenThrow(serviceException);
        Throwable t = doNoExport(_serviceReference, serviceException);
        assertSame(t, serviceException);
    }

    @Test
    public void testNoExportOnUnknownException() throws Exception {
        Throwable serviceException = new NoSuchElementException("unexpected");
        when(_serviceContext.getService(_serviceReference)).thenThrow(serviceException);
        Throwable t = doNoExport(_serviceReference, serviceException);
        assertSame(t, serviceException);
    }

    private Throwable doNoExport(ServiceReference<?> sref, Throwable serviceException) throws Exception {
        // must not return an empty list
        Collection<ExportRegistration> exRefs = _rsa.exportService(sref, null);
        assertNotNull(exRefs, "returned export list must never be null");
        assertEquals(1, exRefs.size());
        ExportRegistration failed = exRefs.iterator().next();
        // failed export must have the passed exception
        Throwable t = failed.getException();
        assertNotNull(t, "failed export must have exception");
        // registered exports must still be empty
        assertEquals(0, _rsa.getExportedServices().size());
        return t;
    }

    @Test
    public void testExportWithDefaultConfigurationType() throws Exception {
		// we need a valid service for this test
		when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");

		// must return a valid export
		Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
		assertNotNull(exRefs);
		assertEquals(1, exRefs.size());

		EndpointDescription exportedEndpoint = exRefs.iterator().next().getExportReference().getExportedEndpoint();

		assertEquals(asList("com.paremus.dosgi.net"), exportedEndpoint.getConfigurationTypes());
		assertEquals(asList("java.lang.String", "java.lang.CharSequence"), exportedEndpoint.getInterfaces());
		assertEquals(42L, exportedEndpoint.getServiceId());
		assertEquals(_rootFrameworkId.toString(), exportedEndpoint.getFrameworkUUID());
		assertEquals(Collections.singletonList("asyncInvocation"), exportedEndpoint.getIntents());
	}

    @Test
	public void testExportWithSpecificConfigurationType() throws Exception {
		// we need a valid service for this test
		when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");
		when(_serviceReference.getProperty(RemoteConstants.SERVICE_EXPORTED_CONFIGS)).thenReturn("com.paremus.dosgi.net");

		List<String> keyList = new ArrayList<>(asList(_serviceReference.getPropertyKeys()));
    	keyList.add(RemoteConstants.SERVICE_EXPORTED_CONFIGS);

    	when(_serviceReference.getPropertyKeys()).thenReturn(keyList.toArray(new String[0]));

		// must return a valid export
		Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
		assertNotNull(exRefs);
		assertEquals(1, exRefs.size());

		EndpointDescription exportedEndpoint = exRefs.iterator().next().getExportReference().getExportedEndpoint();

		assertEquals(asList("com.paremus.dosgi.net"), exportedEndpoint.getConfigurationTypes());
		assertEquals(asList("java.lang.String", "java.lang.CharSequence"), exportedEndpoint.getInterfaces());
		assertEquals(42L, exportedEndpoint.getServiceId());
		assertEquals(_rootFrameworkId.toString(), exportedEndpoint.getFrameworkUUID());
		assertEquals(Collections.singletonList("asyncInvocation"), exportedEndpoint.getIntents());
	}

    @Test
	public void testExportWithMixedConfigurationTypes() throws Exception {
		// we need a valid service for this test
		when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");
		when(_serviceReference.getProperty(RemoteConstants.SERVICE_EXPORTED_CONFIGS))
			.thenReturn(asList("unsupported.config.type", "com.paremus.dosgi.net"));

		List<String> keyList = new ArrayList<>(asList(_serviceReference.getPropertyKeys()));
    	keyList.add(RemoteConstants.SERVICE_EXPORTED_CONFIGS);

    	when(_serviceReference.getPropertyKeys()).thenReturn(keyList.toArray(new String[0]));

		// must return a valid export
		Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
		assertNotNull(exRefs);
		assertEquals(1, exRefs.size());

		EndpointDescription exportedEndpoint = exRefs.iterator().next().getExportReference().getExportedEndpoint();

		assertEquals(asList("com.paremus.dosgi.net"), exportedEndpoint.getConfigurationTypes());
		assertEquals(asList("java.lang.String", "java.lang.CharSequence"), exportedEndpoint.getInterfaces());
		assertEquals(42L, exportedEndpoint.getServiceId());
		assertEquals(_rootFrameworkId.toString(), exportedEndpoint.getFrameworkUUID());
		assertEquals(Collections.singletonList("asyncInvocation"), exportedEndpoint.getIntents());
	}

    @Test
	public void testExportWithSpecificExportedInterface() throws Exception {
		// we need a valid service for this test
		when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");
		when(_serviceReference.getProperty(RemoteConstants.SERVICE_EXPORTED_INTERFACES))
			.thenReturn("java.lang.CharSequence");

		// must return a valid export
		Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
		assertNotNull(exRefs);
		assertEquals(1, exRefs.size());

		EndpointDescription exportedEndpoint = exRefs.iterator().next().getExportReference().getExportedEndpoint();

		assertEquals(asList("com.paremus.dosgi.net"), exportedEndpoint.getConfigurationTypes());
		assertEquals(asList("java.lang.CharSequence"), exportedEndpoint.getInterfaces());
		assertEquals(42L, exportedEndpoint.getServiceId());
		assertEquals(_rootFrameworkId.toString(), exportedEndpoint.getFrameworkUUID());
		assertEquals(Collections.singletonList("asyncInvocation"), exportedEndpoint.getIntents());
	}

    @Test
	public void testNoExportForWrongConfigurationType() throws Exception {
    	// we need a valid service for this test
    	when(_serviceReference.getProperty(RemoteConstants.SERVICE_EXPORTED_CONFIGS))
    		.thenReturn("unsupported.config.type");

    	List<String> keyList = new ArrayList<>(asList(_serviceReference.getPropertyKeys()));
    	keyList.add(RemoteConstants.SERVICE_EXPORTED_CONFIGS);

    	when(_serviceReference.getPropertyKeys()).thenReturn(keyList.toArray(new String[0]));


    	// must return a valid export
    	Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
    	assertNotNull(exRefs);
    	assertEquals(0, exRefs.size());
    }

    @Test
	public void testExportWithSupportedIntents() throws Exception {
    	// we need a valid service for this test
    	when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");
    	when(_serviceReference.getProperty(RemoteConstants.SERVICE_EXPORTED_INTENTS))
    		.thenReturn("asyncInvocation");

    	List<String> keyList = new ArrayList<>(asList(_serviceReference.getPropertyKeys()));
    	keyList.add(RemoteConstants.SERVICE_EXPORTED_INTENTS);

    	when(_serviceReference.getPropertyKeys()).thenReturn(keyList.toArray(new String[0]));


    	// must return a valid export
    	Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
		assertNotNull(exRefs);
		assertEquals(1, exRefs.size());

		EndpointDescription exportedEndpoint = exRefs.iterator().next().getExportReference().getExportedEndpoint();

		assertEquals(asList("com.paremus.dosgi.net"), exportedEndpoint.getConfigurationTypes());
		assertEquals(asList("java.lang.String", "java.lang.CharSequence"), exportedEndpoint.getInterfaces());
		assertEquals(42L, exportedEndpoint.getServiceId());
		assertEquals(_rootFrameworkId.toString(), exportedEndpoint.getFrameworkUUID());
		assertEquals(Collections.singletonList("asyncInvocation"), exportedEndpoint.getIntents());
    }

    @Test
	public void testNoExportWithUnsupportedIntents() throws Exception {
    	// we need a valid service for this test
    	when(_serviceReference.getProperty(RemoteConstants.SERVICE_EXPORTED_INTENTS))
    		.thenReturn("unsupported.intent");

    	List<String> keyList = new ArrayList<>(asList(_serviceReference.getPropertyKeys()));
    	keyList.add(RemoteConstants.SERVICE_EXPORTED_INTENTS);

    	when(_serviceReference.getPropertyKeys()).thenReturn(keyList.toArray(new String[0]));


    	// must return a valid export
    	Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
    	assertNotNull(exRefs);
    	assertEquals(0, exRefs.size());
    }

    @Test
	public void testNoExportWithUnsupportedExtraIntents() throws Exception {
		// we need a valid service for this test
		when(_serviceReference.getProperty(RemoteConstants.SERVICE_EXPORTED_INTENTS_EXTRA))
		.thenReturn("unsupported.intent");

		List<String> keyList = new ArrayList<>(asList(_serviceReference.getPropertyKeys()));
		keyList.add(RemoteConstants.SERVICE_EXPORTED_INTENTS_EXTRA);

		when(_serviceReference.getPropertyKeys()).thenReturn(keyList.toArray(new String[0]));


		// must return a valid export
		Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
		assertNotNull(exRefs);
		assertEquals(0, exRefs.size());
	}

    @Test
	public void testNoExportWithBadSerializer() throws Exception {
		// we need a valid service for this test
		when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");
		when(_serviceReference.getProperty("com.paremus.dosgi.net.serialization"))
			.thenReturn("unsupported.serializer");

		List<String> keyList = new ArrayList<>(asList(_serviceReference.getPropertyKeys()));
		keyList.add("com.paremus.dosgi.net.serialization");

		when(_serviceReference.getPropertyKeys()).thenReturn(keyList.toArray(new String[0]));

		try {
			_rsa.exportService(_serviceReference, null);
			fail("Should fail with IllegalArgumentException");
		} catch (IllegalArgumentException uoe) {}
	}

    @Test
    public void testInsecureExportGetsBothURIs() throws Exception {
    	// we need a valid service for this test
    	when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");

    	// must return a valid export
    	Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
    	assertNotNull(exRefs);
    	assertEquals(1, exRefs.size());
    	// a valid export must not have an exception
    	assertNull(exRefs.iterator().next().getException());

    	EndpointDescription exportedEndpoint = exRefs.iterator().next().getExportReference()
    			.getExportedEndpoint();
		@SuppressWarnings("unchecked")
		List<String> list = (List<String>) exportedEndpoint.getProperties().get("com.paremus.dosgi.net");
    	assertEquals(asList("ptcp://localhost:23456", "ptcps://localhost:12345"), list);
    	assertEquals(asList("java.lang.String", "java.lang.CharSequence"), exportedEndpoint.getInterfaces());
    	assertEquals(42L, exportedEndpoint.getServiceId());
		assertEquals(_rootFrameworkId.toString(), exportedEndpoint.getFrameworkUUID());
    }

    @Test
    public void testSecureExportGetsSecureURI() throws Exception {
    	// we need a valid service for this test
    	when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");
    	intents.add("confidentiality.message");

    	// must return a valid export
    	Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference,
    			Collections.singletonMap(RemoteConstants.SERVICE_EXPORTED_INTENTS, "confidentiality.message"));
    	assertNotNull(exRefs);
    	assertEquals(1, exRefs.size());
    	// a valid export must not have an exception
    	assertNull(exRefs.iterator().next().getException());

    	EndpointDescription exportedEndpoint = exRefs.iterator().next().getExportReference()
    			.getExportedEndpoint();
		@SuppressWarnings("unchecked")
		List<String> list = (List<String>) exportedEndpoint.getProperties().get("com.paremus.dosgi.net");
    	assertEquals(asList("ptcps://localhost:12345"), list);
    	assertEquals(asList("java.lang.String", "java.lang.CharSequence"), exportedEndpoint.getInterfaces());
    	assertEquals(42L, exportedEndpoint.getServiceId());
		assertEquals(_rootFrameworkId.toString(), exportedEndpoint.getFrameworkUUID());
    }

    @Test
    public void testClosingExportUngetsService() throws Exception {
        // we need a valid service for this test
        when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");

        // must return a valid export
        Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
        assertNotNull(exRefs);
        assertEquals(1, exRefs.size());
        // a valid export must not have an exception
        assertNull(exRefs.iterator().next().getException());

        // make sure export is registered with RSA
        assertEquals(1, _rsa.getExportedServices().size());

        // verify that getService() was called
        verify(_serviceContext).getService(_serviceReference);

        // must return a separate valid export
        Collection<ExportRegistration> exRefs2 = _rsa.exportService(_serviceReference, null);
        assertNotNull(exRefs2);
        assertEquals(1, exRefs2.size());
        // a valid export must not have an exception
        assertNull(exRefs2.iterator().next().getException());

        // verify that getService() was called a second time
        verify(_serviceContext, times(2)).getService(_serviceReference);

        // make sure both exports are registered with RSA
        assertEquals(2, _rsa.getExportedServices().size());

        // now close export2
        exRefs2.iterator().next().close();

        // verify that ungetService() was called once
        verify(_serviceContext).ungetService(_serviceReference);

        // now close export1
        exRefs.iterator().next().close();

        // verify that ungetService() was called twice now
        verify(_serviceContext, times(2)).ungetService(_serviceReference);

        // make sure export is gone from RSA
        assertEquals(0, _rsa.getExportedServices().size());
    }

    @Test
    public void testUpdateExport() throws Exception {
		// we need a valid service for this test
		when(_serviceContext.getService(_serviceReference)).thenReturn("MyServiceObject");

		// must return a valid export
		Collection<ExportRegistration> exRefs = _rsa.exportService(_serviceReference, null);
		assertNotNull(exRefs);
		assertEquals(1, exRefs.size());

		ExportRegistration exportRegistration = exRefs.iterator().next();
		assertFalse(exportRegistration.getExportReference().getExportedEndpoint()
				.getProperties().containsKey("foo"));

		exportRegistration.update(Collections.singletonMap("foo", "bar"));

		assertEquals("bar", exportRegistration.getExportReference().getExportedEndpoint()
				.getProperties().get("foo"));

		//Update should remember the previous value
		exportRegistration.update(null);
		assertEquals("bar", exportRegistration.getExportReference().getExportedEndpoint()
				.getProperties().get("foo"));

		//Update should remember the previous value
		exportRegistration.update(null);
		assertEquals("bar", exportRegistration.getExportReference().getExportedEndpoint()
				.getProperties().get("foo"));
	}

    @Test
    public void testNoImportWithUnsupportedConfigType() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put(RemoteConstants.ENDPOINT_ID, "my.endpoint.id");
        p.put(Constants.OBJECTCLASS, new String[]{"my.service.class"});
        p.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "unsupportedConfiguration");
        EndpointDescription endpoint = new EndpointDescription(p);

        // must be null as the endpoint doesn't contain any usable configurations
        assertNull(_rsa.importService(endpoint));
        // must be empty
        assertEquals(0, _rsa.getImportedEndpoints().size());
    }

    @Test
    public void testImportService() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put(RemoteConstants.ENDPOINT_ID, new UUID(78, 910).toString());
        p.put(Constants.OBJECTCLASS, new String[]{"my.primary.role", "my.secondary.role"});
        p.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "com.paremus.dosgi.net");
        p.put("com.paremus.dosgi.net", "ptcp://localhost:1234");
        p.put("com.paremus.dosgi.net.methods", new String[] {"1=length[]","2=subSequence[int,int]"});
        EndpointDescription epd = new EndpointDescription(p);

        Mockito.when(_clientConnectionManager.getChannelFor( ArgumentMatchers.eq(new URI("ptcp://localhost:1234")),
        		ArgumentMatchers.any())).thenAnswer(Mockito.RETURNS_MOCKS);

        ImportRegistration ireg = _rsa.importService(epd);
        assertNotNull(ireg);

        assertEquals(1, _rsa.getImportedEndpoints().size());

        // import the same endpoint once more -> should get a separate ImportRegistration
        ImportRegistration ireg2 = _rsa.importService(epd);
        assertNotNull(ireg2);
        ImportReference[] importedEndpoints = _rsa.getImportedEndpoints().toArray(new ImportReference[0]);
		assertEquals(2, importedEndpoints.length);
		assertTrue((ireg.getImportReference() == importedEndpoints[0] && ireg2.getImportReference() == importedEndpoints[1]) ^
				(ireg.getImportReference() == importedEndpoints[1] && ireg2.getImportReference() == importedEndpoints[0]));

        // now remove the registration
        // closing the second close() must close the first import
        ireg2.close();
        assertEquals(1, _rsa.getImportedEndpoints().size());
        // second close() should really remove the import
        ireg.close();
        assertEquals(0, _rsa.getImportedEndpoints().size());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
	public void testUpdateImport() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put(RemoteConstants.ENDPOINT_ID, new UUID(78, 910).toString());
        p.put(Constants.OBJECTCLASS, new String[]{"my.primary.role", "my.secondary.role"});
        p.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "com.paremus.dosgi.net");
        p.put("com.paremus.dosgi.net", "ptcp://localhost:1234");
        p.put("com.paremus.dosgi.net.methods", new String[] {"1=length[]","2=subSequence[int,int]"});
        EndpointDescription epd = new EndpointDescription(p);


        Mockito.when(_clientConnectionManager.getChannelFor( ArgumentMatchers.eq(new URI("ptcp://localhost:1234")),
        		ArgumentMatchers.any())).thenAnswer(Mockito.RETURNS_MOCKS);

        ImportRegistration ireg = _rsa.importService(epd);
        assertNotNull(ireg);

        assertEquals(1, _rsa.getImportedEndpoints().size());

		ArgumentCaptor<Dictionary> captor = ArgumentCaptor.forClass(Dictionary.class);

        Mockito.verify(_proxyContext).registerService(ArgumentMatchers.any(String[].class), ArgumentMatchers.any(),
        		captor.capture());
        assertNull(captor.getValue().get("foo"));

        p.put("foo", "bar");
        epd = new EndpointDescription(p);
        ireg.update(epd);

        Mockito.verify(_imported).setProperties(captor.capture());

        assertEquals("bar", captor.getValue().get("foo"));
    }

    @Test
    public void testImportedServiceUnregisteredBySomeoneElse() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put(RemoteConstants.ENDPOINT_ID, new UUID(78, 910).toString());
        p.put(Constants.OBJECTCLASS, new String[]{"my.primary.role", "my.secondary.role"});
        p.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "com.paremus.dosgi.net");
        p.put("com.paremus.dosgi.net", "ptcp://localhost:1234");
        p.put("com.paremus.dosgi.net.methods", new String[] {"1=length[]","2=subSequence[int,int]"});
        EndpointDescription epd = new EndpointDescription(p);

        Mockito.when(_clientConnectionManager.getChannelFor( ArgumentMatchers.eq(new URI("ptcp://localhost:1234")),
        		ArgumentMatchers.any())).thenAnswer(Mockito.RETURNS_MOCKS);

        ImportRegistration ireg = _rsa.importService(epd);
        assertNotNull(ireg);

        assertEquals(1, _rsa.getImportedEndpoints().size());

        ArgumentCaptor<ServiceListener> captor = ArgumentCaptor.forClass(ServiceListener.class);

        verify(_frameworkContext).addServiceListener(captor.capture(), ArgumentMatchers.eq("(service.id=5)"));

        captor.getValue().serviceChanged(new ServiceEvent(UNREGISTERING, _importedRef));

        assertEquals(0, _rsa.getImportedEndpoints().size());
        assertNotNull(ireg.getException());
    }
}
