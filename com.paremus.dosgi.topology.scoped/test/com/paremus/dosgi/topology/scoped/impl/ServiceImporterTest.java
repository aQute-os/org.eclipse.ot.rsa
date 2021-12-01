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
package com.paremus.dosgi.topology.scoped.impl;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.osgi.framework.Constants.OBJECTCLASS;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

import com.paremus.dosgi.discovery.scoped.Constants;
import com.paremus.dosgi.topology.scoped.IsolationAwareRemoteServiceAdmin;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceImporterTest {

    private static final String SCOPE_A = "A/part";
    private static final String SCOPE_B = "B/part";
    private static final String SCOPE_C = "C/part";

    @Mock
    private Framework root;
    @Mock
    private Framework childA;
    @Mock
    private Framework childB;
    @Mock
    private Framework childC;

    @Mock
    private BundleContext rootCtx;
    @Mock
    private BundleContext childACtx;
    @Mock
    private BundleContext childBCtx;
    @Mock
    private BundleContext childCCtx;
    
    private String rootId = UUID.randomUUID().toString();
    private String childAId = UUID.randomUUID().toString();
    private String childBId = UUID.randomUUID().toString();
    private String childCId = UUID.randomUUID().toString();
    
    @Mock
    private IsolationAwareRemoteServiceAdmin awareRSA;
    @Mock
    private RemoteServiceAdmin rootRSA;
    @Mock
    private RemoteServiceAdmin rsaA;
    @Mock
    private RemoteServiceAdmin rsaB;
    @Mock
    private RemoteServiceAdmin rsaC;

    @Mock
    private ImportRegistration awareReg;
    @Mock
    private ImportRegistration rootReg;
    @Mock
    private ImportRegistration regA;
    @Mock
    private ImportRegistration regB;
    @Mock
    private ImportRegistration regC;
    
    Semaphore s = new Semaphore(0);
    
    
    ServiceImporter importer;

    @BeforeEach
    public void setUp() throws InvalidSyntaxException {
        Mockito.when(root.getBundleContext()).thenReturn(rootCtx);
        Mockito.when(rootCtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(rootId);
        Mockito.when(childA.getBundleContext()).thenReturn(childACtx);
        Mockito.when(childACtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(childAId);
        Mockito.when(childB.getBundleContext()).thenReturn(childBCtx);
        Mockito.when(childBCtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(childBId);
        Mockito.when(childC.getBundleContext()).thenReturn(childCCtx);
        Mockito.when(childCCtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(childCId);
        
        importer = new ServiceImporter(root);
        
        importer.registerScope(childC, SCOPE_C);
    	importer.registerScope(childB, SCOPE_B);
    	importer.registerScope(childA, SCOPE_A);
        
		Mockito.when(awareRSA.importService(Mockito.any(Framework.class), Mockito.any())).then(i -> {
			s.release();
			return awareReg;	
		});
		Mockito.when(rootRSA.importService( Mockito.any())).then(i -> {
			s.release(2);
			return rootReg;	
		});
		Mockito.when(rsaA.importService(Mockito.any())).then(i -> {
			s.release(4);
			return regA;	
		});
		Mockito.when(rsaB.importService(Mockito.any())).then(i -> {
			s.release(8);
			return regB;	
		});
		Mockito.when(rsaC.importService(Mockito.any())).then(i -> {
			s.release(16);
			return regC;	
		});
        
    }
    
    @AfterEach
    public void tearDown() {
    	assertEquals(0, s.availablePermits());
    }
    
    private EndpointDescription getTestEndpointDescription(String endpointId, UUID frameworkId) {
    	return getTestEndpointDescription(endpointId, frameworkId, null);
    }
   
    private EndpointDescription getTestEndpointDescription(String endpointId, UUID frameworkId, 
    		String scope, String... scopes) {
 		Map<String, Object> m = new LinkedHashMap<String, Object>();

         // required
         m.put(OBJECTCLASS, new String[]{"com.acme.HelloService", "some.other.Service"});
         m.put(RemoteConstants.ENDPOINT_FRAMEWORK_UUID, frameworkId.toString());
         m.put(RemoteConstants.ENDPOINT_ID, endpointId);
         m.put(RemoteConstants.ENDPOINT_SERVICE_ID, Long.valueOf(42));
         m.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
         m.put(Constants.PAREMUS_ORIGIN_ROOT, rootId);
         
         if(scope != null) {
        	 m.put(Constants.PAREMUS_SCOPES_ATTRIBUTE, scope);
         }
         
         if(scopes.length > 0) {
        	 if(scopes.length > 1) {
        		 m.put(Constants.PAREMUS_TARGETTED_EXTRA_ATTRIBUTE, scopes[scopes.length -1]);
        	 }
        	 m.put(Constants.PAREMUS_TARGETTED_ATTRIBUTE, 
        			 asList(scopes).subList(0, Math.max(1, scopes.length - 1)).stream()
        			 	.collect(toSet()));
         }

         return new EndpointDescription(m);
 	}    
    
    @Test
    public void testImportUnscopedEndpointWithRootListenerThenRSAs() throws Exception {
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
		el.endpointAdded(endpointDescription, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertTrue(s.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rootRSA).importService(endpointDescription);

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	importer.addingRSA(childC, rsaC);
    	assertTrue(s.tryAcquire(16, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaC).importService(endpointDescription);
    }

    @Test
    public void testImportUnscopedEndpointWithRootListenerThenRSAs2() throws Exception {
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	el.endpointAdded(endpointDescription, null);

    	importer.addingRSA(root, rootRSA);
    	assertTrue(s.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rootRSA).importService(endpointDescription);
    	
    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	importer.addingRSA(childC, rsaC);
    	assertTrue(s.tryAcquire(16, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaC).importService(endpointDescription);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);
    }

    @Test
    public void testImportUnscopedEndpointWithRSAsThenRootListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);

    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);

    	Mockito.verify(rootRSA).importService(endpointDescription);
    	Mockito.verify(rsaA).importService(endpointDescription);
    	Mockito.verify(rsaB).importService(endpointDescription);
    	Mockito.verify(rsaC).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeUnscopedEndpointWithRSAsThenRootListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointRemoved(endpointDescription, null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(4)).close();
    	Mockito.verify(rootReg).close();
    }

    @Test
    public void testImportUnscopedEndpointWithRootEventListenerThenRSAs() throws Exception {
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertTrue(s.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rootRSA).importService(endpointDescription);

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	importer.addingRSA(childC, rsaC);
    	assertTrue(s.tryAcquire(16, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaC).importService(endpointDescription);
    }
    
    @Test
    public void testImportUnscopedEndpointWithRSAsThenRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);

    	Mockito.verify(rootRSA).importService(endpointDescription);
    	Mockito.verify(rsaA).importService(endpointDescription);
    	Mockito.verify(rsaB).importService(endpointDescription);
    	Mockito.verify(rsaC).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeUnscopedEndpointWithRSAsThenRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.REMOVED, endpointDescription), null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(4)).close();
    	Mockito.verify(rootReg).close();
    }

    @Test
    public void testModifyUnscopedEndpointWithRSAsThenRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	EndpointDescription modifiedEndpoint = getTestEndpointDescription("FOO", 
    			UUID.fromString(endpointDescription.getFrameworkUUID()));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED, modifiedEndpoint), null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(4)).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(rootReg).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(regA).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(regB).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(regC).update(Mockito.same(modifiedEndpoint));
    }

    @Test
    public void testModifyEndMatchUnscopedEndpointWithRSAsThenRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	EndpointDescription modifiedEndpoint = getTestEndpointDescription("FOO", 
    			UUID.fromString(endpointDescription.getFrameworkUUID()));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED_ENDMATCH, modifiedEndpoint), null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(4)).close();
    	Mockito.verify(rootReg).close();
    }

    @Test
    public void testImportScopedEndpointWithRootListenerThenRSAs() throws Exception {
    	
    	importer.unregisterScope(childB, SCOPE_B);
    	
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
		el.endpointAdded(endpointDescription, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	importer.addingRSA(childC, rsaC);

    	
    	importer.registerScope(childB, SCOPE_B);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);

    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testImportScopedEndpointWithRSAsThenRootListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);

    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	Mockito.verify(rsaA).importService(endpointDescription);
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeScopedEndpointWithRSAsThenRootListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointRemoved(endpointDescription, null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(2)).close();
    	Mockito.verify(regA).close();
    	Mockito.verify(regB).close();
    }

    @Test
    public void testImportScopedEndpointWithRootEventListenerThenRSAs() throws Exception {
    	importer.unregisterScope(childB, SCOPE_B);
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	importer.addingRSA(childC, rsaC);

    	
    	importer.registerScope(childB, SCOPE_B);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);

    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void testImportScopedEndpointWithRSAsThenRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	Mockito.verify(rsaA).importService(endpointDescription);
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeScopedEndpointWithRSAsThenRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.REMOVED, endpointDescription), null);
    	
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(2)).close();
    	Mockito.verify(regA).close();
    	Mockito.verify(regB).close();
    }

    @Test
    public void testModifyExpandScopeWithRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B, SCOPE_C);
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED, endpointDescription), null);
    	
    	assertTrue(s.tryAcquire(17, 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg, Mockito.times(2)).update(Mockito.same(endpointDescription));
    	
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);
    	Mockito.verify(rsaC).importService(endpointDescription);
    }

    @Test
    public void testModifyReduceScopeWithRootEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A);
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED, endpointDescription), null);
    	
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(regB).close();
    }
    
    @Test
    public void testUseListenerThenEventListener() {
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	
    	EndpointListener listener = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
		listener.endpointAdded(endpointDescription, null);
    	
    	try {
    		((EndpointEventListener)listener).endpointChanged(ee, null);
    		fail();
    	} catch(IllegalStateException ise) {
    		//Expected
    	}
    }

    @Test
    public void testUseListenerThenEventListenerDifferentBundles() {
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	
    	ServiceFactory<Object> serviceFactory = importer.getServiceFactory(root);
		EndpointListener listener = (EndpointListener) serviceFactory.getService(childA, null);
		listener.endpointAdded(endpointDescription, null);
    	
		EndpointEventListener eventListener = (EndpointEventListener) serviceFactory.getService(childB, null);
		eventListener.endpointChanged(ee, null);
    }

    @Test
    public void testUseEventListenerThenListener() {
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	
    	EndpointEventListener listener = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
		listener.endpointChanged(ee, null);
    	
    	try {
    		((EndpointListener) listener).endpointAdded(endpointDescription, null);
    		fail();
    	} catch(IllegalStateException ise) {
    		//Expected
    	}
    }

    @Test
    public void testUseEventListenerThenListenerDifferentBundles() {
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	
    	ServiceFactory<Object> serviceFactory = importer.getServiceFactory(root);
		EndpointEventListener eventListener = (EndpointEventListener) serviceFactory.getService(childA, null);
		eventListener.endpointChanged(ee, null);
    	
		EndpointListener listener = (EndpointListener) serviceFactory.getService(childB, null);
		listener.endpointAdded(endpointDescription, null);
    }

    @Test
    public void testReleasingListenerRevokesService() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);

    	ServiceFactory<Object> listenerFactory = importer.getServiceFactory(root);
		EndpointListener el = (EndpointListener) listenerFactory.getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	listenerFactory.ungetService(root, null, el);
    	
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(4)).close();
    	Mockito.verify(rootReg).close();
    }

    @Test
    public void testReleasingEventListenerRevokesService() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	ServiceFactory<Object> listenerFactory = importer.getServiceFactory(root);
    	EndpointEventListener el = (EndpointEventListener) listenerFactory.getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	el.endpointChanged(new EndpointEvent(EndpointEvent.ADDED, endpointDescription), null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	listenerFactory.ungetService(root, null, el);
    	
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(4)).close();
    	Mockito.verify(rootReg).close();
    }

    @Test
    public void testImportUnscopedEndpointWithChildListenerThenRSAs() throws Exception {
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
		el.endpointAdded(endpointDescription, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(1, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	importer.addingRSA(childB, rsaB);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	importer.addingRSA(childC, rsaC);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testImportUnscopedEndpointWithRSAsThenChildListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);

    	EndpointListener el = (EndpointListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	Mockito.verify(rsaA).importService(endpointDescription);

    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeUnscopedEndpointWithRSAsThenChildListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointRemoved(endpointDescription, null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(regA).close();
    }

    @Test
    public void testImportUnscopedEndpointWithChildEventListenerThenRSAs() throws Exception {
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(1, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	importer.addingRSA(childB, rsaB);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	importer.addingRSA(childC, rsaC);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void testImportUnscopedEndpointWithRSAsThenChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	Mockito.verify(rsaA).importService(endpointDescription);

    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeUnscopedEndpointWithRSAsThenChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.REMOVED, endpointDescription), null);
    	
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(regA).close();
    }

    @Test
    public void testModifyUnscopedEndpointWithRSAsThenChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	
    	EndpointDescription modifiedEndpoint = getTestEndpointDescription("FOO", 
    			UUID.fromString(endpointDescription.getFrameworkUUID()));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED, modifiedEndpoint), null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(regA).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(rootReg, Mockito.never()).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(regB, Mockito.never()).update(Mockito.same(modifiedEndpoint));
    	Mockito.verify(regC, Mockito.never()).update(Mockito.same(modifiedEndpoint));
    }

    @Test
    public void testModifyEndMatchUnscopedEndpointWithRSAsThenChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	
    	EndpointDescription modifiedEndpoint = getTestEndpointDescription("FOO", 
    			UUID.fromString(endpointDescription.getFrameworkUUID()));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED_ENDMATCH, modifiedEndpoint), null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(regA).close();
    }

    @Test
    public void testImportScopedEndpointWithChildListenerThenRSAs() throws Exception {
    	
    	importer.unregisterScope(childB, SCOPE_B);
    	
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
		el.endpointAdded(endpointDescription, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	importer.addingRSA(childC, rsaC);

    	
    	importer.registerScope(childB, SCOPE_B);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);

    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testImportScopedEndpointWithRSAsThenChildListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);

    	EndpointListener el = (EndpointListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	Mockito.verify(rsaA).importService(endpointDescription);
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeScopedEndpointWithRSAsThenChildListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointRemoved(endpointDescription, null);
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(2)).close();
    	Mockito.verify(regA).close();
    	Mockito.verify(regB).close();
    }

    @Test
    public void testImportScopedEndpointWithChildEventListenerThenRSAs() throws Exception {
    	importer.unregisterScope(childB, SCOPE_B);
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	importer.addingRSA(childC, rsaC);

    	
    	importer.registerScope(childB, SCOPE_B);
    	assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);

    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void testImportScopedEndpointWithRSAsThenChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(root, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	Mockito.verify(rsaA).importService(endpointDescription);
    	Mockito.verify(rsaB).importService(endpointDescription);
    	
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRevokeScopedEndpointWithRSAsThenChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	
    	el.endpointChanged(new EndpointEvent(EndpointEvent.REMOVED, endpointDescription), null);
    	
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(2)).close();
    	Mockito.verify(regA).close();
    	Mockito.verify(regB).close();
    }

    @Test
    public void testModifyExpandScopeWithChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B, SCOPE_C);
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED, endpointDescription), null);
    	
    	assertTrue(s.tryAcquire(17, 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg, Mockito.times(2)).update(Mockito.same(endpointDescription));
    	
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);
    	Mockito.verify(rsaC).importService(endpointDescription);
    }

    @Test
    public void testModifyReduceScopeWithChildEventListener() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A);
    	el.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED, endpointDescription), null);
    	
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(regB).close();
    }
    
    @Test
    public void testDestroy() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointListener el = (EndpointListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	el.endpointAdded(endpointDescription, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	
    	importer.destroy();
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(regA).close();
    	Mockito.verify(regB).close();
    }
    
    @Test
    public void testModifyFrameworkScope() throws Exception {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(childA).getService(childA, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(14, 100, TimeUnit.MILLISECONDS));
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	importer.registerScope(childB, SCOPE_C);
    	
    	Thread.sleep(100);
    	Mockito.verify(regB).close();
    	Mockito.verify(awareReg).close();
    	
    	
    	importer.registerScope(childB, SCOPE_A);
    	
    	assertTrue(s.tryAcquire(9, 100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void testRemovingRSAProviderUnregistersServices() throws InterruptedException {
    	importer.addingRSA(awareRSA);
    	importer.addingRSA(root, rootRSA);
    	importer.addingRSA(childA, rsaA);
    	importer.addingRSA(childB, rsaB);
    	importer.addingRSA(childC, rsaC);
    	
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	assertTrue(s.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	importer.removingRSA(awareRSA);
    	Thread.sleep(100);
    	Mockito.verify(awareReg, Mockito.times(4)).close();
    	Mockito.verifyZeroInteractions(rootReg, regA, regB, regC);
    	
    	importer.removingRSA(childA, rsaA);
    	Thread.sleep(100);
    	Mockito.verify(regA).close();
    	Mockito.verifyZeroInteractions(rootReg, regB, regC);
    }
    
    @Test
    public void testImportUniversalScopedEndpointWithFromDifferentParent() throws Exception {
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_UNIVERSAL);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA).importService(childC, endpointDescription);
    	
    	importer.addingRSA(root, rootRSA);
    	assertTrue(s.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	
    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	
    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	
    	importer.addingRSA(childC, rsaC);
    	assertTrue(s.tryAcquire(16, 100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void testImportUniversalScopedEndpointWithFromSameParent() throws Exception {
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.fromString(childCId), 
    			Constants.PAREMUS_SCOPE_UNIVERSAL);
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);
    	Mockito.verify(awareRSA).importService(childA, endpointDescription);
    	Mockito.verify(awareRSA).importService(childB, endpointDescription);
    	Mockito.verify(awareRSA, Mockito.never()).importService(childC, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertTrue(s.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);

    	importer.addingRSA(childA, rsaA);
    	assertTrue(s.tryAcquire(4, 100, TimeUnit.MILLISECONDS));

    	importer.addingRSA(childB, rsaB);
    	assertTrue(s.tryAcquire(8, 100, TimeUnit.MILLISECONDS));
    	
    	importer.addingRSA(childC, rsaC);
    	assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void testImportScopedEndpointWithRootEventListenerThenRSAsTargettingRoot() throws Exception {
    	EndpointEventListener el = (EndpointEventListener) importer.getServiceFactory(root).getService(root, null);
    	
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(), 
    			Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>");
    	
    	EndpointEvent ee = new EndpointEvent(EndpointEvent.ADDED, endpointDescription);
    	el.endpointChanged(ee, null);
    	
    	importer.addingRSA(awareRSA);
    	assertTrue(s.tryAcquire(1, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).importService(root, endpointDescription);

    	importer.addingRSA(root, rootRSA);
    	assertTrue(s.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rootRSA).importService(endpointDescription);

    	assertFalse(s.tryAcquire(500, TimeUnit.MILLISECONDS));
    }
}
