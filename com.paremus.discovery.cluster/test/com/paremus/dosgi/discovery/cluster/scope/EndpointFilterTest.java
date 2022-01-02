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
package com.paremus.dosgi.discovery.cluster.scope;

import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_SCOPE_GLOBAL;
import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_SCOPE_TARGETTED;
import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_SCOPE_UNIVERSAL;
import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_TARGETTED_ATTRIBUTE;
import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_TARGETTED_EXTRA_ATTRIBUTE;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.osgi.framework.Constants.OBJECTCLASS;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;

import com.paremus.dosgi.discovery.cluster.Constants;

public class EndpointFilterTest {

	private static final UUID LOCAL_UUID = new UUID(123, 456);
	private static final String ENDPOINT = new UUID(234, 567).toString();

	public static final String CLUSTER_A = "clusterA";
	public static final String CLUSTER_B = "clusterB";

	public static final String SCOPE_A = "system-a";
	public static final String SCOPE_B = "system-b";
	
	@Test
	public void testDefault() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				null, null, null, null, null), emptySet()));
	}

	@Test
	public void testScopeUniversal() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_UNIVERSAL, null, null, null, null), emptySet()));
	}

	@Test
	public void testScopeGlobal() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_GLOBAL, null, null, null, null), emptySet()));
	}

	@Test
	public void testScopeGlobalWrongCluster() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_GLOBAL, CLUSTER_B, null, null, null), emptySet()));
		
		filter.addCluster(CLUSTER_B);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_GLOBAL, CLUSTER_B, null, null, null), emptySet()));
	}

	@Test
	public void testScopeGlobalMultipleClusters() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_GLOBAL, asList(CLUSTER_A, CLUSTER_B), null, null, null), emptySet()));

		filter = new EndpointFilter(CLUSTER_B, new String[0]);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_GLOBAL, asList(CLUSTER_A, CLUSTER_B), null, null, null), emptySet()));
	}

	@Test
	public void testScopeGlobalMultipleClustersButNotALocalTarget() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addCluster(CLUSTER_B);
		
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_GLOBAL, asList(CLUSTER_B), null, null, null), Collections.singleton(CLUSTER_A)));
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_GLOBAL, asList(CLUSTER_B), null, null, null), Collections.singleton(CLUSTER_B)));
	}

	@Test
	public void testTargetScope() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, null), emptySet()));

		filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_A);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, null), emptySet()));
	}

	@Test
	public void testTargetScopeExtra() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_B);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, null), emptySet()));
		
		filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_B);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, SCOPE_B), emptySet()));
	}

	@Test
	public void testTargetScopeCorrectCluster() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, CLUSTER_A, null, SCOPE_A, null), emptySet()));
		
		filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_A);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, CLUSTER_A, null, SCOPE_A, null), emptySet()));
	}

	@Test
	public void testTargetScopeWrongCluster() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, CLUSTER_B, null, SCOPE_A, null), emptySet()));
		
		filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_A);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, CLUSTER_B, null, SCOPE_A, null), emptySet()));
	}

	@Test
	public void testTargetScopeExtraCluster() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_A);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, CLUSTER_B, null, SCOPE_A, null), emptySet()));
		
		filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_A);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, CLUSTER_B, CLUSTER_A, SCOPE_A, null), emptySet()));
	}

	@Test
	public void testTargetScopeMultipleClusters() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, new String[]{CLUSTER_A, CLUSTER_B}, null, SCOPE_A, null), emptySet()));
		
		filter = new EndpointFilter(CLUSTER_A, new String[0]);
		filter.addScope(SCOPE_A);
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, new String[]{CLUSTER_A, CLUSTER_B}, null, SCOPE_A, null), emptySet()));
	}

	@Test
	public void testTargetScopeMultipleScopes() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[0]);
		
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, null), emptySet()));

		filter.addScope(SCOPE_A);
		filter.addScope(SCOPE_B);
		
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, null), emptySet()));
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, new String[] {SCOPE_A, SCOPE_B}, null), emptySet()));

		filter.removeScope(SCOPE_A);
		assertFalse(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, null), emptySet()));
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, new String[] {SCOPE_A, SCOPE_B}, null), emptySet()));
	}

	@Test
	public void testBaseScopeIncludedInScopes() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[] {SCOPE_A});
		
		assertTrue(filter.getScopes().contains(SCOPE_A), ()->"Scopes did not contain " + SCOPE_A + " " + filter.getScopes());
		
		assertTrue(filter.accept(getTestEndpointDescription(ENDPOINT, 
				PAREMUS_SCOPE_TARGETTED, null, null, SCOPE_A, null), emptySet()));
	}

	@Test
	public void testBaseScopeNotRemovable() {
		EndpointFilter filter = new EndpointFilter(CLUSTER_A, new String[] {SCOPE_A});
		
		filter.removeScope(SCOPE_A);

		assertTrue(filter.getScopes().contains(SCOPE_A), ()->"Scopes did not contain " + SCOPE_A + " " + filter.getScopes());
	}


	private EndpointDescription getTestEndpointDescription(String endpointId, String scope, 
			Object cluster, Object clustersExtra, Object scopes, Object scopesExtra) {
		Map<String, Object> m = new LinkedHashMap<String, Object>();

        // required
        m.put(OBJECTCLASS, new String[]{"com.acme.HelloService", "some.other.Service"});
        m.put(RemoteConstants.ENDPOINT_FRAMEWORK_UUID, LOCAL_UUID.toString());
        m.put(RemoteConstants.ENDPOINT_ID, endpointId);
        m.put(RemoteConstants.ENDPOINT_SERVICE_ID, Long.valueOf(42));
        m.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");
        if(scope != null) {
        	m.put(PAREMUS_SCOPES_ATTRIBUTE, scope);
        }
        if(cluster != null) {
        	m.put(Constants.PAREMUS_CLUSTERS_ATTRIBUTE, cluster);
        }
        if(clustersExtra != null) {
        	m.put(Constants.PAREMUS_CLUSTERS_EXTRA_ATTRIBUTE, clustersExtra);
        }
        if(scopes != null) {
        	m.put(PAREMUS_TARGETTED_ATTRIBUTE, scopes);
        }
        if(scopesExtra != null) {
        	m.put(PAREMUS_TARGETTED_EXTRA_ATTRIBUTE, scopesExtra);
        }

        return new EndpointDescription(m);
	}
}
