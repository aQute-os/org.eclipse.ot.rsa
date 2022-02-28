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
package org.eclipse.ot.rsa.topology.promiscuous.common;

import static org.osgi.framework.Constants.SERVICE_ID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.osgi.framework.ServiceReference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EndpointEventListenerInterest {
	private static final Logger logger = LoggerFactory.getLogger(EndpointEventListenerInterest.class);

	private List<String> filters;
	private final EndpointEventListener listener;
	private final ServiceReference<?> listenerRef;

	private final Map<EndpointDescription, String> trackedEndpoints = new HashMap<>();

	public EndpointEventListenerInterest(EndpointEventListener listener,
			ServiceReference<?> listenerRef, List<String> filters) {
		this.listener = listener;
		this.listenerRef = listenerRef;
		updateFilters(filters);
	}

	public void updateFilters(List<String> filters) {

		this.filters = new ArrayList<>(filters);
		if(this.filters.isEmpty()) {
			logger.warn("The RSA endpoint listener {} with service id {} from bundle {} does not specify any filters so no endpoints will be passed to it",
					new Object[] {listener, listenerRef.getProperty(SERVICE_ID), listenerRef.getBundle()});
		}
		trackedEndpoints.keySet().removeIf(ed -> getMatchingFilter(ed) == null);
	}

	public void notify(EndpointDescription oldEd, EndpointDescription newEd) {
		if(oldEd == null && newEd != null) {
			String filter = getMatchingFilter(newEd);
			if(filter != null && trackedEndpoints.put(newEd, filter) == null) {
				listener.endpointChanged(new EndpointEvent(EndpointEvent.ADDED, newEd), filter);
			}
		} else if (oldEd != null && newEd != null) {
			String oldFilter = trackedEndpoints.get(oldEd);
			if(oldFilter != null) {
				String filter = getMatchingFilter(newEd);
				if(filter != null) {
					trackedEndpoints.put(newEd, filter);
					listener.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED, newEd), filter);
				} else {
					trackedEndpoints.remove(oldEd);
					listener.endpointChanged(new EndpointEvent(EndpointEvent.MODIFIED_ENDMATCH, newEd), oldFilter);
				}
			} else {
				notify(null, newEd);
			}
		} else if (oldEd != null && newEd == null) {
			String oldFilter = trackedEndpoints.remove(oldEd);
			if(oldFilter != null) {
				listener.endpointChanged(new EndpointEvent(EndpointEvent.REMOVED, oldEd), oldFilter);
			}
		}
	}

	private String getMatchingFilter(EndpointDescription ed) {
		return filters.stream().filter(ed::matches).findFirst().orElse(null);
	}
}
