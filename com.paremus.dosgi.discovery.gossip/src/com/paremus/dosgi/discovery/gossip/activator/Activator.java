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
package com.paremus.dosgi.discovery.gossip.activator;

import static com.paremus.dosgi.discovery.gossip.impl.GossipDiscovery.PAREMUS_DISCOVERY_DATA;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static org.osgi.framework.Constants.SERVICE_PID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.tracker.ServiceTracker;

import com.paremus.dosgi.discovery.gossip.impl.Config;
import com.paremus.dosgi.discovery.gossip.impl.GossipDiscovery;
import com.paremus.dosgi.discovery.gossip.local.LocalDiscoveryListener;
import com.paremus.dosgi.discovery.scoped.ScopedDiscovery;
import com.paremus.gossip.cluster.listener.ClusterListener;
import com.paremus.net.encode.EncodingSchemeFactory;

import aQute.bnd.annotation.metatype.Configurable;

@SuppressWarnings("deprecation")
public class Activator implements BundleActivator {

	private volatile BundleContext context;
	
	private boolean active;
	
	private EncodingSchemeFactory encodingSchemeFactory;

	private Config config;

	private GossipDiscovery listener;
	private ServiceRegistration<ScopedDiscovery> discoveryReg;
	private ServiceRegistration<ClusterListener> listenerReg;
	private ServiceRegistration<?> eventlistenerReg;
	
	
	private Lock stateLock = new ReentrantLock();
	
	private volatile ServiceTracker<EncodingSchemeFactory, EncodingSchemeFactory> encodingTracker;


	@Override
	public void start(BundleContext context) throws Exception {
		this.context = context;
		
		stateLock.lock();
		try {
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
				
				GossipDiscovery listenerToDestroy = null;
				ServiceRegistration<?> registrationToUnregister = null;
				ServiceRegistration<?> registrationToUnregister2 = null;
				ServiceRegistration<?> registrationToUnregister3 = null;
				
				boolean updated;
				EncodingSchemeFactory esf;
				Config cfg;
				stateLock.lock();
				try {
					updated = esfUpdater.apply(tracked, getService());
					esf = encodingSchemeFactory;
					cfg = config;
					
					listenerToDestroy = listener;
					registrationToUnregister = discoveryReg;
					registrationToUnregister2 = listenerReg;
					registrationToUnregister3 = eventlistenerReg;
				} finally {
					stateLock.unlock();
				}
				
				if(updated) {
					setup(context, esf, cfg);
					destroy(listenerToDestroy, registrationToUnregister, 
							registrationToUnregister2, registrationToUnregister3);
				}
			}
		};

