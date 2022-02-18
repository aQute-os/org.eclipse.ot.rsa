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
package com.paremus.dosgi.discovery.cluster.local;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.REMOVED;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.discovery.cluster.scope.EndpointFilter;

import io.netty.util.concurrent.EventExecutorGroup;

@SuppressWarnings("deprecation")
public class LocalDiscoveryListener implements ServiceFactory<Object> {

	private class EndpointListenerService implements EndpointEventListener, EndpointListener {

		private final Bundle client;

		private final AtomicReference<ListenerType> typeWatcher = new AtomicReference<>();

		public EndpointListenerService(Bundle client) {
			this.client = client;
		}

		@Override
		public void endpointChanged(EndpointEvent event, String filter) {
			checkEventListener();
			worker.execute(() -> endpointNotification(client, event, filter));
		}

		private void checkEventListener() {
			if(typeWatcher.updateAndGet(old -> old == null ? ListenerType.EVENT_LISTENER : old)
					!= ListenerType.EVENT_LISTENER) {
				throw new IllegalStateException("An RSA 1.1 EndpointEventListener must not be "
							+ "called in addition to an EndpointListener from the same bundle");
			}
		}

		@Override
		public void endpointRemoved(EndpointDescription endpoint,
				String matchedFilter) {
			checkListener();
			worker.execute(() -> endpointNotification(client,
					new EndpointEvent(REMOVED, endpoint), matchedFilter));
		}

		@Override
		public void endpointAdded(EndpointDescription endpoint, String matchedFilter) {
			checkListener();
			worker.execute(() -> endpointNotification(client,
					new EndpointEvent(ADDED, endpoint), matchedFilter));
		}

		private void checkListener() {
			if(typeWatcher.updateAndGet(old -> old == null ? ListenerType.LISTENER : old)
					!= ListenerType.LISTENER) {
				throw new IllegalStateException("An RSA 1.1 EndpointListener must not be "
						+ "called in addition to an EndpointEventListener from the same bundle");
			}
		}
	}

	private static enum ListenerType {
		LISTENER, EVENT_LISTENER;
	}

	private static final Logger logger = LoggerFactory.getLogger(LocalDiscoveryListener.class);

	private final ConcurrentMap<Bundle, Map<String, Integer>> sponsoredEndpoints = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, EndpointDescription> localEndpoints = new ConcurrentHashMap<>();

	private final ConcurrentMap<UUID, RemoteDiscoveryEndpoint> remotes = new ConcurrentHashMap<>();
	private final ConcurrentMap<UUID, Set<String>> remoteSponsors = new ConcurrentHashMap<>();

	private final Lock publishLock = new ReentrantLock();

	private final EventExecutorGroup worker;

	private final ScheduledFuture<?> reminderTask;

	public LocalDiscoveryListener(long reminderInterval, EventExecutorGroup worker) {
		this.worker = worker;
		reminderTask = worker.scheduleAtFixedRate(
				() -> remotes.values().stream().forEach(RemoteDiscoveryEndpoint::sendReminder),
				reminderInterval, reminderInterval, TimeUnit.MILLISECONDS);
	}

	@Override
	public Object getService(Bundle sponsor,
			ServiceRegistration<Object> registration) {
		return new EndpointListenerService(sponsor);
	}

	@Override
	public void ungetService(Bundle sponsor,
			ServiceRegistration<Object> registration,
			Object service) {
		publishLock.lock();
		try {
			Map<String, Integer> endpoints = sponsoredEndpoints.remove(sponsor);
			if(endpoints != null) {
				endpoints.forEach((k,v) -> {
					EndpointDescription ed = localEndpoints.get(k);
					remotes.values().forEach(r -> r.revokeEndpoint(v + 1, ed));
				});

				localEndpoints.keySet().removeAll(endpoints.keySet());
			}
		} finally {
			publishLock.unlock();
		}
	}

	public void destroy() {
		reminderTask.cancel(true);
	}

	public void updateRemote(String clusterName, UUID id, int port, EndpointFilter endpointFilter,
			Supplier<RemoteDiscoveryEndpoint> generator) {
		publishLock.lock();
		try {
			remoteSponsors.compute(id, (i, v) -> {
				Set<String> s = (v == null) ? new HashSet<>() : new HashSet<>(v);
				s.add(clusterName);
				return s;
			});
			RemoteDiscoveryEndpoint rd = remotes.computeIfAbsent(id, x -> generator.get());
			rd.update(port, endpointFilter);
			rd.open();
			publishAllEndpoints(rd);
		} finally {
			publishLock.unlock();
		}
	}

	public boolean removeRemote(String clusterName, UUID id) {
		publishLock.lock();
		try {
			if(remoteSponsors.computeIfPresent(id, (i,v) -> {
				Set<String> s = v.stream()
						.filter(f -> !clusterName.equals(f))
						.collect(Collectors.toSet());
				return s.isEmpty() ? null : s;
			}) == null) {
				RemoteDiscoveryEndpoint rde = remotes.remove(id);
				if(rde != null) {
					rde.stopCalling();
				}
				return true;
			}
			return false;
		} finally {
			publishLock.unlock();
		}
	}

