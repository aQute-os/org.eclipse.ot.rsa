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
package com.paremus.dosgi.discovery.cluster.remote;

import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_ORIGIN_ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.times;
import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.framework.ServiceEvent.REGISTERED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.RemoteConstants;

import com.paremus.dosgi.discovery.cluster.scope.EndpointFilter;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RemoteDiscoveryNotifierTest {

	private static final UUID REMOTE_UUID = new UUID(123, 456);
    private static final UUID OTHER_REMOTE_UUID = new UUID(456, 789);
	private static final String FILTER_1 = "(endpoint.service.id=42)";
	private static final String FILTER_2 = "(endpoint.service.id=43)";
	@Mock
	BundleContext context;
	@Mock
	ServiceReference<EndpointEventListener> refA;
	@Mock
	EndpointEventListener listenerA;
	@Mock
	ServiceReference<EndpointEventListener> refB;
	@Mock
	EndpointEventListener listenerB;
	@Mock
	EndpointFilter filter;

	EventExecutorGroup executor;

	Semaphore s = new Semaphore(0);

	RemoteDiscoveryNotifier notifier;

	@BeforeEach
	public void setUp() throws Exception {
		Mockito.when(context.getService(refA)).thenReturn(listenerA);
		Mockito.when(refA.getProperty(EndpointEventListener.ENDPOINT_LISTENER_SCOPE)).thenReturn(Arrays.asList(FILTER_1, FILTER_2));
		Mockito.doAnswer(i -> {
				s.release();
				return null;
			}).when(listenerA).endpointChanged(ArgumentMatchers.any(EndpointEvent.class), ArgumentMatchers.anyString());

		Mockito.when(context.getService(refB)).thenReturn(listenerB);
		Mockito.when(refB.getProperty(EndpointEventListener.ENDPOINT_LISTENER_SCOPE)).thenReturn(Arrays.asList(FILTER_1));
		Mockito.doAnswer(i -> {
			s.release(2);
			return null;
		}).when(listenerB).endpointChanged(ArgumentMatchers.any(EndpointEvent.class), ArgumentMatchers.anyString());

		Mockito.when(refA.compareTo(refB)).thenReturn(1);
		Mockito.when(refB.compareTo(refA)).thenReturn(-1);

		executor = new DefaultEventExecutorGroup(1);
	}

	@AfterEach
	public void tearDown() throws Exception {
		notifier.destroy();
		executor.shutdownGracefully(10, 1000, TimeUnit.MILLISECONDS).sync();
	}

	@Test
	public void testAnnouncementEvent() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Set up a single listener
		ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
		Mockito.verify(context).addServiceListener(listenerCaptor.capture(), ArgumentMatchers.contains(EndpointEventListener.class.getName()));
		ServiceListener captured = listenerCaptor.getValue();
		captured.serviceChanged(new ServiceEvent(REGISTERED, refA));

		//Send a notification
		EndpointDescription ed = getTestEndpointDescription(42);
		Mockito.when(filter.accept(same(ed), anySet())).thenReturn(true);
		notifier.announcementEvent(ed, 1);

		//Check listener A received it
		assertTrue(s.tryAcquire(1000, TimeUnit.MILLISECONDS));

		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerA).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		EndpointEvent ee = captor.getValue();
		assertEquals(ADDED, ee.getType());
		assertSame(ed, ee.getEndpoint());
	}

	@Test
	public void testFilteredAnnouncementEvent() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Set up a single listener
		ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
		Mockito.verify(context).addServiceListener(listenerCaptor.capture(), ArgumentMatchers.contains(EndpointEventListener.class.getName()));
		ServiceListener captured = listenerCaptor.getValue();
		captured.serviceChanged(new ServiceEvent(REGISTERED, refA));

		//Send a notification
		EndpointDescription ed = getTestEndpointDescription(42);
		notifier.announcementEvent(ed, 1);

		//Check listener A received it
		assertFalse(s.tryAcquire(1000, TimeUnit.MILLISECONDS));
	}

	@Test
	public void testDuplicateAnnouncementEvent() throws Exception {
		testAnnouncementEvent();

		//Send the same announcement
		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerA).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		EndpointEvent ee = captor.getValue();
		notifier.announcementEvent(ee.getEndpoint(), 1);


		Thread.sleep(100);
		Mockito.verifyNoMoreInteractions(listenerA);
	}

	@Test
	public void testAnnouncementEventLateJoiner() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Set up a single listener
		ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
		Mockito.verify(context).addServiceListener(listenerCaptor.capture(), ArgumentMatchers.contains(EndpointEventListener.class.getName()));
		ServiceListener captured = listenerCaptor.getValue();

		//Send a notification
		EndpointDescription ed = getTestEndpointDescription(42);
		Mockito.when(filter.accept(same(ed), anySet())).thenReturn(true);
		notifier.announcementEvent(ed, 1);

		//Test a late joiner
		captured.serviceChanged(new ServiceEvent(REGISTERED, refB));
		assertTrue(s.tryAcquire(2, 1000, TimeUnit.MILLISECONDS));

		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerB).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		EndpointEvent ee = captor.getValue();
		assertEquals(ADDED, ee.getType());
		assertSame(ed, ee.getEndpoint());
	}

	@Test
	public void testTwoListeners() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Set up a two listeners
		ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
		Mockito.verify(context).addServiceListener(listenerCaptor.capture(), ArgumentMatchers.contains(EndpointEventListener.class.getName()));
		ServiceListener captured = listenerCaptor.getValue();
		captured.serviceChanged(new ServiceEvent(REGISTERED, refA));
		captured.serviceChanged(new ServiceEvent(REGISTERED, refB));

		//Send a notification
		EndpointDescription ed = getTestEndpointDescription(42);
		Mockito.when(filter.accept(same(ed), anySet())).thenReturn(true);
		notifier.announcementEvent(ed, 1);

		//Check
		assertTrue(s.tryAcquire(3, 1000, TimeUnit.MILLISECONDS));

		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerA).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		EndpointEvent ee = captor.getValue();
		assertEquals(ADDED, ee.getType());
		assertSame(ed, ee.getEndpoint());

		Mockito.verify(listenerB).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		ee = captor.getValue();
		assertEquals(ADDED, ee.getType());
		assertSame(ed, ee.getEndpoint());
	}

	@Test
	public void testEndpointUpdates() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Set up a two listeners
		ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
		Mockito.verify(context).addServiceListener(listenerCaptor.capture(), ArgumentMatchers.contains(EndpointEventListener.class.getName()));
		ServiceListener captured = listenerCaptor.getValue();
		captured.serviceChanged(new ServiceEvent(REGISTERED, refA));
		captured.serviceChanged(new ServiceEvent(REGISTERED, refB));

		//Send a notification
		EndpointDescription ed = getTestEndpointDescription(42);
		Mockito.when(filter.accept(eq(ed), anySet())).thenReturn(true);
		notifier.announcementEvent(ed, 1);

		//Send an update

		EndpointDescription ed2 = getTestEndpointDescription(43);
		notifier.announcementEvent(ed2, 2);

		assertTrue(s.tryAcquire(6, 1000, TimeUnit.MILLISECONDS));

		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerA).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_2));
		EndpointEvent ee = captor.getValue();
		assertEquals(EndpointEvent.MODIFIED, ee.getType());
		assertSame(ed2, ee.getEndpoint());

		Mockito.verify(listenerB, times(2)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		ee = captor.getValue();
		assertEquals(EndpointEvent.MODIFIED_ENDMATCH, ee.getType());
		assertSame(ed2, ee.getEndpoint());
	}

	@Test
	public void testEndpointUpdateNoLongerMatchesFilter() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Set up a two listeners
		ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
		Mockito.verify(context).addServiceListener(listenerCaptor.capture(), ArgumentMatchers.contains(EndpointEventListener.class.getName()));
		ServiceListener captured = listenerCaptor.getValue();
		captured.serviceChanged(new ServiceEvent(REGISTERED, refA));
		captured.serviceChanged(new ServiceEvent(REGISTERED, refB));

		//Send a notification
		EndpointDescription ed = getTestEndpointDescription(42);
		Mockito.when(filter.accept(eq(ed), anySet())).thenReturn(true);
		notifier.announcementEvent(ed, 1);

		//Send an update

		EndpointDescription ed2 = getTestEndpointDescription(43);
		Mockito.when(filter.accept(eq(ed), anySet())).thenReturn(false);
		notifier.announcementEvent(ed2, 2);

		assertTrue(s.tryAcquire(6, 1000, TimeUnit.MILLISECONDS));

		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerA, times(2)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		EndpointEvent ee = captor.getValue();
		assertEquals(EndpointEvent.REMOVED, ee.getType());
		assertSame(ed, ee.getEndpoint());

		Mockito.verify(listenerB, times(2)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		ee = captor.getValue();
		assertEquals(EndpointEvent.REMOVED, ee.getType());
		assertSame(ed, ee.getEndpoint());
	}

	@Test
	public void testRevokeAllFromFramework() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Set up a single listener
		ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
		Mockito.verify(context).addServiceListener(listenerCaptor.capture(), ArgumentMatchers.contains(EndpointEventListener.class.getName()));
		ServiceListener captured = listenerCaptor.getValue();
		captured.serviceChanged(new ServiceEvent(REGISTERED, refA));

		//Send some notifications
		EndpointDescription edA = getTestEndpointDescription("a", 42);
		Mockito.when(filter.accept(same(edA), anySet())).thenReturn(true);
		notifier.announcementEvent(edA, 1);
		EndpointDescription edB = getTestEndpointDescription("b", 42);
		Mockito.when(filter.accept(same(edB), anySet())).thenReturn(true);
		notifier.announcementEvent(edB, 1);
		EndpointDescription edC = getTestEndpointDescription("c", 42);
		Mockito.when(filter.accept(same(edC), anySet())).thenReturn(true);
		notifier.announcementEvent(edC, 1);

		//Check listener A received it
		assertTrue(s.tryAcquire(3, 1000, TimeUnit.MILLISECONDS));
		List<EndpointDescription> eds = Arrays.asList(edA, edB, edC);

		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerA, times(3)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		List<EndpointEvent> eeList = captor.getAllValues();
		Iterator<EndpointDescription> it = eds.iterator();
		for(EndpointEvent ee : eeList) {
			assertEquals(EndpointEvent.ADDED, ee.getType());
			assertSame(it.next(), ee.getEndpoint());
		}

		//Revoke all of them
		notifier.revokeAllFromFramework(UUID.fromString(edA.getFrameworkUUID()));

		assertTrue(s.tryAcquire(3, 1000, TimeUnit.MILLISECONDS));

		Mockito.verify(listenerA, times(6)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		eeList = captor.getAllValues().subList(6, 9);
		it = eds.iterator();
		for(EndpointEvent ee : eeList) {
			assertEquals(EndpointEvent.REMOVED, ee.getType());
			assertSame(it.next(), ee.getEndpoint());
		}
	}

    @Test
    public void testRevokeAllFromOtherFramework() throws Exception {
        notifier = new RemoteDiscoveryNotifier(filter, context, executor);

        // Set up a single listener
        ArgumentCaptor<ServiceListener> listenerCaptor = ArgumentCaptor.forClass(ServiceListener.class);
        Mockito.verify(context).addServiceListener(listenerCaptor.capture(),
                ArgumentMatchers.contains(EndpointEventListener.class.getName()));
        ServiceListener captured = listenerCaptor.getValue();
        captured.serviceChanged(new ServiceEvent(REGISTERED, refA));

        // Send some notifications
        EndpointDescription edA = getOtherTestEndpointDescription("a", 42);
        Mockito.when(filter.accept(same(edA), anySet())).thenReturn(true);
        notifier.announcementEvent(edA, 1);
        EndpointDescription edB = getOtherTestEndpointDescription("b", 42);
        Mockito.when(filter.accept(same(edB), anySet())).thenReturn(true);
        notifier.announcementEvent(edB, 1);
        EndpointDescription edC = getOtherTestEndpointDescription("c", 42);
        Mockito.when(filter.accept(same(edC), anySet())).thenReturn(true);
        notifier.announcementEvent(edC, 1);

        // Check listener A received it
        assertTrue(s.tryAcquire(3, 1000, TimeUnit.MILLISECONDS));
        List<EndpointDescription> eds = Arrays.asList(edA, edB, edC);

        ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
        Mockito.verify(listenerA, times(3)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
        List<EndpointEvent> eeList = captor.getAllValues();
        Iterator<EndpointDescription> it = eds.iterator();
        for (EndpointEvent ee : eeList) {
            assertEquals(EndpointEvent.ADDED, ee.getType());
            assertSame(it.next(), ee.getEndpoint());
        }

        // Revoke all of them
        notifier.revokeAllFromFramework(OTHER_REMOTE_UUID);

        assertTrue(s.tryAcquire(3, 1000, TimeUnit.MILLISECONDS));

        Mockito.verify(listenerA, times(6)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
        eeList = captor.getAllValues().subList(6, 9);
        it = eds.iterator();
        for (EndpointEvent ee : eeList) {
            assertEquals(EndpointEvent.REMOVED, ee.getType());
            assertSame(it.next(), ee.getEndpoint());
        }
    }

    @Test
	public void testRevocationEvent() throws Exception {
		testAnnouncementEvent();

		String endpointId = getTestEndpointDescription(42).getId();
		notifier.revocationEvent(endpointId, 2);

		assertTrue(s.tryAcquire(1000, TimeUnit.MILLISECONDS));

		ArgumentCaptor<EndpointEvent> captor = ArgumentCaptor.forClass(EndpointEvent.class);
		Mockito.verify(listenerA, times(2)).endpointChanged(captor.capture(), ArgumentMatchers.eq(FILTER_1));
		EndpointEvent ee = captor.getValue();
		assertEquals(EndpointEvent.REMOVED, ee.getType());
		assertSame(endpointId, ee.getEndpoint().getId());
	}

	@Test
	public void testDestroy() throws Exception {
		testTwoListeners();

		notifier.destroy();

		Mockito.verify(context).ungetService(refA);
		Mockito.verify(context).ungetService(refB);
	}

	@Test
	public void testListKnownEvents() throws Exception {
		notifier = new RemoteDiscoveryNotifier(filter, context, executor);

		//Send a notification
		EndpointDescription ed = getTestEndpointDescription(42);
		Mockito.when(filter.accept(same(ed), anySet())).thenReturn(true);
		notifier.announcementEvent(ed, 1);

		assertEquals(Collections.singleton(ed.getId()), notifier.getEndpointsFor(REMOTE_UUID).keySet());

		EndpointDescription ed2 = getTestEndpointDescription("blah", UUID.randomUUID(), 123,
				Collections.singletonMap(PAREMUS_ORIGIN_ROOT, REMOTE_UUID));
		Mockito.when(filter.accept(same(ed2), anySet())).thenReturn(true);
		notifier.announcementEvent(ed2, 1);

		assertEquals(new HashSet<>(Arrays.asList(ed.getId(), ed2.getId())),
				notifier.getEndpointsFor(REMOTE_UUID).keySet());

		notifier.revocationEvent(ed.getId(), 2);

		assertEquals(Collections.singleton(ed2.getId()), notifier.getEndpointsFor(REMOTE_UUID).keySet());
	}

	private EndpointDescription getTestEndpointDescription(int serviceId) {
        return getTestEndpointDescription("http://myhost:8080/commands", serviceId);
    }

	private EndpointDescription getTestEndpointDescription(String endpointId, int serviceId) {
		return getTestEndpointDescription(endpointId, REMOTE_UUID, serviceId, Collections.emptyMap());
	}

    private EndpointDescription getOtherTestEndpointDescription(String endpointId, int serviceId) {
        HashMap<String, Object> props = new HashMap<>();
        props.put(PAREMUS_ORIGIN_ROOT, OTHER_REMOTE_UUID.toString());
        return getTestEndpointDescription(endpointId, REMOTE_UUID, serviceId, props);
    }

	private EndpointDescription getTestEndpointDescription(String endpointId, UUID remoteId, int serviceId,
			Map<String, Object> props) {
		Map<String, Object> m = new LinkedHashMap<>(props);

		// required
		m.put(OBJECTCLASS, new String[]{"com.acme.HelloService", "some.other.Service"});
		m.put(RemoteConstants.ENDPOINT_FRAMEWORK_UUID, remoteId.toString());
		m.put(RemoteConstants.ENDPOINT_ID, endpointId);
		m.put(RemoteConstants.ENDPOINT_SERVICE_ID, Long.valueOf(serviceId));
		m.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");

		return new EndpointDescription(m);
	}


}