		encodingTracker.open();
	}

	private void registerManagedService(BundleContext context)
			throws ConfigurationException {
		ManagedService service = this::configUpdate;

		Hashtable<String, Object> table = new Hashtable<String, Object>();
		table.put(SERVICE_PID, "com.paremus.dosgi.discovery.gossip");
		context.registerService(ManagedService.class, service, table);
	}

	private synchronized void configUpdate(Dictionary<String, ?> props) {
		GossipDiscovery listenerToDestroy = null;
		ServiceRegistration<?> registrationToUnregister = null;
		ServiceRegistration<?> registrationToUnregister2 = null;
		ServiceRegistration<?> registrationToUnregister3 = null;

		EncodingSchemeFactory esf = null;
		Config cfg = null;
		stateLock.lock();
		try {
			if(props == null) {
				config = null;
			} else {
				config = Configurable.createConfigurable(Config.class, props);
				esf = encodingSchemeFactory;
				cfg = config;
			}
			
			if(listener != null) {
				listenerToDestroy = listener;
				registrationToUnregister = discoveryReg;
				registrationToUnregister2 = listenerReg;
				registrationToUnregister3 = eventlistenerReg;
				listener = null;
				listenerReg = null;
				eventlistenerReg = null;
			}
		
		} finally {
			stateLock.unlock();
		}
		destroy(listenerToDestroy, registrationToUnregister, 
				registrationToUnregister2, registrationToUnregister3);
		if(cfg != null) {
			setup(context, esf, cfg);
		}
	}

	private void setup(BundleContext context, EncodingSchemeFactory esf, Config cfg) {
		if(!active || esf == null || config == null) {
			return;
		}
		UUID id = UUID.fromString(context.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID));
		LocalDiscoveryListener newlistener = new LocalDiscoveryListener(cfg.rebroadcast_interval());
		GossipDiscovery newDiscovery = new GossipDiscovery(context, id, newlistener, esf, cfg);
		
		ServiceRegistration<ScopedDiscovery> newDiscoveryReg = context.registerService(
				ScopedDiscovery.class, newDiscovery, null);
		
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(ClusterListener.LIMIT_KEYS, Arrays.asList(PAREMUS_DISCOVERY_DATA, PAREMUS_SCOPES_ATTRIBUTE));
		ServiceRegistration<ClusterListener> newClusterListenerReg = 
				context.registerService(ClusterListener.class, newDiscovery::clusterEvent, props);
		
		ServiceRegistration<?> newEndpointEventListenerReg = 
				context.registerService(new String[] {EndpointEventListener.class.getName(),
						EndpointListener.class.getName()}, newlistener, getFilters(cfg, id));
		
		stateLock.lock();
		try {
			if(esf == encodingSchemeFactory && cfg == config) {
				listener = newDiscovery;
				discoveryReg = newDiscoveryReg;
				listenerReg = newClusterListenerReg;
				eventlistenerReg = newEndpointEventListenerReg;
				newDiscovery = null;
				newDiscoveryReg = null;
				newClusterListenerReg = null;
				newEndpointEventListenerReg = null;
			}
		} finally {
			stateLock.unlock();
		}
		destroy(newDiscovery, newDiscoveryReg, newClusterListenerReg, newEndpointEventListenerReg);
	}

	private Hashtable<String, Object> getFilters(Config cfg, UUID id) {
		Hashtable<String, Object> props;
		props = new Hashtable<>();
		List<String> extraFilters = cfg.additional_filters();
		extraFilters = extraFilters == null ? Collections.emptyList() : extraFilters;
		List<String> filters = new ArrayList<>(extraFilters.size() + 1);
		filters.add("("+ RemoteConstants.ENDPOINT_FRAMEWORK_UUID + "=" + id + ")");
		filters.addAll(extraFilters);
		props.put(EndpointEventListener.ENDPOINT_LISTENER_SCOPE, filters);
		return props;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		
		GossipDiscovery listenerToDestroy;
		ServiceRegistration<?> registrationToUnregister;
		ServiceRegistration<?> registrationToUnregister2;
		ServiceRegistration<?> registrationToUnregister3;
		
		stateLock.lock();
		try {
			active = false;
			listenerToDestroy = listener;
			registrationToUnregister = discoveryReg;
			registrationToUnregister2 = listenerReg;
			registrationToUnregister3 = eventlistenerReg;
			listener = null;
			listenerReg = null;
		} finally {
			stateLock.unlock();
		}
		destroy(listenerToDestroy, registrationToUnregister, 
				registrationToUnregister2, registrationToUnregister3);
	}

	private void destroy(GossipDiscovery listenerToDestroy, 
			ServiceRegistration<?> registrationToUnregister,
			ServiceRegistration<?> registrationToUnregister2, 
			ServiceRegistration<?> registrationToUnregister3) {
		if(registrationToUnregister != null) {
			try { 
				registrationToUnregister.unregister(); 
			} catch (IllegalStateException ise) {}
		}

		if(registrationToUnregister2 != null) {
			try { 
				registrationToUnregister2.unregister(); 
			} catch (IllegalStateException ise) {}
		}

		if(registrationToUnregister3 != null) {
			try { 
				registrationToUnregister3.unregister(); 
			} catch (IllegalStateException ise) {}
		}
		
		if(listenerToDestroy != null) {
			listenerToDestroy.destroy();
		}
	}
}
