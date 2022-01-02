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

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.MODIFIED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.MODIFIED_ENDMATCH;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.REMOVED;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.ServiceReference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class EndpointEventListenerInterestTest {

	private static final String FILTER = "foo";
	
	private static final List<String> filters = Arrays.asList(FILTER, "bar");
	
	@Mock
	EndpointEventListener listener;
	
	@Mock
	ServiceReference<?> ref;
	
	@Mock
	EndpointDescription edA, edB;
	
	@Test
	public void testNewAdd() {
		
		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(listener, ref, filters);
		
		when(edA.matches(FILTER)).thenReturn(true);
		
		interest.notify(null, edA);
		
		verify(listener).endpointChanged(argThat(isEventWith(ADDED, edA)), eq(FILTER));
		verifyNoMoreInteractions(listener);
	}

	@Test
	public void testNewAddNoMatch() {
		
		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(listener, ref, filters);
		
		interest.notify(null, edA);
		
		verifyNoInteractions(listener);
	}
	
	@Test
	public void testNoMatchToMatchUnseen() {
		
		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(listener, ref, filters);

		when(edB.matches(FILTER)).thenReturn(true);
		
		interest.notify(edA, edB);
		
		verify(listener).endpointChanged(argThat(isEventWith(ADDED, edB)), eq(FILTER));
		verifyNoMoreInteractions(listener);
	}

	@Test
	public void testMatchToMatchSeen() {
		
		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(listener, ref, filters);
		
		when(edA.matches(FILTER)).thenReturn(true);
		when(edB.matches(FILTER)).thenReturn(true);
		
		interest.notify(null, edA);
		interest.notify(edA, edB);
		
		InOrder inOrder = Mockito.inOrder(listener);
		inOrder.verify(listener).endpointChanged(argThat(isEventWith(ADDED, edA)), eq(FILTER));
		inOrder.verify(listener).endpointChanged(argThat(isEventWith(MODIFIED, edB)), eq(FILTER));
		inOrder.verifyNoMoreInteractions();
	}

	@Test
	public void testMatchToNoMatchSeen() {
		
		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(listener, ref, filters);
		
		when(edA.matches(FILTER)).thenReturn(true);
		
		interest.notify(null, edA);
		interest.notify(edA, edB);
		
		InOrder inOrder = Mockito.inOrder(listener);
		inOrder.verify(listener).endpointChanged(argThat(isEventWith(ADDED, edA)), eq(FILTER));
		inOrder.verify(listener).endpointChanged(argThat(isEventWith(MODIFIED_ENDMATCH, edB)), eq(FILTER));
		inOrder.verifyNoMoreInteractions();
	}

	@Test
	public void testNoMatchToNullUneen() {
		
		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(listener, ref, filters);
		
		interest.notify(null, edA);
		interest.notify(edA, null);
		
		verifyNoInteractions(listener);
	}
	
	@Test
	public void testMatchToNullSeen() {
		
		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(listener, ref, filters);
		
		when(edA.matches(FILTER)).thenReturn(true);
		
		interest.notify(null, edA);
		interest.notify(edA, null);
		
		InOrder inOrder = Mockito.inOrder(listener);
		inOrder.verify(listener).endpointChanged(argThat(isEventWith(ADDED, edA)), eq(FILTER));
		inOrder.verify(listener).endpointChanged(argThat(isEventWith(REMOVED, edA)), eq(FILTER));
		inOrder.verifyNoMoreInteractions();
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
