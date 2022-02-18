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

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.REMOVED;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class EndpointListenerServiceTest {

	public static final String FILTER = "FOO";

	@Mock
	Bundle b;

	@Mock
	EndpointDescription ed;

	EndpointListenerService listenerService;

	@BeforeEach
	public void setUp() {

		listenerService = mock(EndpointListenerService.class, withSettings().useConstructor(b)
				.defaultAnswer(Answers.CALLS_REAL_METHODS));

	}

	@Test
	public void testEndpointAdded() {
		listenerService.endpointAdded(ed, FILTER);

		verify(listenerService).handleEvent(same(b), argThat(isEventWith(ADDED, ed)), eq(FILTER));
	}

	@Test
	public void testEndpointRemoved() {
		listenerService.endpointRemoved(ed, FILTER);

		verify(listenerService).handleEvent(same(b), argThat(isEventWith(REMOVED, ed)), eq(FILTER));
	}

	@Test
	public void testEndpointChanged() {

		EndpointEvent event = new EndpointEvent(ADDED, ed);
		listenerService.endpointChanged(event, FILTER);

		verify(listenerService).handleEvent(same(b), same(event), eq(FILTER));
	}

	@Test
	public void testMultipleUseAsListener() {

		listenerService.endpointAdded(ed, FILTER);
		listenerService.endpointRemoved(ed, FILTER);

		InOrder inOrder = Mockito.inOrder(listenerService);
		inOrder.verify(listenerService).handleEvent(same(b), argThat(isEventWith(ADDED, ed)), eq(FILTER));
		inOrder.verify(listenerService).handleEvent(same(b), argThat(isEventWith(REMOVED, ed)), eq(FILTER));
	}

	@Test
	public void testMultipleUseAsEventListener() {

		listenerService.endpointChanged(new EndpointEvent(ADDED, ed), FILTER);
		listenerService.endpointChanged(new EndpointEvent(REMOVED, ed), FILTER);

		InOrder inOrder = Mockito.inOrder(listenerService);
		inOrder.verify(listenerService).handleEvent(same(b), argThat(isEventWith(ADDED, ed)), eq(FILTER));
		inOrder.verify(listenerService).handleEvent(same(b), argThat(isEventWith(REMOVED, ed)), eq(FILTER));
	}

	@Test
	public void testMixedUseEventListener() {

		EndpointEvent event = new EndpointEvent(ADDED, ed);
		listenerService.endpointChanged(event, FILTER);

		try {
			listenerService.endpointRemoved(ed, FILTER);
			fail("Should throw an Exception");
		} catch (IllegalStateException e) {

		}
	}

	@Test
	public void testMixedUseListenerEvent() {

		listenerService.endpointAdded(ed, FILTER);

		try {
			listenerService.endpointChanged(new EndpointEvent(REMOVED, ed), FILTER);
			fail("Should throw an Exception");
		} catch (IllegalStateException e) {

		}
	}


	private ArgumentMatcher<EndpointEvent> isEventWith(int eventType, EndpointDescription ed) {
		return new ArgumentMatcher<EndpointEvent>() {

				@Override
				public boolean matches(EndpointEvent argument) {
					return argument.getType() == eventType && argument.getEndpoint().equals(ed);
				}
			};
	}

}
