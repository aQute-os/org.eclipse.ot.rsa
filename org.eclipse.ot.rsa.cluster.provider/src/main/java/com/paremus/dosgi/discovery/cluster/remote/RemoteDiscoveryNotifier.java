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

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_ORIGIN_ROOT;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.MODIFIED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.MODIFIED_ENDMATCH;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.REMOVED;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import com.paremus.dosgi.discovery.cluster.scope.EndpointFilter;

import io.netty.util.concurrent.EventExecutorGroup;

@SuppressWarnings("deprecation")
public class RemoteDiscoveryNotifier {

	private final EventExecutorGroup notificationWorker;

	private final Lock remoteLock = new ReentrantLock();

	private final ConcurrentMap<String, EndpointDescription> remoteEndpoints = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Integer> remoteEndpointStates = new ConcurrentHashMap<>();

	private final ServiceTracker<Object, AbstractListenerInterest> eelTracker;

	private final EndpointFilter filter;

	public RemoteDiscoveryNotifier(EndpointFilter filter, BundleContext context,
			EventExecutorGroup remoteEventNotifier) {
		this.filter = filter;
		this.notificationWorker = remoteEventNotifier;
		try {
			eelTracker = new ServiceTracker<>(context,
					FrameworkUtil.createFilter("(|(" + Constants.OBJECTCLASS + "="
					+ EndpointEventListener.class.getName() + ")("
					+ Constants.OBJECTCLASS + "=" + EndpointListener.class.getName()
					+ "))"),
					new ServiceTrackerCustomizer<Object, AbstractListenerInterest>(){

				@Override
				public AbstractListenerInterest addingService(ServiceReference<Object> reference) {
					AbstractListenerInterest interest;

					Object service = context.getService(reference);
					if(service instanceof EndpointEventListener) {
						interest = new EndpointEventListenerInterest((EndpointEventListener) service, reference);
					} else if (service instanceof EndpointListener) {
						interest = new EndpointListenerInterest((EndpointListener) service, reference);
					} else {
						return null;
					}
					notifyAdd(interest);
					return interest;
				}

				@Override
				public void modifiedService(ServiceReference<Object> reference,
						AbstractListenerInterest interest) {
					remoteLock.lock();
					try {
						List<String> oldFilters = interest.updateFilters(reference);
						notifyUpdate(oldFilters, interest);
					} finally {
						remoteLock.unlock();
					}
				}

				@Override
				public void removedService(ServiceReference<Object> reference,
						AbstractListenerInterest service) {
					remoteLock.lock();
					try {
						context.ungetService(reference);
					} finally {
						remoteLock.unlock();
					}
				}
			});
		} catch (InvalidSyntaxException e) {
			//This cannot happen with a static filter;
			throw new RuntimeException(e);
		}

		eelTracker.open();
	}

	public void filterChange() {
		remoteLock.lock();
		try {
			Set<String> filtered = remoteEndpoints.values().stream().
				filter(e -> !filter.accept(e, emptySet()))
				.map(EndpointDescription::getId)
				.collect(toSet());

			filtered.stream().forEach(e -> revocationEvent(e, remoteEndpointStates.get(e)));
		} finally {
			remoteLock.unlock();
		}
	}

	public void announcementEvent(EndpointDescription ed, Integer state) {
		remoteLock.lock();
		try {
			String endpointId = ed.getId();
			Integer previous = remoteEndpointStates.get(endpointId);

			if(filter.accept(ed, emptySet())) {
				if(previous == null) {
					remoteEndpointStates.put(endpointId, state);
					remoteEndpoints.put(endpointId, ed);
					notifyAdd(ed);

				} else if (state.compareTo(previous) > 0) {
					remoteEndpointStates.put(endpointId, state);
					EndpointDescription oldEd = remoteEndpoints.put(endpointId, ed);
					notifyUpdate(oldEd, ed);
				}
			} else {
				revocationEvent(endpointId, state);
			}
		} finally {
			remoteLock.unlock();
		}
	}

