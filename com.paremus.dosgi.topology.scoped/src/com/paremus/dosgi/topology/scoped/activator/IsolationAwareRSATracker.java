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

import static java.util.Optional.ofNullable;
import static com.paremus.dosgi.topology.scoped.activator.Activator.getUUID;
import static org.osgi.framework.Constants.SERVICE_ID;
import static org.osgi.framework.FrameworkUtil.createFilter;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.deployment.framework.provider.ChildFrameworkEvent;
import com.paremus.deployment.framework.provider.ChildFrameworkListener;
import com.paremus.dosgi.topology.scoped.IsolationAwareRemoteServiceAdmin;
import com.paremus.dosgi.topology.scoped.impl.ServiceExporter;
import com.paremus.dosgi.topology.scoped.impl.ServiceImporter;

@SuppressWarnings("deprecation")
public class IsolationAwareRSATracker implements ChildFrameworkListener {
	
	private static final Logger logger = LoggerFactory.getLogger(IsolationAwareRSATracker.class);

	private final ServiceImporter importer;
	
	private final ServiceExporter exporter;

	private final ServiceRegistration<ChildFrameworkListener> serviceRegistration;
	
	private final ConcurrentMap<Framework, ServiceRegistration<?>>
		listenerRegistrations = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Framework, ServiceTracker<RemoteServiceAdmin, RemoteServiceAdmin>> childRSAs = 
			new ConcurrentHashMap<>();

	private final ServiceTracker<RemoteServiceAdmin, RemoteServiceAdmin> localRSATracker;

	private final ConcurrentMap<Framework, ServiceTracker<Object, 
		EndpointEventListener>> childEndpointListeners = new ConcurrentHashMap<>();
	
	private final ServiceTracker<Object, EndpointEventListener> 
		localELTracker;

	private ConcurrentMap<Framework, ServiceTracker<Object, ServiceReference<Object>>> 
		childRemotableServices = new ConcurrentHashMap<>();

	private final ServiceTracker<Object, ServiceReference<Object>> 
		localRemotableServices;
	
	private static Filter getFilter(String s) {
		try {
			return createFilter(s);
		} catch (InvalidSyntaxException ise) {
			throw new RuntimeException(ise);
		}
	}

	private class FrameworkRSACustomiser extends 
		ServiceTracker<RemoteServiceAdmin, RemoteServiceAdmin> {

		private final Framework framework;
		
		public FrameworkRSACustomiser(Framework framework) {
			super(framework.getBundleContext(), RemoteServiceAdmin.class, null);
			this.framework = framework;
		}

		@Override
		public RemoteServiceAdmin addingService(
				ServiceReference<RemoteServiceAdmin> reference) {
			RemoteServiceAdmin rsa = super.addingService(reference);
			if(rsa instanceof IsolationAwareRemoteServiceAdmin) {
				if(logger.isDebugEnabled()) {
					logger.debug("The tracker for framework {} discovered an isolation aware RSA with service id {}",
							getUUID(framework), reference.getProperty(SERVICE_ID));
				}
				IsolationAwareRemoteServiceAdmin awareRSA = (IsolationAwareRemoteServiceAdmin)rsa;
				importer.addingRSA(awareRSA);
				exporter.addingRSA(awareRSA);
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("The tracker for framework {} discovered an RSA with service id {}",
							getUUID(framework), reference.getProperty(SERVICE_ID));
				}
				importer.addingRSA(framework, rsa);
				exporter.addingRSA(framework, rsa);
			}
			return rsa;
		}

