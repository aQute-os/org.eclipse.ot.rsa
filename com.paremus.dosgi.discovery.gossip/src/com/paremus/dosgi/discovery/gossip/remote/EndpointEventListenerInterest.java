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
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;

class EndpointEventListenerInterest extends AbstractListenerInterest {
	public final EndpointEventListener listener;
	
	public EndpointEventListenerInterest(EndpointEventListener listener,
			ServiceReference<?> ref) {
		super(ref);
		this.listener = listener;
	}

	@Override
	public void sendEvent(EndpointEvent ee, String filter) {
		listener.endpointChanged(ee, filter);
	}
}