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
package com.paremus.dosgi.topology.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.MODIFIED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.REMOVED;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointListener;

@SuppressWarnings("deprecation")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class EndpointListenerAdapterTest {

	private static final String FILTER = "foo";

	@Mock
	EndpointListener listener;

	@Mock
	EndpointDescription ed;

	@Test
	public void testAdd() {
		EndpointListenerAdapter adapter = new EndpointListenerAdapter(listener);

		adapter.endpointChanged(new EndpointEvent(ADDED, ed), FILTER);

		Mockito.verify(listener).endpointAdded(ed, FILTER);
		Mockito.verifyNoMoreInteractions(listener);

	}

	@Test
	public void testRemove() {
		EndpointListenerAdapter adapter = new EndpointListenerAdapter(listener);

		adapter.endpointChanged(new EndpointEvent(REMOVED, ed), FILTER);

		Mockito.verify(listener).endpointRemoved(ed, FILTER);
		Mockito.verifyNoMoreInteractions(listener);

	}

	@Test
	public void testModified() {
		EndpointListenerAdapter adapter = new EndpointListenerAdapter(listener);

		adapter.endpointChanged(new EndpointEvent(MODIFIED, ed), FILTER);

		InOrder inOrder = Mockito.inOrder(listener);
		inOrder.verify(listener).endpointRemoved(ed, FILTER);
		inOrder.verify(listener).endpointAdded(ed, FILTER);
		inOrder.verifyNoMoreInteractions();

	}

	@Test
	public void testEquals() {
		EndpointListenerAdapter adapter = new EndpointListenerAdapter(listener);
		EndpointListenerAdapter adapter2 = new EndpointListenerAdapter(listener);

		assertEquals(adapter, adapter2);

	}
}