	public Collection<UUID> removeRemotesForCluster(String clusterName) {
		publishLock.lock();
		try {
			//All ids that reference the cluster
			Collection<UUID> ids = remoteSponsors.entrySet().stream()
				.filter(e -> e.getValue().contains(clusterName))
				.map(Entry::getKey)
				.collect(toSet());

			//All ids that no longer have a sponsor
			ids = ids.stream()
				.filter(i -> remoteSponsors.computeIfPresent(i, (x,v) -> {
					Set<String> set = v.stream()
							.filter(s -> !clusterName.equals(s))
							.collect(toSet());
					return set.isEmpty() ? null : set;
				}) == null).collect(toSet());

			//Ids that have a remote to remove
			ids = ids.stream()
				.filter(remotes::containsKey)
				.collect(toSet());
			//Clear the relevant remotes
			ids.stream()
				.map(remotes::get)
				.filter(rd -> rd != null)
				.forEach(RemoteDiscoveryEndpoint::stopCalling);

			remotes.keySet().removeAll(ids);

			return ids;
		} finally {
			publishLock.unlock();
		}
	}

	private void publishAllEndpoints(RemoteDiscoveryEndpoint rd) {
		publishLock.lock();
		try {
			if(logger.isDebugEnabled()) {
				logger.debug("Publishing endpoints {} to {}",
						new Object[] {localEndpoints.values(), rd.getId()});
			}

			sponsoredEndpoints.values().forEach(m -> m.entrySet().forEach(
					e -> {
						EndpointDescription ed = localEndpoints.get(e.getKey());
						rd.publishEndpoint(e.getValue(), ed, false);
					}));
		} finally {
			publishLock.unlock();
		}
	}

	private void endpointNotification(Bundle sponsor, EndpointEvent event, String filter) {

		if(logger.isDebugEnabled()) {
			String type;
			switch(event.getType()) {
				case EndpointEvent.ADDED:
					type = "ADDED";
					break;
				case EndpointEvent.REMOVED:
					type = "REMOVED";
					break;
				case EndpointEvent.MODIFIED:
					type = "MODIFIED";
					break;
				case EndpointEvent.MODIFIED_ENDMATCH:
					type = "MODIFIED_ENDMATCH";
					break;
				default:
					type = "UNKNOWN";
			}

			logger.debug("Received local endpoint event {} from bundle {} for endpoint {}",
					new Object[] {type, sponsor.getBundleId(), event.getEndpoint().getId()});
		}

		final EndpointDescription endpoint = event.getEndpoint();
		final String endpointId = endpoint.getId();
		int eventType = event.getType();

		switch(eventType) {
			case EndpointEvent.ADDED:
			case EndpointEvent.MODIFIED:
				endpointUpdate(sponsor, endpoint, endpointId, eventType);
				return;
			case EndpointEvent.MODIFIED_ENDMATCH:
			case EndpointEvent.REMOVED:
				endpointLeaving(sponsor, endpointId);
				return;
		}
	}

	private void endpointUpdate(final Bundle sponsor, final EndpointDescription endpoint, final String endpointId,
			final int eventType) {
		publishLock.lock();
		try {
			if(sponsoredEndpoints.entrySet().stream().filter(e -> !sponsor.equals(e.getKey()))
				.anyMatch(e -> e.getValue().containsKey(endpointId))) {
				logger.error("Two different bundles have attempted to add or modify the same endpoint {}. This is not supported, and so this update will be ignored",
						endpointId);
				return;
			}
			Integer counter = sponsoredEndpoints.compute(sponsor, (k,v) -> {
				if(v != null && v.containsKey(endpointId) && eventType == EndpointEvent.ADDED) {
					logger.warn("Multiple ADDED events have been received for the endpoint {}. All adds after the first are treated as modifications", endpointId);
				}
				Map<String, Integer> endpoints = (v == null) ? new HashMap<>() : new HashMap<>(v);
				endpoints.merge(endpointId, 1, (e,i) -> e +=1);
				return endpoints;
			}).get(endpointId);
			localEndpoints.put(endpointId, endpoint);

			if(logger.isDebugEnabled()) {
				logger.debug("Publishing update {} for endpoint {} from {} to {}",
					new Object[] {counter, endpointId, endpoint.getFrameworkUUID(),
						remotes.values().stream().collect(toSet())});
			}

			remotes.values().forEach(r -> r.publishEndpoint(counter, endpoint, false));
		} finally {
			publishLock.unlock();
		}
	}

	private void endpointLeaving(final Bundle sponsor, final String endpointId) {
		publishLock.lock();
		try {
			//We need the original Endpoint to check matching
			EndpointDescription ed = localEndpoints.remove(endpointId);
			Integer counter = sponsoredEndpoints.getOrDefault(sponsor, new HashMap<>()).get(endpointId);
			if(counter != null) {
				sponsoredEndpoints.computeIfPresent(sponsor,
						(k,v) -> v.entrySet().stream().filter(e -> !endpointId.equals(e.getKey()))
						.collect(toMap(Entry::getKey, Entry::getValue)));
				Integer revocationCounter = counter + 1;

				if(logger.isDebugEnabled()) {
					logger.debug("Revoking endpoint {} with counter {}",
						new Object[] {endpointId, counter});
				}

				remotes.values().forEach(r -> r.revokeEndpoint(revocationCounter, ed));
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("No endpoint to revoke for id {}", endpointId);
				}
			}
		} finally {
			publishLock.unlock();
		}
	}

	public void republish(String endpointId, UUID remoteNode) {
		ofNullable(remotes.get(remoteNode)).ifPresent(r -> {
			publishLock.lock();
			try {
				if(logger.isDebugEnabled()) {
					logger.debug("Republishing endpoint {} to node {}",
						new Object[] {endpointId, remoteNode});
				}
				EndpointDescription ed = localEndpoints.get(endpointId);
				if(ed != null) {
					sponsoredEndpoints.values().stream()
						.filter(m -> m.containsKey(endpointId))
						.map(m -> m.get(endpointId))
						.findFirst().ifPresent(counter -> r.publishEndpoint(counter, ed, true));
				}
			} finally {
				publishLock.unlock();
			}
		});
	}
}
