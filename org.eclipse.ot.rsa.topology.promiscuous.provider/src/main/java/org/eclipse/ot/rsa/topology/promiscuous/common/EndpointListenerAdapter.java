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

import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("deprecation")
public class EndpointListenerAdapter implements EndpointEventListener {

	private static final Logger logger = LoggerFactory.getLogger(EndpointListenerAdapter.class);

	private final EndpointListener listener;

	public EndpointListenerAdapter(EndpointListener listener) {
		this.listener = listener;
	}

	@Override
	public void endpointChanged(EndpointEvent event, String filter) {
		switch(event.getType()) {
		case EndpointEvent.MODIFIED :
				listener.endpointRemoved(event.getEndpoint(), filter);
			case EndpointEvent.ADDED :
				listener.endpointAdded(event.getEndpoint(), filter);
				break;
			case EndpointEvent.MODIFIED_ENDMATCH :
			case EndpointEvent.REMOVED :
				listener.endpointRemoved(event.getEndpoint(), filter);
				break;
			default :
				logger.error("An unknown event type {} occurred for endpoint {}",
						new Object[] {event.getType(), event.getEndpoint()});
		}
	}

	@Override
	public String toString() {
		return "EventListener wrapping: " + listener.toString();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((listener == null) ? 0 : listener.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if ((obj == null) || (getClass() != obj.getClass()))
			return false;
		EndpointListenerAdapter other = (EndpointListenerAdapter) obj;
		if (listener == null) {
			if (other.listener != null)
				return false;
		} else if (!listener.equals(other.listener))
			return false;
		return true;
	}
}
