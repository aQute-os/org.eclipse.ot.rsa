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
package com.paremus.net.encode.impl;

import java.util.Collections;
import java.util.Hashtable;
import java.util.UUID;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ManagedService;

public class Activator implements BundleActivator {

	private volatile ManagedServiceImpl ms;
	private volatile ServiceRegistration<ManagedService> msReg;

	@Override
	public void start(BundleContext context) throws Exception {
		UUID id = UUID.fromString(context.getProperty(Constants.FRAMEWORK_UUID));
		
		ms = new ManagedServiceImpl(id, context);
		msReg = context.registerService(ManagedService.class, ms, 
				new Hashtable<>(Collections.singletonMap(Constants.SERVICE_PID, "com.paremus.net.encode")));
	}
	
	@Override
	public void stop(BundleContext context) throws Exception {
		if(msReg != null) {
			try {
				msReg.unregister();
			} catch (IllegalStateException ise) {}
		}
		
		ms.destroy();
	}

}
