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
package com.paremus.dosgi.topology.scoped.activator;

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

	
	public String toString() {
		return "EventListener wrapping: " + listener.toString(); 
	}
}
