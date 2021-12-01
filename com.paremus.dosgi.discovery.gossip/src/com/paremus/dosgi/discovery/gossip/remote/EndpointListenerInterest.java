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
package com.paremus.dosgi.discovery.gossip.remote;

import org.osgi.framework.ServiceReference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("deprecation")
class EndpointListenerInterest extends AbstractListenerInterest {
	
	private static final Logger logger = LoggerFactory.getLogger(EndpointListenerInterest.class);
	
	public final EndpointListener listener;
	
	public EndpointListenerInterest(EndpointListener listener,
			ServiceReference<?> ref) {
		super(ref);
		this.listener = listener;
	}

	@Override
	public void sendEvent(EndpointEvent ee, String filter) {
		EndpointDescription endpoint = ee.getEndpoint();
		switch(ee.getType()) {
			case EndpointEvent.MODIFIED :
				if(logger.isDebugEnabled()) { 
					logger.debug("EndpointListener services are unable to handle modification, removing and re-adding the endpoint {}", endpoint.getId()); 
				}
				listener.endpointRemoved(endpoint, filter);
			case EndpointEvent.ADDED :
				listener.endpointAdded(endpoint, filter);
				break;
			case EndpointEvent.MODIFIED_ENDMATCH :
			case EndpointEvent.REMOVED :
				listener.endpointRemoved(endpoint, filter);
		}
	}
}