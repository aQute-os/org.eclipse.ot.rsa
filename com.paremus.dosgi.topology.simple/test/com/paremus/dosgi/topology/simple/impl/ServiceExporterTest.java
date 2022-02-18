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
package com.paremus.dosgi.topology.simple.impl;

import static java.util.Collections.singleton;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.MODIFIED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.MODIFIED_ENDMATCH;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

import com.paremus.dosgi.scoping.discovery.Constants;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceExporterTest {

	private static final String SCOPE_A = "A";
	private static final String SCOPE_B = "B";
	private static final String SCOPE_C = "C";

	@Mock
	private RemoteServiceAdmin rsaA, rsaB;

	@Mock
	private ExportRegistration regA, regB;

	@Mock
	private ExportReference refA, refB;

	@Mock
	private EndpointDescription refADesc, refBDesc;

	@Mock
	private ServiceReference<Object> ref;

	@Mock
	private EndpointEventListener listener;

	@Mock
	private ServiceReference<EndpointEventListener> listenerRef;

	ServiceExporter exporter;

	@BeforeEach
	public void setUp() throws InvalidSyntaxException {

		exporter = new ServiceExporter(new String[] { SCOPE_A, SCOPE_B });

		setupRSAs();
	}

	private void setupRSAs() {
		setupRSA(rsaA, regA, refA, refADesc);
		setupRSA(rsaB, regB, refB, refBDesc);
	}

	private void setupRSA(RemoteServiceAdmin rsa, ExportRegistration reg, ExportReference ref, EndpointDescription ed) {
		Mockito.when(rsa.exportService(ArgumentMatchers.any(), ArgumentMatchers.anyMap())).thenReturn(singleton(reg));
		setupRegistration(reg, ref, ed);
	}

	private void setupRegistration(ExportRegistration reg, ExportReference ref, EndpointDescription ed) {
		Mockito.when(reg.getExportReference()).thenReturn(ref);
		Mockito.doAnswer(i -> {
			Mockito.when(reg.getExportReference()).thenReturn(null);
			return null;
		}).when(reg).close();
		Mockito.when(ref.getExportedEndpoint()).thenReturn(ed);
		Mockito.when(ed.matches(ArgumentMatchers.anyString())).thenReturn(true);
	}

	private ArgumentMatcher<Map<String, Object>> mapWith(String scope, String... scopes) {
		return new ArgumentMatcher<Map<String, Object>>() {

			@Override
			@SuppressWarnings("unchecked")
			public boolean matches(Map<String, Object> item) {
				boolean matches = true;
				if (item instanceof Map) {
					Map<String, Object> m = item;
					if (scope != null) {
						matches &= scope.equals(m.get(Constants.PAREMUS_SCOPES_ATTRIBUTE));
					} else {
						matches &= !m.containsKey(Constants.PAREMUS_SCOPES_ATTRIBUTE);
					}

					Object sys = m.get(Constants.PAREMUS_TARGETTED_ATTRIBUTE);
					if (scopes != null && scopes.length > 0) {
						if (sys == null) {
							matches = false;
						} else {
							matches &= (sys instanceof String) ? (scopes.length == 1 && scopes[0].equals(sys))
									: new HashSet<>((Collection<String>) sys)
											.equals(new HashSet<>(Arrays.asList(scopes)));
						}
					} else {
						matches &= !m.containsKey(Constants.PAREMUS_TARGETTED_ATTRIBUTE);
					}
				} else {
					matches = false;
				}
				if (!matches)
					System.err.printf("Match failed on input: '%s'. Wanted scope=%s, systems=%s%n", item, scope,
							Arrays.toString(scopes));
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
					EndpointEvent ee = item;
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
	public void testExportUnscopedEndpointThenRSAs() throws Exception {

		exporter.exportService(ref);

		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, ADDED)),
				ArgumentMatchers.anyString());
	}

	@Test
	public void testExportUnscopedEndpointWithRSAs() throws Exception {

		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, ADDED)),
				ArgumentMatchers.anyString());

	}

	@Test
	public void testRevokeUnscopedEndpointWithRSAs() throws Exception {
		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, ADDED)),
				ArgumentMatchers.anyString());

		exporter.removeExportedService(ref);

		Mockito.verify(regA).close();
		Mockito.verify(regB).close();

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, EndpointEvent.REMOVED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, EndpointEvent.REMOVED)),
				ArgumentMatchers.anyString());
	}

	@Test
	public void testDoubleExportUnscopedEndpointBecomesModifiedWithRSAs() throws Exception {
		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, ADDED)),
				ArgumentMatchers.anyString());

		exporter.exportService(ref);

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, MODIFIED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, MODIFIED)),
				ArgumentMatchers.anyString());

	}

	@Test
	public void testModifyUnscopedEndpointWithRSAs() throws Exception {
		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, ADDED)),
				ArgumentMatchers.anyString());

		exporter.updateExportedService(ref);

		Mockito.verify(regA).update(ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(regB).update(ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, MODIFIED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, MODIFIED)),
				ArgumentMatchers.anyString());

	}

	@Test
	public void testModifyEndMatchUnscopedEndpointWithRSAsThenRoot() throws Exception {
		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, ADDED)),
				ArgumentMatchers.anyString());

		Mockito.when(refADesc.matches(ArgumentMatchers.anyString())).thenReturn(false);
		Mockito.when(refBDesc.matches(ArgumentMatchers.anyString())).thenReturn(false);

		exporter.updateExportedService(ref);

		Mockito.verify(regA).update(ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(regB).update(ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, MODIFIED_ENDMATCH)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, MODIFIED_ENDMATCH)),
				ArgumentMatchers.anyString());

	}

	@Test
	public void testExportScopedEndpointThenRSAs() throws Exception {

		Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE)).thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);

		exporter.exportService(ref);

		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref),
				ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref),
				ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, EndpointEvent.ADDED)),
				ArgumentMatchers.anyString());
	}

	@Test
	public void testExportScopedEndpointWithRSAs() throws Exception {
		Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE)).thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);

		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref),
				ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref),
				ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, EndpointEvent.ADDED)),
				ArgumentMatchers.anyString());
	}

	@Test
	public void testDestroy() throws Exception {
		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);

		exporter.destroy();

		Mockito.verify(regA).close();
		Mockito.verify(regB).close();
	}

	@Test
	public void testModifyFrameworkScope() throws Exception {

		Mockito.when(ref.getProperty(Constants.PAREMUS_SCOPES_ATTRIBUTE)).thenReturn(Constants.PAREMUS_SCOPE_TARGETTED);

		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		exporter.exportService(ref);
		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref),
				ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref),
				ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B)));

		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, EndpointEvent.ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, EndpointEvent.ADDED)),
				ArgumentMatchers.anyString());

		exporter.updateScopes(new String[] {SCOPE_C});

		Mockito.verify(regA).update(ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_C)));
		Mockito.verify(regB).update(ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_C)));
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, EndpointEvent.MODIFIED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, EndpointEvent.MODIFIED)),
				ArgumentMatchers.anyString());
	}

	@Test
	public void testRemovingRSAProviderUnregistersServices() throws InterruptedException {
		exporter.exportService(ref);

		exporter.addingRSA(rsaA);
		exporter.addingRSA(rsaB);

		Mockito.verify(rsaA).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));
		Mockito.verify(rsaB).exportService(ArgumentMatchers.same(ref), ArgumentMatchers.argThat(mapWith(Constants.PAREMUS_SCOPE_GLOBAL)));

		exporter.addingEEL(listener, listenerRef, Arrays.asList(""));
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refADesc, ADDED)),
				ArgumentMatchers.anyString());
		Mockito.verify(listener).endpointChanged(ArgumentMatchers.argThat(endpointEventWith(refBDesc, ADDED)),
				ArgumentMatchers.anyString());

		exporter.removingRSA(rsaA);

		Mockito.verify(regA).close();
		Mockito.verify(regB, Mockito.never()).close();
	}
}
