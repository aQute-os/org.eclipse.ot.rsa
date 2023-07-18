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
package org.eclipse.ot.rsa.distribution.provider.impl;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdminEvent;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdminListener;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instances of this class are used by every {@link RemoteServiceAdminImpl} to
 * send various {@link RemoteServiceAdminEvent} types to all registered
 * {@link RemoteServiceAdminListener} and any active {@link EventAdmin}
 * services.
 */
public class RemoteServiceAdminEventPublisher {

	public static final String	RSA_TOPIC_NAME	= "org/osgi/service/remoteserviceadmin";
	public static final String	EXCEPTION_CAUSE	= "cause";

	public enum RemoteServiceAdminEventType {

		UNKNOWN_EVENT,
		IMPORT_REGISTRATION,
		EXPORT_REGISTRATION,
		EXPORT_UNREGISTRATION,
		IMPORT_UNREGISTRATION,
		IMPORT_ERROR,
		EXPORT_ERROR,
		EXPORT_WARNING,
		IMPORT_WARNING,
		IMPORT_UPDATE,
		EXPORT_UPDATE;

		private final String _topic;

		public static RemoteServiceAdminEventType valueOf(int type) {
			return (type < 1 || type >= values().length) ? UNKNOWN_EVENT : values()[type];
		}

		RemoteServiceAdminEventType() {
			_topic = RSA_TOPIC_NAME + '/' + name();
		}

		public String topicName() {
			return _topic;
		}
	}

	private static final Logger																LOG	= LoggerFactory
		.getLogger(RemoteServiceAdminEventPublisher.class);

	private final BundleContext																_bundleContext;
	private final ServiceTracker<RemoteServiceAdminListener, RemoteServiceAdminListener>	_rsaListenerTracker;
	private final ServiceTracker<EventAdmin, EventAdmin>									_eventAdminTracker;

	static class IST<T> extends ServiceTracker<T, T> {

		public IST(BundleContext context, Class<T> clazz) {
			super(context, clazz, null);
		}

		@Override
		public T addingService(ServiceReference<T> reference) {
			return super.addingService(reference);
		}

		@Override
		public void removedService(ServiceReference<T> reference, T service) {
			super.removedService(reference, service);
		}
	}

	public RemoteServiceAdminEventPublisher(BundleContext bc) {
		_bundleContext = bc;
		_rsaListenerTracker = new IST<>(bc, RemoteServiceAdminListener.class);
		_eventAdminTracker = new ServiceTracker<>(bc, EventAdmin.class.getName(), null);
	}

	protected void start() {
		CompletableFuture.runAsync(() -> {
			_rsaListenerTracker.open();
			_eventAdminTracker.open();
		});
	}

	protected void destroy() {
		try {
			_rsaListenerTracker.close();
			_eventAdminTracker.close();
		} catch (Exception e) {
			// ignore
		} finally {
			LOG.debug("stopped: {}", this);
		}
	}

