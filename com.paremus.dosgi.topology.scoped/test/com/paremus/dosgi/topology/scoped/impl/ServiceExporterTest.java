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

import static com.paremus.dosgi.topology.scoped.activator.TopologyManagerConstants.SCOPE_FRAMEWORK_PROPERTY;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
import org.mockito.runners.MockitoJUnitRunner;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

import com.paremus.dosgi.discovery.scoped.Constants;
import com.paremus.dosgi.topology.scoped.IsolationAwareRemoteServiceAdmin;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceExporterTest {

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
    private ExportRegistration awareReg;
    @Mock
    private ExportRegistration rootReg;
    @Mock
    private ExportRegistration regA;
    @Mock
    private ExportRegistration regB;
    @Mock
    private ExportRegistration regC;

    
    @Mock
    private ExportReference awareRef;
    @Mock
    private ExportReference rootRef;
    @Mock
    private ExportReference refA;
    @Mock
    private ExportReference refB;
    @Mock
    private ExportReference refC;
    
    @Mock
    private EndpointDescription awareDesc;
    @Mock
    private EndpointDescription rootDesc;
    @Mock
    private EndpointDescription refADesc;
    @Mock
    private EndpointDescription refBDesc;
    @Mock
    private EndpointDescription refCDesc;
  
    @Mock
    private ServiceReference<Object> ref;
    
    @Mock
    private EndpointEventListener listener;
    @Mock
    private ServiceReference<EndpointEventListener> listenerRef;
    
    Semaphore sem = new Semaphore(0);

    ServiceExporter exporter;

    @BeforeEach
    public void setUp() throws InvalidSyntaxException {
        setupFrameworkIds();
        
        exporter = new ServiceExporter(root);
        
        exporter.registerScope(childC, SCOPE_C);
    	exporter.registerScope(childB, SCOPE_B);
    	exporter.registerScope(childA, SCOPE_A);
        
		setupRSAs();
		
		Mockito.doAnswer(i -> {
			sem.release(32 << ((EndpointEvent)i.getArguments()[0]).getType());
			return null;
		}).when(listener).endpointChanged(Mockito.any(), Mockito.anyString());
    }

	private void setupFrameworkIds() {
		Mockito.when(root.getBundleContext()).thenReturn(rootCtx);
	    Mockito.when(rootCtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(rootId);
	    Mockito.when(childA.getBundleContext()).thenReturn(childACtx);
	    Mockito.when(childACtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(childAId);
	    Mockito.when(childB.getBundleContext()).thenReturn(childBCtx);
	    Mockito.when(childBCtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(childBId);
	    Mockito.when(childC.getBundleContext()).thenReturn(childCCtx);
	    Mockito.when(childCCtx.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID)).thenReturn(childCId);
	}

	private void setupRSAs() {
		Mockito.when(awareRSA.exportService(Mockito.any(Framework.class), Mockito.any(), 
				Mockito.anyMapOf(String.class, Object.class))).then(i -> {
			sem.release();
			return singleton(awareReg);	
		});
		setupRegistration(awareReg, awareRef, awareDesc);
		
		setupRSA(rootRSA, 2, rootReg, rootRef, rootDesc);

		setupRSA(rsaA, 4, regA, refA, refADesc);
		setupRSA(rsaB, 8, regB, refB, refBDesc);
		setupRSA(rsaC, 16, regC, refC, refCDesc);
	}

	private void setupRSA(RemoteServiceAdmin rsa, int release, ExportRegistration reg, ExportReference ref, EndpointDescription ed) {
		Mockito.when(rsa.exportService(Mockito.any(), 
				Mockito.anyMapOf(String.class, Object.class))).then(i -> {
			sem.release(release);
			return singleton(reg);	
		});
		setupRegistration(reg, ref, ed);
	}

	private void setupRegistration(ExportRegistration reg, ExportReference ref, EndpointDescription ed) {
		Mockito.when(reg.getExportReference()).thenReturn(ref);
		Mockito.doAnswer(i -> {
				Mockito.when(reg.getExportReference()).thenReturn(null);
				return null;
			}).when(reg).close();
		Mockito.when(ref.getExportedEndpoint()).thenReturn(ed);
		Mockito.when(ed.matches(Mockito.anyString())).thenReturn(true);
	}

	@AfterEach
	public void tearDown() {
    	assertEquals(0, sem.availablePermits());
    }
    
	private ArgumentMatcher<Map<String, Object>> mapWith(String scope, String fwScope, String rootId, String... systems) {
		return new ArgumentMatcher<Map<String,Object>>() {

			@Override
			@SuppressWarnings("unchecked")
			public boolean matches(Map<String,Object> item) {
				boolean matches = true;
				if (item instanceof Map) {
					Map<String, Object> m = (Map<String, Object>) item;
					if(scope != null) {
						matches &= scope.equals(m.get(Constants.PAREMUS_SCOPES_ATTRIBUTE));
					} else {
						matches &= !m.containsKey(Constants.PAREMUS_SCOPES_ATTRIBUTE);
					}
					matches &= fwScope.equals(m.get(Constants.PAREMUS_ORIGIN_SCOPE));
					matches &= rootId.equals(m.get(Constants.PAREMUS_ORIGIN_ROOT));
					
					Object sys = m.get(Constants.PAREMUS_TARGETTED_ATTRIBUTE);
					if(systems != null && systems.length > 0) {
						if(sys == null) {
							matches = false;
						} else {
							matches &= (sys instanceof String) ? 
								(systems.length == 1 && systems[0].equals(sys)) : 
								new HashSet<>((Collection<String>) sys).equals(
										new HashSet<>(Arrays.asList(systems)));
						}
					} else {
						matches &= !m.containsKey(Constants.PAREMUS_TARGETTED_ATTRIBUTE);
					}
				} else {
					matches = false;
				}
				if (!matches) System.err.printf("Match failed on input: '%s'. Wanted scope=%s, fwScope=%s, rootId=%s, systems=%s%n", item, scope, fwScope, rootId, Arrays.toString(systems));
				return matches;
			}

		};
	}

	private ArgumentMatcher<EndpointEvent> endpointEventWith(EndpointDescription ed, int type) {
		return new ArgumentMatcher<EndpointEvent>() {
			
			@Override
			public boolean matches(EndpointEvent item) {
				boolean matches = true;
				if (item instanceof EndpointEvent) {
					EndpointEvent ee = (EndpointEvent) item;
					matches &= ed.equals(ee.getEndpoint());
					matches &= ee.getType() == type;
				} else {
					matches = false;
				}
				return matches;
			}
			
		};
	}
	
	@Test
    public void testExportUnscopedEndpointFromRootThenRSAs() throws Exception {
    	
    	exporter.addingService(root, ref);
    	
    	exporter.addingRSA(awareRSA);
    	assertTrue(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(root), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));

    	exporter.addingRSA(root, rootRSA);
    	assertTrue(sem.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rootRSA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));

    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rsaA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

	@Test
    public void testExportUnscopedEndpointFromRootThenRSAs2() throws Exception {
    	
    	exporter.addingService(root, ref);
    	
    	exporter.addingRSA(root, rootRSA);
    	assertTrue(sem.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rootRSA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingRSA(awareRSA);
    	assertTrue(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(root), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	
    	Mockito.verifyZeroInteractions(rsaA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

	@Test
    public void testExportUnscopedEndpointWithRSAsFromRoot() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(root), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	Mockito.verify(rootRSA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rsaA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

	@Test
    public void testRevokeUnscopedEndpointFromRootWithRSAs() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	exporter.removedService(root, ref);
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(rootReg).close();
    	
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.REMOVED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.REMOVED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.REMOVED)), Mockito.anyString());
    }

	@Test
    public void testDoubleExportUnscopedEndpointBecomesModifiedWithRSAsFromRoot() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    	
    }
    
	@Test
    public void testModifyUnscopedEndpointWithRSAsFromRoot() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	exporter.modifiedService(root, ref);
    	
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	Mockito.verify(rootReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    	
    }

	@Test
	public void testModifyUnscopedEndpointWithRSAsFromRootWithExtra() throws Exception {
		exporter.addingRSA(awareRSA);
		exporter.addingRSA(root, rootRSA);
		exporter.addingRSA(childA, rsaA);
		exporter.addingRSA(childB, rsaB);
		exporter.addingRSA(childC, rsaC);
		
		Mockito.when(rootCtx.getProperty(SCOPE_FRAMEWORK_PROPERTY))
			.thenReturn("foo,bar");
		
		exporter.registerScope(root, "<<ROOT>>");
		
		exporter.addingService(root, ref);
		
		assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
		
		exporter.addingEEL(root, listener, listenerRef, "");
		assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
		
		exporter.modifiedService(root, ref);
		
		assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
		
		Mockito.verify(awareReg).update(
				Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>", "foo", "bar")));
		Mockito.verify(rootReg).update(
				Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>", "foo", "bar")));
		
		Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
		Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
		
	}

	@Test
    public void testModifyEndMatchUnscopedEndpointWithRSAsThenRoot() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.when(awareDesc.matches(Mockito.anyString())).thenReturn(false);
    	Mockito.when(rootDesc.matches(Mockito.anyString())).thenReturn(false);
    	
    	exporter.modifiedService(root, ref);
    	
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.MODIFIED_ENDMATCH), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	Mockito.verify(rootReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.MODIFIED_ENDMATCH)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.MODIFIED_ENDMATCH)), Mockito.anyString());
    	
    }

	@Test
    public void testExportScopedEndpointFromRootThenRSAs() throws Exception {
    	
    	Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE))
    		.thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);
    	
    	exporter.addingService(root, ref);
    	
    	exporter.addingRSA(awareRSA);
    	assertTrue(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(root), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));

    	exporter.addingRSA(root, rootRSA);
    	assertTrue(sem.tryAcquire(2, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rootRSA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));

    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rsaA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

	@Test
    public void testExportScopedEndpointWithRSAsFromRoot() throws Exception {
    	Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE))
			.thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);
    	
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(root), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	Mockito.verify(rootRSA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, "<<ROOT>>", rootId, "<<ROOT>>")));
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rsaA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    }
    
	@Test
    public void testExportUnscopedEndpointFromChildThenRSAs() throws Exception {
    	
    	exporter.addingService(childA, ref);
    	
    	exporter.addingRSA(awareRSA);
    	assertTrue(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(childA), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));

    	exporter.addingRSA(root, rootRSA);
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));

    	exporter.addingRSA(childA, rsaA);
    	assertTrue(sem.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());

    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    	
    }

	@Test
    public void testExportUnscopedEndpointWithRSAsFromChild() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(childA), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.verify(rsaA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rootRSA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());

    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    }

	@Test
    public void testRevokeUnscopedEndpointFromChildWithRSAs() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	
    	assertTrue(sem.tryAcquire(3, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	exporter.removedService(root, ref);
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(rootReg).close();
    	
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.REMOVED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.REMOVED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(rootDesc, EndpointEvent.REMOVED)), Mockito.anyString());
    }

	@Test
    public void testDoubleExportUnscopedEndpointBecomesModifiedWithRSAsFromChild() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(4 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(4 * (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    	
    }
    
	@Test
    public void testModifyUnscopedEndpointWithRSAsFromChild() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(4 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	exporter.modifiedService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(4 * (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	Mockito.verify(regA).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.MODIFIED)), Mockito.anyString());
    }

	@Test
    public void testModifyEndMatchUnscopedEndpointWithRSAsThenChild() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(4 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.when(awareDesc.matches(Mockito.anyString())).thenReturn(false);
    	Mockito.when(refADesc.matches(Mockito.anyString())).thenReturn(false);
    	
    	exporter.modifiedService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(4 * (32 << EndpointEvent.MODIFIED_ENDMATCH), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	Mockito.verify(regA).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.MODIFIED_ENDMATCH)), Mockito.anyString());
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.MODIFIED_ENDMATCH)), Mockito.anyString());
    }

	@Test
    public void testExportScopedEndpointFromChildThenRSAs() throws Exception {
    	
    	Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE))
    		.thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);
    	
    	exporter.addingService(childA, ref);
    	
    	exporter.addingRSA(awareRSA);
    	assertTrue(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(childA), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));

    	exporter.addingRSA(root, rootRSA);
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));

    	exporter.addingRSA(childA, rsaA);
    	assertTrue(sem.tryAcquire(4, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(rsaA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rootRSA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());

    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    }

	@Test
    public void testExportScopedEndpointWithRSAsFromChild() throws Exception {
    	Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE))
			.thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);
    	
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(childA), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.verify(rsaA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verifyZeroInteractions(rootRSA, rsaB, rsaC);
    	
    	exporter.addingEEL(root, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.ADDED)), Mockito.anyString());
    	Mockito.verify(listener, Mockito.times(2)).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)), Mockito.anyString());

    	exporter.addingEEL(childB, listener, listenerRef, "");
    	exporter.addingEEL(childC, listener, listenerRef, "");
    }
    
	@Test
    public void testModifyExpandScopeWithChildEventListener() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(childA), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.verify(rsaA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE))
    		.thenReturn(Constants.PAREMUS_SCOPE_GLOBAL);
    	
    	exporter.modifiedService(childA, ref);
    	
    	exporter.addingEEL(childA, listener, listenerRef, "");
    	
    	assertTrue(sem.tryAcquire(2* (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg).update(
    			Mockito.argThat(mapWith(null, SCOPE_A, rootId)));
    	Mockito.verify(regA).update(
    			Mockito.argThat(mapWith(null, SCOPE_A, rootId)));

    	
    	Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE))
    		.thenReturn(null);
    	Mockito.when(ref.getProperty(Constants.PAREMUS_TARGETTED_ATTRIBUTE))
    		.thenReturn(Arrays.asList("A", "B"));
    	
    	exporter.modifiedService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(2* (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	Mockito.verify(regA).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));

    	
    	Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE))
    		.thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);
    	
    	exporter.modifiedService(childA, ref);
    	
    	assertTrue(sem.tryAcquire(2* (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg, Mockito.times(2)).update(
    			Mockito.argThat(mapWith(null, SCOPE_A, rootId)));
    	Mockito.verify(regA, Mockito.times(2)).update(
    			Mockito.argThat(mapWith(null, SCOPE_A, rootId)));
    }

	@Test
    public void testDestroy() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(root, ref);
    	exporter.addingService(childA, ref);
    	exporter.addingService(childB, ref);
    	exporter.addingService(childC, ref);
    	
    	assertTrue(sem.tryAcquire(34, 100, TimeUnit.MILLISECONDS));
    	
    	exporter.destroy();
    	Thread.sleep(100);
    	Mockito.verify(awareReg).close();
    	Mockito.verify(rootReg).close();
    	Mockito.verify(regA).close();
    	Mockito.verify(regB).close();
    	Mockito.verify(regC).close();
    }
    
	@Test
    public void testModifyFrameworkScope() throws Exception {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	exporter.addingEEL(root, listener, listenerRef, "");
    	
    	assertTrue(sem.tryAcquire(5 + 2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(childA), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.verify(rsaA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.when(childACtx.getProperty(SCOPE_FRAMEWORK_PROPERTY)).thenReturn("foo,bar , baz");
    	exporter.registerScope(childA, SCOPE_B);
    	
    	assertTrue(sem.tryAcquire(2 * (32 << EndpointEvent.MODIFIED), 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(awareReg).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_B, rootId, 
    					SCOPE_B, "foo", "bar", "baz")));
    	Mockito.verify(regA).update(
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_B, rootId, 
    					SCOPE_B, "foo", "bar", "baz")));
    	
    	Mockito.verify(awareReg, Mockito.never()).close();
    	Mockito.verify(regA, Mockito.never()).close();
    }
    
	@Test
    public void testRemovingRSAProviderUnregistersServices() throws InterruptedException {
    	exporter.addingRSA(awareRSA);
    	exporter.addingRSA(root, rootRSA);
    	exporter.addingRSA(childA, rsaA);
    	exporter.addingRSA(childB, rsaB);
    	exporter.addingRSA(childC, rsaC);
    	
    	exporter.addingService(childA, ref);
    	exporter.addingEEL(root, listener, listenerRef, "");
    	
    	assertTrue(sem.tryAcquire(5 + 2 * (32 << EndpointEvent.ADDED), 100, TimeUnit.MILLISECONDS));
    	Mockito.verify(awareRSA).exportService(Mockito.same(childA), Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	Mockito.verify(rsaA).exportService(Mockito.same(ref), 
    			Mockito.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, rootId, SCOPE_A)));
    	
    	exporter.removingRSA(awareRSA);
    	assertTrue(sem.tryAcquire(32 << EndpointEvent.REMOVED, 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(awareDesc, EndpointEvent.REMOVED)), Mockito.anyString());
    	
    	Mockito.verify(awareReg).close();
    	Mockito.verifyZeroInteractions(rootReg, regB, regC);
    	
    	exporter.removingRSA(childA, rsaA);
    	assertTrue(sem.tryAcquire(32 << EndpointEvent.REMOVED, 100, TimeUnit.MILLISECONDS));
    	
    	Mockito.verify(listener).endpointChanged(Mockito.argThat(endpointEventWith(refADesc, EndpointEvent.REMOVED)), Mockito.anyString());
    	Mockito.verify(regA).close();
    	Mockito.verifyZeroInteractions(rootReg, regB, regC);
    }
}