	public void revokeAllFromFramework(UUID id) {
		remoteLock.lock();
		try {
            getEndpointsFor(id).keySet()
                    .forEach(endpointId -> revocationEvent(endpointId, Integer.MAX_VALUE));
		} finally {
			remoteLock.unlock();
		}
	}

	public void revocationEvent(String endpointId, Integer state) {
		remoteLock.lock();
		try {
			if(remoteEndpointStates.computeIfPresent(endpointId, (k,v) -> state.compareTo(v) >= 0 ? null : v) == null) {
				EndpointDescription ed = remoteEndpoints.remove(endpointId);
				if(ed != null) {
					notifyRemove(ed);
				}
			}
		} finally {
			remoteLock.unlock();
		}
	}

	public void destroy() {
		eelTracker.close();
	}

	private void notifyAdd(EndpointDescription ed) {
		remoteLock.lock();
		try {
			eelTracker.getTracked().values().stream().forEach(interest -> {
					String filter = interest.isInterested(ed);
					if(filter != null) {
						notificationWorker.execute(
								() -> interest.sendEvent(new EndpointEvent(ADDED, ed), filter));
					}
			});
		} finally {
			remoteLock.unlock();
		}
	}

	private void notifyAdd(AbstractListenerInterest interest) {
		remoteLock.lock();
		try {
			remoteEndpoints.values().stream().forEach(ed -> {
				String filter = interest.isInterested(ed);
				if(filter != null) {
					notificationWorker.execute(
							() -> interest.sendEvent(new EndpointEvent(ADDED, ed), filter));
				}
			});
		} finally {
			remoteLock.unlock();
		}
	}

	private void notifyUpdate(EndpointDescription previous, EndpointDescription current) {
		remoteLock.lock();
		try {
			eelTracker.getTracked().values().stream().forEach(interest -> {
					String oldFilter = interest.isInterested(previous);
					String newFilter = interest.isInterested(current);
					notifyUpdate(current, interest, oldFilter, newFilter);
				});
		} finally {
			remoteLock.unlock();
		}
	}

	private void notifyUpdate(List<String> oldFilters, AbstractListenerInterest interest) {
		remoteLock.lock();
		try {
			remoteEndpoints.values().stream().forEach(ed -> {
				String oldFilter = oldFilters.stream().filter(ed::matches).findFirst().orElse(null);
				String newFilter = interest.isInterested(ed);
				notifyUpdate(ed, interest, oldFilter, newFilter);
			});
		} finally {
			remoteLock.unlock();
		}
	}

	private void notifyUpdate(EndpointDescription current,
			AbstractListenerInterest interest, String oldFilter,
			String newFilter) {
		EndpointEvent ee;
		String filterToPass;
		if(oldFilter != null) {
			if(newFilter != null) {
				ee = new EndpointEvent(MODIFIED, current);
				filterToPass = newFilter;
			} else {
				ee = new EndpointEvent(MODIFIED_ENDMATCH, current);
				filterToPass = oldFilter;
			}
		} else if(newFilter != null) {
			ee = new EndpointEvent(ADDED, current);
			filterToPass = newFilter;
		} else {
			return;
		}
		notificationWorker.execute(
				() -> interest.sendEvent(ee, filterToPass));
	}

	private void notifyRemove(EndpointDescription ed) {
		remoteLock.lock();
		try {
			eelTracker.getTracked().values().stream().forEach(interest -> {
					String filter = interest.isInterested(ed);
					if(filter != null) {
						notificationWorker.execute(
								() -> interest.sendEvent(new EndpointEvent(REMOVED, ed), filter));
					}
			});
		} finally {
			remoteLock.unlock();
		}
	}

	public Map<String, Integer> getEndpointsFor(UUID remoteNodeId) {
		String stringRemoteId = remoteNodeId.toString();
		return remoteEndpoints.values().stream()
			.filter(ed -> stringRemoteId.equals(ed.getFrameworkUUID()) ||
					stringRemoteId.equals(String.valueOf(ed.getProperties().get(PAREMUS_ORIGIN_ROOT))))
			.map(EndpointDescription::getId)
			.collect(toMap(id -> id, remoteEndpointStates::get));
	}
}