		@Override
		public void removedService(
				ServiceReference<RemoteServiceAdmin> reference,
				RemoteServiceAdmin rsa) {
			if(rsa instanceof IsolationAwareRemoteServiceAdmin) {
				if(logger.isDebugEnabled()) {
					logger.debug("The isolation aware RSA with service id {} in framework {} is being removed",
							getUUID(framework), reference.getProperty(SERVICE_ID));
				}
				exporter.removingRSA((IsolationAwareRemoteServiceAdmin)rsa);
				importer.removingRSA((IsolationAwareRemoteServiceAdmin)rsa);
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("The RSA with service id {} in framework {} is being removed",
							getUUID(framework), reference.getProperty(SERVICE_ID));
				}
				exporter.removingRSA(framework, rsa);
				importer.removingRSA(framework, rsa);
			}
			super.removedService(reference, rsa);
		}
	}

	private class FrameworkELTracker extends 
		ServiceTracker<Object, EndpointEventListener> {
		
		private final Framework framework;
		
		public FrameworkELTracker(Framework framework) {
			super(framework.getBundleContext(), getFilter("(|(" + Constants.OBJECTCLASS + "="
							+ EndpointEventListener.class.getName() + ")(" 
							+ Constants.OBJECTCLASS + "=" + EndpointListener.class.getName()
							+ "))"), null);
			this.framework = framework;
		}
		
		@Override
		public EndpointEventListener addingService(
				ServiceReference<Object> reference) {
			if(logger.isDebugEnabled()) {
				logger.debug("The tracker for framework {} discovered an RSA listener with service id {}",
						getUUID(framework), reference.getProperty(SERVICE_ID));
			}
			
			Object service = context.getService(reference);
			EndpointEventListener listener;
			Object filters = reference.getProperty(EndpointEventListener.ENDPOINT_LISTENER_SCOPE);
			if(service instanceof EndpointEventListener) {
				listener = (EndpointEventListener) service;
			} else if (service instanceof EndpointListener) {
				listener = new EndpointListenerAdapter((EndpointListener) service);
			} else {
				context.ungetService(reference);
				return null;
			}
			exporter.addingEEL(framework, listener, reference, filters);
			return listener;
		}
		
		@Override
		public void modifiedService(ServiceReference<Object> reference, EndpointEventListener eel) {
			if(logger.isDebugEnabled()) {
				logger.debug("The RSA listener with service id {} in framework {} is being removed",
						getUUID(framework), reference.getProperty(SERVICE_ID));
			}
			exporter.updatedEEL(framework, eel, reference,
					reference.getProperty(EndpointEventListener.ENDPOINT_LISTENER_SCOPE));
		}
		
		@Override
		public void removedService(ServiceReference<Object> reference, EndpointEventListener eel) {
			if(logger.isDebugEnabled()) {
				logger.debug("The RSA listener with service id {} in framework {} is being removed",
						getUUID(framework), reference.getProperty(SERVICE_ID));
			}
			exporter.removingEEL(framework, eel);
			super.removedService(reference, eel);
		}
	}
	
	private class FrameworkRemotableServiceTracker extends 
		ServiceTracker<Object, ServiceReference<Object>> {
	
		private final Framework framework;
		
		public FrameworkRemotableServiceTracker(Framework framework) {
			super(framework.getBundleContext(), getFilter("(service.exported.interfaces=*)"), null);
			this.framework = framework;
		}
		
		@Override
		public ServiceReference<Object> addingService(ServiceReference<Object> reference) {
			if(logger.isDebugEnabled()) {
				logger.debug("The tracker for framework {} discovered a remotable service with service id {}",
						getUUID(framework), reference.getProperty(SERVICE_ID));
			}
			exporter.addingService(framework, reference);
			return reference;
		}
		
		@Override
		public void modifiedService(ServiceReference<Object> reference, ServiceReference<Object> svc) {
			if(logger.isDebugEnabled()) {
				logger.debug("The remotable service with service id {} in framework {} has been modified",
						getUUID(framework), reference.getProperty(SERVICE_ID));
			}
			exporter.modifiedService(framework, reference);
		}
		
		@Override
		public void removedService(ServiceReference<Object> reference, ServiceReference<Object> svc) {
			if(logger.isDebugEnabled()) {
				logger.debug("The remotable service with service id {} in framework {} has been removed",
						getUUID(framework), reference.getProperty(SERVICE_ID));
			}
			exporter.removedService(framework, reference);
		}
	}
	
	public IsolationAwareRSATracker(BundleContext context) {
		Framework root = context.getBundle(0).adapt(Framework.class);

		this.importer = new ServiceImporter(root);
		this.exporter = new ServiceExporter(root);
		
		localRSATracker = new FrameworkRSACustomiser(root);
		localRSATracker.open();
		
		localELTracker = new FrameworkELTracker(root);
		localELTracker.open();

		localRemotableServices = new FrameworkRemotableServiceTracker(root);
		localRemotableServices.open();
		
		registerListener(root, "<<ROOT>>");

		serviceRegistration = context.registerService(ChildFrameworkListener.class, this, null);
	}

	private void registerListener(Framework f, String name) {
		listenerRegistrations.compute(f, (k,v) -> {
			if(v != null) {
				logger.warn("A listener has already been registered for the framework {}", name);
				return v;
			} else {
				return f.getBundleContext().registerService(
						new String[] {EndpointEventListener.class.getName(), EndpointListener.class.getName()}, 
						importer.getServiceFactory(f), getEndpointFilter(f));
			}
		});
	}
	
	private Dictionary<String, Object> getEndpointFilter(Framework f) {
		Dictionary<String, Object> table = new Hashtable<>();
		table.put(EndpointEventListener.ENDPOINT_LISTENER_SCOPE, 
				"(!(" + RemoteConstants.ENDPOINT_FRAMEWORK_UUID + "=" + getUUID(f) + "))");
		return table;
	}
	
	public void destroy() {
		localRSATracker.close();
		localELTracker.close();
		serviceRegistration.unregister();
		importer.destroy();
		exporter.destroy();
	}

	@Override
	public void childFrameworkEvent(ChildFrameworkEvent event) {
		switch(event.getType()) {
			case INITIALIZED :
				startTracking(event.getFramework(), event.getName());
				break;
			case MOVED :
				importer.registerScope(event.getFramework(), event.getName());
				exporter.registerScope(event.getFramework(), event.getName());
				break;
			case DESTROYING :
				stopTracking(event.getFramework(), event.getName());
				break;
			default:
				break;
		}
	}

	private void startTracking(Framework framework, String name) {
		importer.registerScope(framework, name);
		exporter.registerScope(framework, name);
		storeTracker(framework, name, childRSAs, new FrameworkRSACustomiser(framework));
		storeTracker(framework, name, childEndpointListeners, 
				new FrameworkELTracker(framework));
		storeTracker(framework, name, childRemotableServices, new FrameworkRemotableServiceTracker(framework));
		registerListener(framework, name);
	}

	private <T, V> void storeTracker(Framework framework, String name, 
			ConcurrentMap<Framework, ServiceTracker<T, V>> map, ServiceTracker<T, V> tracker) {
		if(map.putIfAbsent(framework, tracker) == null) {
			tracker.open();
		} else {
			logger.error("Received multiple initialisation events for the same framework {}", name);
		}
	}

	private void stopTracking(Framework framework, String name) {
		try {
			ofNullable(listenerRegistrations.remove(framework)).ifPresent(ServiceRegistration::unregister);
		} catch (IllegalStateException ise) {}
		closeTracker(childRSAs.remove(framework), name);
		closeTracker(childEndpointListeners.remove(framework), name);
		closeTracker(childRemotableServices.remove(framework), name);
		
		exporter.unregisterScope(framework, name);
		importer.unregisterScope(framework, name);
	}

	private void closeTracker(ServiceTracker<?, ?> tracker, String name) {
		if(tracker != null) {
			try {
				tracker.close();
			} catch (Exception e) {
				logger.error("There was an error destroying a tracker for framework {}", name);
			}
		} else {
			logger.error("Received destroy event for an unknown framework");
		}
	}
	
}
