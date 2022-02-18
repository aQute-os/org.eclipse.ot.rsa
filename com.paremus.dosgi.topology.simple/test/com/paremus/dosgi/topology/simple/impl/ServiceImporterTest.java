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

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.osgi.framework.Constants.OBJECTCLASS;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

import com.paremus.dosgi.scoping.discovery.Constants;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceImporterTest {

    private static final String SCOPE_A = "A";
    private static final String SCOPE_B = "B";
    private static final String SCOPE_C = "C";


    @Mock
    private Bundle bundleA, bundleB;

    @Mock
    private RemoteServiceAdmin rsaA, rsaB;

    @Mock
    private ImportRegistration regA, regB;

    ServiceImporter importer;

    @BeforeEach
    public void setUp() throws InvalidSyntaxException {

        importer = new ServiceImporter(new String[] {SCOPE_A, SCOPE_B});

		Mockito.when(rsaA.importService(ArgumentMatchers.any())).thenReturn(regA);
		Mockito.when(rsaB.importService(ArgumentMatchers.any())).thenReturn(regB);
    }

    private EndpointDescription getTestEndpointDescription(String endpointId, UUID frameworkId) {
    	return getTestEndpointDescription(endpointId, frameworkId, null);
    }

    private EndpointDescription getTestEndpointDescription(String endpointId, UUID frameworkId,
    		String scope, String... scopes) {
 		Map<String, Object> m = new LinkedHashMap<>();

         // required
         m.put(OBJECTCLASS, new String[]{"com.acme.HelloService", "some.other.Service"});
         m.put(RemoteConstants.ENDPOINT_FRAMEWORK_UUID, frameworkId.toString());
         m.put(RemoteConstants.ENDPOINT_ID, endpointId);
         m.put(RemoteConstants.ENDPOINT_SERVICE_ID, Long.valueOf(42));
         m.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");

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
    public void testImportUnscopedEndpointThenRSAs() throws Exception {
    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	importer.addingRSA(rsaA);
    	verify(rsaA).importService(endpointDescription);

    	importer.addingRSA(rsaB);
    	verify(rsaB).importService(endpointDescription);
    }

    @Test
    public void testImportUnscopedEndpointWithRSAs() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);
    }

    @Test
    public void testRevokeUnscopedEndpointWithRSAs() throws Exception {

    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	importer.departingEndpoint(bundleA, endpointDescription);
    	verify(regA).close();
    	verify(regB).close();
    }

    @Test
    public void testModifyUnscopedEndpointWithRSAs() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);


    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	EndpointDescription modifiedEndpoint = getTestEndpointDescription("FOO",
    			UUID.fromString(endpointDescription.getFrameworkUUID()));

    	importer.modifiedEndpoint(bundleA, modifiedEndpoint);

    	verify(regA).update(ArgumentMatchers.same(modifiedEndpoint));
    	verify(regB).update(ArgumentMatchers.same(modifiedEndpoint));
    }

    @Test
    public void testImportScopedEndpointThenRSAs() throws Exception {

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A);
		importer.incomingEndpoint(bundleA, endpointDescription);

    	importer.addingRSA(rsaA);
    	verify(rsaA).importService(endpointDescription);

    	importer.addingRSA(rsaB);
    	verify(rsaB).importService(endpointDescription);
    }

    @Test
    public void testImportScopedEndpointWithRSAs() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A);
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);
    }

    @Test
    public void testRevokeScopedEndpointWithRSAs() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A);
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);

    	importer.departingEndpoint(bundleA, endpointDescription);

    	verify(regA).close();
    	verify(regB).close();
    }

    @Test
    public void testModifyExpandScope() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_C);

    	importer.incomingEndpoint(bundleA, endpointDescription);

    	verifyNoInteractions(rsaA, rsaB);

    	endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_C);
    	importer.modifiedEndpoint(bundleA, endpointDescription);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);
    }

    @Test
    public void testModifyReduceScope() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);

    	importer.incomingEndpoint(bundleA, endpointDescription);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);

    	endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_C);
    	importer.modifiedEndpoint(bundleA, endpointDescription);

    	verify(regA).close();
    	verify(regB).close();
    }

    @Test
    public void testReleasingListenerRevokesService() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	importer.releaseListener(bundleA);

    	verify(regA).close();
    	verify(regB).close();
    }

    @Test
    public void testReleasingListenerDoesNotRevokeServiceWithMultipleSponsors() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	importer.incomingEndpoint(bundleA, endpointDescription);
    	importer.incomingEndpoint(bundleB, endpointDescription);

    	verify(rsaA, Mockito.times(1)).importService(endpointDescription);
    	verify(rsaB, Mockito.times(1)).importService(endpointDescription);

    	importer.releaseListener(bundleA);

    	verifyNoInteractions(regA, regB);
    }

    @Test
    public void testDestroy() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	importer.destroy();
    	verify(regA).close();
    	verify(regB).close();
    }

    @Test
    public void testModifyFrameworkScope() throws Exception {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_TARGETTED, SCOPE_A, SCOPE_B);
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);

    	importer.updateScopes(new String[] {SCOPE_C});

    	verify(regA).close();
    	verify(regB).close();

    	importer.updateScopes(new String[] {SCOPE_A});

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);
    }

    @Test
    public void testRemovingRSAProviderUnregistersServices() throws InterruptedException {
    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID());
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);

    	importer.removingRSA(rsaA);

    	verify(regA).close();
    	verifyNoInteractions(regB);

    }

    @Test
    public void testImportUniversalScopedEndpoint() throws Exception {

    	EndpointDescription endpointDescription = getTestEndpointDescription("FOO", UUID.randomUUID(),
    			Constants.PAREMUS_SCOPE_UNIVERSAL);
    	importer.incomingEndpoint(bundleA, endpointDescription);

    	importer.addingRSA(rsaA);
    	importer.addingRSA(rsaB);

    	verify(rsaA).importService(endpointDescription);
    	verify(rsaB).importService(endpointDescription);
    }
}