	public void notifyExport(ServiceReference<?> service, EndpointDescription endpoint) {
		ExportReference ref = new AnonymousExportReference(service, endpoint);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.EXPORT_REGISTRATION,
			_bundleContext.getBundle(), ref, null);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyImport(ServiceReference<?> service, EndpointDescription endpoint) {
		ImportReference ref = new AnonymousImportReference(service, endpoint);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.IMPORT_REGISTRATION,
			_bundleContext.getBundle(), ref, null);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyExportWarning(ServiceReference<?> service, EndpointDescription endpoint, Throwable t) {
		ExportReference ref = new AnonymousExportReference(service, endpoint);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.EXPORT_WARNING,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyImportWarning(ServiceReference<?> service, EndpointDescription endpoint, Throwable t) {
		ImportReference ref = new AnonymousImportReference(service, endpoint);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.IMPORT_WARNING,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyExportError(ServiceReference<?> service, Throwable t) {
		ExportReference ref = new AnonymousExportReference(service, null);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.EXPORT_ERROR,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyImportError(EndpointDescription endpoint, Throwable t) {
		ImportReference ref = new AnonymousImportReference(null, endpoint);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.IMPORT_ERROR,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyExportRemoved(ServiceReference<?> service, EndpointDescription endpoint, Throwable t) {
		ExportReference ref = new AnonymousExportReference(service, null);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.EXPORT_UNREGISTRATION,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyImportRemoved(ServiceReference<?> service, EndpointDescription endpoint, Throwable t) {
		ImportReference ref = new AnonymousImportReference(service, null);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.IMPORT_UNREGISTRATION,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyExportUpdate(ServiceReference<?> service, EndpointDescription endpoint, Throwable t) {
		ExportReference ref = new AnonymousExportReference(service, endpoint);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.EXPORT_UPDATE,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	public void notifyImportUpdate(ServiceReference<?> service, EndpointDescription endpoint, Throwable t) {
		ImportReference ref = new AnonymousImportReference(service, endpoint);
		RemoteServiceAdminEvent rsae = new RemoteServiceAdminEvent(RemoteServiceAdminEvent.IMPORT_UPDATE,
			_bundleContext.getBundle(), ref, t);

		notifyListeners(rsae);
		notifyEventAdmin(rsae);
	}

	private void notifyListeners(RemoteServiceAdminEvent rsae) {
		for (RemoteServiceAdminListener rsaListener : _rsaListenerTracker
			.getServices(new RemoteServiceAdminListener[0])) {
			rsaListener.remoteAdminEvent(rsae);
		}
	}

	private void notifyEventAdmin(RemoteServiceAdminEvent rsaEvent) {
		EventAdmin[] eventAdmins = _eventAdminTracker.getServices(new EventAdmin[0]);
		// no need to do anything
		if (eventAdmins.length == 0) {
			return;
		}

		Map<String, Object> props = new HashMap<>(16);

		Throwable t = rsaEvent.getException();
		if (t != null) {
			// see RSA-98 spec bug: "cause" vs. EventConstants
			setIfNotNull(props, EXCEPTION_CAUSE, t);
			setIfNotNull(props, EventConstants.EXCEPTION, t);
			setIfNotNull(props, EventConstants.EXCEPTION_CLASS, t.getClass());
			setIfNotNull(props, EventConstants.EXCEPTION_MESSAGE, t.getMessage());
		}

		EndpointDescription endpoint = null;

		// figure out whether this is an export- or import-related event
		if (rsaEvent.getImportReference() != null) {
			endpoint = rsaEvent.getImportReference()
				.getImportedEndpoint();
		} else if (rsaEvent.getExportReference() != null) {
			endpoint = rsaEvent.getExportReference()
				.getExportedEndpoint();
		}

		// in case of errors the endpoint description may be null
		if (endpoint != null) {
			String[] objClass = endpoint.getInterfaces()
				.toArray(new String[0]);
			setIfNotNull(props, Constants.OBJECTCLASS, objClass);
			setIfNotNull(props, RemoteConstants.ENDPOINT_SERVICE_ID, endpoint.getServiceId());
			setIfNotNull(props, RemoteConstants.ENDPOINT_FRAMEWORK_UUID, endpoint.getFrameworkUUID());
			setIfNotNull(props, RemoteConstants.ENDPOINT_ID, endpoint.getId());
			setIfNotNull(props, RemoteConstants.SERVICE_IMPORTED_CONFIGS, endpoint.getConfigurationTypes());
		}

		props.put(EventConstants.TIMESTAMP, Long.valueOf(System.currentTimeMillis()));
		props.put(EventConstants.EVENT, rsaEvent);
		Bundle _rsaBundle = _bundleContext.getBundle();
		props.put(EventConstants.BUNDLE, _bundleContext.getBundle());
		props.put(EventConstants.BUNDLE_ID, _rsaBundle.getBundleId());
		// The RSA spec uses the wrong case for the symbolic name
		props.put(EventConstants.BUNDLE_SYMBOLICNAME.toLowerCase(), _rsaBundle.getSymbolicName());
		props.put(EventConstants.BUNDLE_SYMBOLICNAME, _rsaBundle.getSymbolicName());
		props.put(EventConstants.BUNDLE_VERSION, _rsaBundle.getVersion());
		props.put(EventConstants.BUNDLE_SIGNER, getSigners(_rsaBundle));

		String topic = RemoteServiceAdminEventType.valueOf(rsaEvent.getType())
			.topicName();
		Event event = new Event(topic, props);

		AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
			for (EventAdmin ea : eventAdmins) {
				ea.postEvent(event);
			}
			return null;
		});
	}

	private void setIfNotNull(Map<String, Object> props, String key, Object o) {
		if (o != null) {
			props.put(key, o);
		}
	}

	private String[] getSigners(Bundle b) {
		Objects.requireNonNull(b, "Bundle");

		@SuppressWarnings("rawtypes")
		Map certificates = b.getSignerCertificates(Bundle.SIGNERS_ALL);
		if (certificates == null || certificates.isEmpty()) {
			return new String[] {};
		}

		@SuppressWarnings("unchecked")
		Set<Map.Entry<X509Certificate, List<?>>> certs = certificates.entrySet();
		String[] signers = new String[certs.size()];
		int i = 0;

		for (Map.Entry<X509Certificate, List<?>> cert : certs) {
			signers[i++] = cert.getKey()
				.getIssuerX500Principal()
				.getName();
		}

		return signers;
	}

	@Override
	public String toString() {
		return "RSA listener notifier for the Paremus RSA provider";
	}

	private static class AnonymousExportReference implements ExportReference {

		private final ServiceReference<?>	_service;
		private final EndpointDescription	_endpoint;

		private AnonymousExportReference(ServiceReference<?> service, EndpointDescription endpoint) {
			_service = service;
			_endpoint = endpoint;
		}

		@Override
		public ServiceReference<?> getExportedService() {
			return _service;
		}

		@Override
		public EndpointDescription getExportedEndpoint() {
			return _endpoint;
		}

	}

	private static class AnonymousImportReference implements ImportReference {

		private final ServiceReference<?>	_service;
		private final EndpointDescription	_endpoint;

		private AnonymousImportReference(ServiceReference<?> service, EndpointDescription endpoint) {
			_service = service;
			_endpoint = endpoint;
		}

		@Override
		public ServiceReference<?> getImportedService() {
			return _service;
		}

		@Override
		public EndpointDescription getImportedEndpoint() {
			return _endpoint;
		}

	}
}
