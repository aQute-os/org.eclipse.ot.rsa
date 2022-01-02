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
package com.paremus.dosgi.discovery.cluster.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.osgi.service.remoteserviceadmin.EndpointEventListener.ENDPOINT_LISTENER_SCOPE;

import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ConfiguredDiscoveryTest {

	private static final UUID LOCAL_UUID = new UUID(123, 456);
	
	@Mock
	Config config;

	@BeforeEach
	public void setUp() {
		Mockito.when(config.additional_filters()).thenReturn(new String[0]);
		Mockito.when(config.local_id_filter_extension()).thenReturn("");
	}
	
	@Test
	public void testDefaultConfig() {
		Hashtable<String, Object> filters = ConfiguredDiscovery.getFilters(config, LOCAL_UUID);
		
		assertTrue(filters.containsKey(ENDPOINT_LISTENER_SCOPE));
		
		@SuppressWarnings("unchecked")
		List<String> filterList = (List<String>) filters.get(ENDPOINT_LISTENER_SCOPE);
		
		assertEquals(1, filterList.size());
		assertEquals("(endpoint.framework.uuid=" + LOCAL_UUID + ")", filterList.get(0));
	}

	@Test
	public void testFilterExtensionConfig() {
		
		Mockito.when(config.local_id_filter_extension()).thenReturn("(foo=bar)");
		
		Hashtable<String, Object> filters = ConfiguredDiscovery.getFilters(config, LOCAL_UUID);
		
		assertTrue(filters.containsKey(ENDPOINT_LISTENER_SCOPE));
		
		@SuppressWarnings("unchecked")
		List<String> filterList = (List<String>) filters.get(ENDPOINT_LISTENER_SCOPE);
		
		assertEquals(1, filterList.size());
		assertEquals("(&(foo=bar)(endpoint.framework.uuid=" + LOCAL_UUID + "))", filterList.get(0));
	}

	@Test
	public void testFilterExtensionPlaceholderConfig() {
		
		Mockito.when(config.local_id_filter_extension()).thenReturn("(foo=${LOCAL_FW_UUID})");
		
		Hashtable<String, Object> filters = ConfiguredDiscovery.getFilters(config, LOCAL_UUID);
		
		assertTrue(filters.containsKey(ENDPOINT_LISTENER_SCOPE));
		
		@SuppressWarnings("unchecked")
		List<String> filterList = (List<String>) filters.get(ENDPOINT_LISTENER_SCOPE);
		
		assertEquals(1, filterList.size());
		assertEquals("(&(foo=" + LOCAL_UUID + ")(endpoint.framework.uuid=" + LOCAL_UUID + "))", filterList.get(0));
	}

	@Test
	public void testAdditionalFilterConfig() {
		
		Mockito.when(config.additional_filters()).thenReturn(new String[] {"(foo=bar)", "(fizz=buzz)"});
		
		Hashtable<String, Object> filters = ConfiguredDiscovery.getFilters(config, LOCAL_UUID);
		
		assertTrue(filters.containsKey(ENDPOINT_LISTENER_SCOPE));
		
		@SuppressWarnings("unchecked")
		List<String> filterList = (List<String>) filters.get(ENDPOINT_LISTENER_SCOPE);
		
		assertEquals(3, filterList.size());
		assertEquals("(endpoint.framework.uuid=" + LOCAL_UUID + ")", filterList.get(0));
		assertEquals("(foo=bar)", filterList.get(1));
		assertEquals("(fizz=buzz)", filterList.get(2));
	}

	@Test
	public void testAdditionalFilterPlaceholderConfig() {
		
		Mockito.when(config.additional_filters()).thenReturn(new String[] {"(foo=${LOCAL_FW_UUID})", "(fizz=${LOCAL_FW_UUID})"});
		
		Hashtable<String, Object> filters = ConfiguredDiscovery.getFilters(config, LOCAL_UUID);
		
		assertTrue(filters.containsKey(ENDPOINT_LISTENER_SCOPE));
		
		@SuppressWarnings("unchecked")
		List<String> filterList = (List<String>) filters.get(ENDPOINT_LISTENER_SCOPE);
		
		assertEquals(3, filterList.size());
		assertEquals("(endpoint.framework.uuid=" + LOCAL_UUID + ")", filterList.get(0));
		assertEquals("(foo=" + LOCAL_UUID + ")", filterList.get(1));
		assertEquals("(fizz=" + LOCAL_UUID + ")", filterList.get(2));
	}

}
