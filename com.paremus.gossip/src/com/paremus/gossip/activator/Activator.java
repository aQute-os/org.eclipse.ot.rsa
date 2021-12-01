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
package com.paremus.gossip.activator;

import java.util.Collections;
import java.util.Hashtable;
import java.util.UUID;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.util.tracker.ServiceTracker;

import com.paremus.net.encode.EncodingSchemeFactory;

public class Activator implements BundleActivator {

	private volatile ManagedServiceFactoryImpl msf;
	private volatile ServiceRegistration<ManagedServiceFactory> msfReg;
	
	private EncodingSchemeFactory es;
	
	private ServiceTracker<EncodingSchemeFactory, EncodingSchemeFactory> tracker;

	@Override
	public void start(BundleContext context) throws Exception {
		UUID id = UUID.fromString(context.getProperty(Constants.FRAMEWORK_UUID));
		
		tracker = new ServiceTracker<EncodingSchemeFactory, EncodingSchemeFactory>(context, EncodingSchemeFactory.class, null) {

			@Override
			public EncodingSchemeFactory addingService(
					ServiceReference<EncodingSchemeFactory> reference) {
				
				EncodingSchemeFactory es = super.addingService(reference);
				
				addIfNeeded(id, es);
				
				return es;
			}

			private void addIfNeeded(UUID id, EncodingSchemeFactory esf) {
				ManagedServiceFactoryImpl msfImpl = null;
				ServiceRegistration<ManagedServiceFactory> reg = null;
				boolean create = false;
				synchronized (this) {
					if(Activator.this.es == null) {
						Activator.this.es = esf;
						create = true;
					}
				}
				
				if(create) {
					msfImpl = new ManagedServiceFactoryImpl(id, context, esf);
					reg = context.registerService(ManagedServiceFactory.class, msfImpl, 
							new Hashtable<>(Collections.singletonMap(Constants.SERVICE_PID, "com.paremus.gossip")));
				} else {
					return;
				}
				
				boolean destroy = false;
				synchronized (this) {
					if(Activator.this.es == esf) {
						msf = msfImpl;
						msfReg = reg;
					} else {
						destroy = true;
					}
				}
				
				if(destroy) {
					if(reg != null) {
						reg.unregister();
					}
					if(msfImpl != null) {
						msfImpl.destroy();
					}
				}
			}

			@Override
			public void removedService(
					ServiceReference<EncodingSchemeFactory> reference,
					EncodingSchemeFactory service) {
				ManagedServiceFactoryImpl msfImpl = null;
				ServiceRegistration<ManagedServiceFactory> reg = null;
				
				synchronized(this) {
					if(Activator.this.es == es) {
						Activator.this.es = null;
						msfImpl = msf;
						msf = null;
						reg = msfReg;
						msfReg = null;
					}
				}
				
				if(reg != null) {
					reg.unregister();
					msfImpl.destroy();
				}
				
				EncodingSchemeFactory newScheme = getService();
				if(newScheme != null)
					addIfNeeded(id, newScheme);
				
				super.removedService(reference, service);
			}
		};
		
		tracker.open();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		tracker.close();
	}
}
