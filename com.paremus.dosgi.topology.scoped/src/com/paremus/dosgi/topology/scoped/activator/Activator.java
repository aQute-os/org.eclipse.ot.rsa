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

import static org.osgi.framework.Constants.FRAMEWORK_UUID;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;

public class Activator implements BundleActivator {

	private volatile IsolationAwareRSATracker tracker;


	@Override
	public void start(BundleContext context) throws Exception {
		tracker = new IsolationAwareRSATracker(context);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		tracker.destroy();
	}
	
	public static String getUUID(Framework f) {
		BundleContext context = f.getBundleContext();
		return context == null ? "UNKNOWN" : context.getProperty(FRAMEWORK_UUID);
	}
}
