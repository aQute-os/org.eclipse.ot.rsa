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
package com.paremus.dosgi.net.activator;

import static org.osgi.framework.Constants.SERVICE_PID;

import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.tracker.ServiceTracker;

import com.paremus.dosgi.net.config.Config;
import com.paremus.dosgi.net.impl.RemoteServiceAdminFactoryImpl;
import com.paremus.dosgi.topology.scoped.IsolationAwareRemoteServiceAdmin;
import com.paremus.net.encode.EncodingSchemeFactory;

import aQute.bnd.annotation.metatype.Configurable;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;

public class Activator implements BundleActivator {

	private static final ByteBufAllocator allocator = new PooledByteBufAllocator(true);
	
	private volatile BundleContext context;
	
	private boolean active;
	
	private EncodingSchemeFactory encodingSchemeFactory;

	private Config config;

	private RemoteServiceAdminFactoryImpl _rsaFactory;

	private ServiceRegistration<?> _rsaFactoryReg;
	
	private Lock stateLock = new ReentrantLock();
	
	private volatile ServiceTracker<EncodingSchemeFactory, EncodingSchemeFactory> encodingTracker;


	@Override
	public void start(BundleContext context) throws Exception {
		this.context = context;
		
		stateLock.lock();
		try {
			config = Configurable.createConfigurable(Config.class, new HashMap<>());
			active = true;
		} finally {
			stateLock.unlock();
		}
		registerManagedService(context);
		
		BiFunction<EncodingSchemeFactory, EncodingSchemeFactory, Boolean> esfUpdater = 
				(a, b) -> {
					if(encodingSchemeFactory == a) {
						encodingSchemeFactory = b;
						return true;
					} else {
						return false;
					}
				};
		
		encodingTracker = new ServiceTracker<EncodingSchemeFactory, EncodingSchemeFactory>(
				context, EncodingSchemeFactory.class, null) {
			@Override
			public EncodingSchemeFactory addingService(ServiceReference<EncodingSchemeFactory> reference) {
				
				EncodingSchemeFactory tracked = super.addingService(reference);
				
				boolean updated;
				EncodingSchemeFactory esf;
				Config cfg;
				stateLock.lock();
				try {
					updated = esfUpdater.apply(null, tracked);
					esf = encodingSchemeFactory;
					cfg = config;
				} finally {
					stateLock.unlock();
				}
				
				if(updated && cfg != null) {
					setup(context, esf, cfg);
				}
				
				return tracked;
			}

			@Override
			public void removedService(ServiceReference<EncodingSchemeFactory> reference,
					EncodingSchemeFactory tracked) {
				
				RemoteServiceAdminFactoryImpl rsaFactoryToDestroy = null;
				ServiceRegistration<?> registrationToUnregister = null;
				
				boolean updated;
				EncodingSchemeFactory esf;
				Config cfg;
				stateLock.lock();
				try {
					updated = esfUpdater.apply(tracked, getService());
					esf = encodingSchemeFactory;
					cfg = config;
					
					rsaFactoryToDestroy = _rsaFactory;
					registrationToUnregister = _rsaFactoryReg;
				} finally {
					stateLock.unlock();
				}
				
				if(updated) {
					setup(context, esf, cfg);
					destroy(rsaFactoryToDestroy, registrationToUnregister);
				}
			}
		};

		encodingTracker.open();
	}

	private void registerManagedService(BundleContext context)
			throws ConfigurationException {
		ManagedService service = this::configUpdate;

		Hashtable<String, Object> table = new Hashtable<String, Object>();
		table.put(SERVICE_PID, "com.paremus.dosgi.net");
		context.registerService(ManagedService.class, service, table);
	}

	private synchronized void configUpdate(Dictionary<String, ?> props) {
		RemoteServiceAdminFactoryImpl rsaToDestroy = null;
		ServiceRegistration<?> registrationToUnregister = null;

		EncodingSchemeFactory esf = null;
		Config cfg = null;
		stateLock.lock();
		try {
			if(props == null) {
				config = Configurable.createConfigurable(Config.class, new HashMap<>());
			} else {
				config = Configurable.createConfigurable(Config.class, props);
				esf = encodingSchemeFactory;
				cfg = config;
			}
			
			if(_rsaFactory != null) {
				rsaToDestroy = _rsaFactory;
				registrationToUnregister = _rsaFactoryReg;
				_rsaFactory = null;
				_rsaFactoryReg = null;
			}
		} finally {
			stateLock.unlock();
		}
		destroy(rsaToDestroy, registrationToUnregister);
		if(cfg != null) {
			setup(context, esf, cfg);
		}
	}

	private void setup(BundleContext context, EncodingSchemeFactory esf, Config cfg) {
		if(!active || esf == null || config == null) {
			return;
		}
		RemoteServiceAdminFactoryImpl newRSA = new RemoteServiceAdminFactoryImpl(config, esf, allocator);
		
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(RemoteConstants.REMOTE_INTENTS_SUPPORTED, newRSA.getSupportedIntents());
        props.put(RemoteConstants.REMOTE_CONFIGS_SUPPORTED, Collections.singletonList("com.paremus.dosgi.net"));
        
		ServiceRegistration<?> newRSAReg = context.registerService(new String[] {
				RemoteServiceAdmin.class.getName(), IsolationAwareRemoteServiceAdmin.class.getName()}, 
				newRSA, props);
		
		stateLock.lock();
		try {
			if(esf == encodingSchemeFactory && cfg == config) {
				_rsaFactory = newRSA;
				_rsaFactoryReg = newRSAReg;
				newRSA = null;
				newRSAReg = null;
			}
		} finally {
			stateLock.unlock();
		}
		destroy(newRSA, newRSAReg);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		
		RemoteServiceAdminFactoryImpl rsaToDestroy = null;
		ServiceRegistration<?> registrationToUnregister = null;
		
		stateLock.lock();
		try {
			active = false;
			rsaToDestroy = _rsaFactory;
			registrationToUnregister = _rsaFactoryReg;
			_rsaFactory = null;
			_rsaFactoryReg = null;
		} finally {
			stateLock.unlock();
		}
		destroy(rsaToDestroy, registrationToUnregister);
	}

	private void destroy(RemoteServiceAdminFactoryImpl rsaToDestroy, 
			ServiceRegistration<?> registrationToUnregister) {
		if(registrationToUnregister != null) {
			try { 
				registrationToUnregister.unregister(); 
			} catch (IllegalStateException ise) {}
		}
		
		if(rsaToDestroy != null) {
			rsaToDestroy.close();
		}
	}
}
