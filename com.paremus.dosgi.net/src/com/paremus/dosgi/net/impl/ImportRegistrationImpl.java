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
package com.paremus.dosgi.net.impl;

import static com.paremus.dosgi.net.impl.RegistrationState.CLOSED;
import static com.paremus.dosgi.net.impl.RegistrationState.ERROR;
import static com.paremus.dosgi.net.impl.RegistrationState.OPEN;
import static com.paremus.dosgi.net.impl.RegistrationState.PRE_INIT;
import static java.util.stream.Collectors.toList;
import static org.osgi.framework.Constants.SERVICE_ID;
import static org.osgi.framework.ServiceException.REMOTE;

import java.net.URI;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.converter.Converters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.client.ClientConnectionManager;
import com.paremus.dosgi.net.config.ImportedServiceConfig;
import com.paremus.dosgi.net.proxy.ClientServiceFactory;

import io.netty.channel.Channel;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;

public class ImportRegistrationImpl implements ImportRegistration {

	private static final Logger				LOG		= LoggerFactory.getLogger(ImportRegistrationImpl.class);

	private final ServiceRegistration<?>	_serviceRegistration;
	private final BundleContext				_hostBundleContext;
	private final Framework					_targetFramework;
	private final ImportReference			_importReference;
	private final RemoteServiceAdminImpl	_rsa;
	private final ClientConnectionManager	_clientConnectionManager;
	private final Channel					_channel;
	private final EventExecutorGroup		_executor;
	private final Timer						_timer;
	private final long						_defaultServiceTimeout;
	private final AtomicLong				_serviceTimeout;
	private final String					_frameworkUUID;

	private EndpointDescription				_endpointDescription;
	private Throwable						_exception;
	private RegistrationState				_state	= PRE_INIT;
	private ImportedServiceConfig			_config;

	private final Map<Integer, String>		_methodMappings;

	/**
	 * Default constructor for a new service import.
	 *
	 * @param endpoint
	 *                                  the description for the endpoint being
	 *                                  imported
	 * @param targetFramework
	 *                                  the framework into which this service is
	 *                                  being imported
	 * @param hostBundleContext
	 *                                  the bundle context for the proxy bundle
	 * @param rsa
	 *                                  the exporting {@link RemoteServiceAdmin}
	 * @param ccm
	 *                                  The connection manager for making client
	 *                                  invocations
	 * @param defaultServiceTimeout
	 *                                  the time (in millis) after which client
	 *                                  calls should timeout
	 * @param executor
	 *                                  the worker to use when making async
	 *                                  network calls
	 * @param timer
	 *                                  The timer to use for triggering
	 *                                  scheduled future work
	 * @throws NullPointerException
	 *                                  if any required argument is
	 *                                  <code>null</code>
	 */
	public ImportRegistrationImpl(EndpointDescription endpoint, Framework targetFramework,
			BundleContext hostBundleContext, RemoteServiceAdminImpl rsa, ClientConnectionManager ccm,
			int defaultServiceTimeout, EventExecutorGroup executor, Timer timer) {

		_endpointDescription = Objects.requireNonNull(endpoint, "The endpoint for an export must not be null");
		_targetFramework = Objects.requireNonNull(targetFramework,
				"The target framework for a remote service import not be null");
		_hostBundleContext = Objects.requireNonNull(hostBundleContext,
				"The remote service host bundle must be active to import a remote service");

		_frameworkUUID = _hostBundleContext.getProperty("org.osgi.framework.uuid");
		_importReference = new SimpleImportReference();
		_rsa = Objects.requireNonNull(rsa, "The Remote Service Admin must not be null");
		_clientConnectionManager = Objects.requireNonNull(ccm, "The Remote Service Admin must not be null");
		_executor = Objects.requireNonNull(executor, "The executor must not be null");
		_timer = Objects.requireNonNull(timer, "The timer must not be null");
		_defaultServiceTimeout = defaultServiceTimeout;

		try {
			_config = Converters.standardConverter().convert(
					_endpointDescription.getProperties()).to(ImportedServiceConfig.class);
		} catch (Exception ex) {
			LOG.error("A serious failure occurred trying to import endpoint {}", endpoint, ex);
			throw new IllegalArgumentException(ex);
		}

		long serviceTimeout = getServiceTimeout();
		_serviceTimeout = new AtomicLong(serviceTimeout);

		_methodMappings = Arrays.stream(_config.com_paremus_dosgi_net_methods())
				.map(s -> s.split("="))
				.collect(Collectors.toMap(s -> Integer.valueOf(s[0]), s -> s[1]));

		List<URI> uris = Arrays.stream(_config.com_paremus_dosgi_net())
				.map(URI::create)
				.collect(toList());

		Channel channel;
		try {
			channel = uris.stream()
					.map(uri -> _clientConnectionManager.getChannelFor(uri, _endpointDescription))
					.filter(mchf -> mchf != null)
					.findFirst().orElseThrow(() -> new IllegalArgumentException(
							"Unable to connect to any of the endpoint locations " + uris));
		} catch (Exception e) {
			_channel = null;
			_serviceRegistration = null;
			asyncFail(e);
			return;
		}
		_exception = null;
		_channel = channel;
		_state = OPEN;

		Dictionary<String, Object> serviceProps = new Hashtable<>(_endpointDescription.getProperties());
		serviceProps.remove(RemoteConstants.SERVICE_EXPORTED_INTERFACES);
		serviceProps.put(RemoteConstants.SERVICE_IMPORTED, Boolean.TRUE);

		ServiceRegistration<?> reg;
		try {
			reg = _hostBundleContext.registerService(
					endpoint.getInterfaces().toArray(new String[0]),
					new ClientServiceFactory(this, endpoint, _channel,
							_config.com_paremus_dosgi_net_serialization().getFactory(),
							_serviceTimeout, _executor, _timer),
					serviceProps);
		} catch (Exception e) {
			_serviceRegistration = null;
			asyncFail(e);
			return;
		}

		// We must sync and check here as we've escaped into the wild by
		// registering the service
		synchronized (this) {
			if (_state == OPEN) {
				_serviceRegistration = reg;
			} else {
				_serviceRegistration = null;
				return;
			}
		}
		try {
			_clientConnectionManager.addImportRegistration(this);
		} catch (Exception e) {
			asyncFail(e);
			return;
		}

		try {
			UnregistrationListener listener = new UnregistrationListener();
			targetFramework.getBundleContext().addServiceListener(listener,
					"(" + SERVICE_ID + "=" + getServiceReference().getProperty(SERVICE_ID) + ")");

			if (getServiceReference() == null) {
				targetFramework.getBundleContext().removeServiceListener(listener);
			}
		} catch (Exception e) {
			asyncFail(e);
			return;
		}
	}

	private long getServiceTimeout() {
		long serviceTimeout = _config.com_paremus_dosgi_net_timeout();

		if (serviceTimeout < 0) {
			serviceTimeout = _config.osgi_basic_timeout();
		}

		if (serviceTimeout < 0) {
			serviceTimeout = _defaultServiceTimeout;
		}
		return serviceTimeout;
	}

	/**
	 * Create a failed endpoint
	 *
	 * @param endpoint
	 *                            The Endpoint Description
	 * @param targetFramework
	 *                            The framework into which the service is being
	 *                            imported
	 * @param rsa
	 *                            The Remote Service Admin
	 * @param failure
	 *                            The failure that occurred while importing
	 */
	public ImportRegistrationImpl(EndpointDescription endpoint, Framework targetFramework,
			RemoteServiceAdminImpl rsa, Exception failure) {

		_endpointDescription = Objects.requireNonNull(endpoint, "The endpoint for an export must not be null");
		_targetFramework = Objects.requireNonNull(targetFramework,
				"The target framework for a remote service import not be null");
		_rsa = Objects.requireNonNull(rsa, "The Remote Service Admin must not be null");

		_serviceRegistration = null;
		_importReference = null;
		_clientConnectionManager = null;
		_channel = null;
		_executor = null;
		_timer = null;
		_hostBundleContext = null;
		_frameworkUUID = "<failed endpoint>";
		_config = null;
		_methodMappings = null;
		_defaultServiceTimeout = -1;
		_serviceTimeout = null;

		_state = ERROR;
		_exception = failure;
	}

	private class UnregistrationListener implements ServiceListener {
		@Override
		public void serviceChanged(ServiceEvent event) {
			if (event.getType() == ServiceEvent.UNREGISTERING) {
				BundleContext context = _targetFramework.getBundleContext();
				if (context != null) {
					context.removeServiceListener(this);
					synchronized (ImportRegistrationImpl.this) {

						if (_state == CLOSED || _state == ERROR || context.getBundle().getState() != Bundle.ACTIVE) {
							return;
						}
					}
				}
				LOG.warn("The imported remote service {} was unregistered by a third party",
						_endpointDescription.getId());
				asyncFail(new IllegalStateException("The imported remote service " +
						_endpointDescription.getId() + " was unregistered by a third party"));
			}
		}
	}

	RegistrationState getState() {
		synchronized (this) {
			return _state;
		}
	}

	Framework getTargetFramework() {
		synchronized (this) {
			return _targetFramework;
		}
	}

	/**
	 * Default implementation of {@link ExportRegistration#close()}, removing
	 * this export from the associated {@link RemoteServiceAdminImpl}.
	 */
	@Override
	public void close() {
		synchronized (this) {
			if (_state == CLOSED) {
				return;
			} else if (_state == ERROR) {
				_state = CLOSED;
				return;
			}

			_state = CLOSED;
		}
		// We must remove before unregistering so that the service reference (if
		// valid) is still
		// available to send in the RemoteServiceAdminEvent
		_rsa.removeImportRegistration(this, _endpointDescription.getId());

		try {
			if (_serviceRegistration != null)
				_serviceRegistration.unregister();
		} catch (IllegalStateException ise) {
			// This can happen if the target is shutting down
		}
		_clientConnectionManager.notifyClosing(this);
	}

	@Override
	public ImportReference getImportReference() {
		synchronized (this) {
			if (_state == CLOSED) {
				return null;
			}

			if (_state == ERROR) {
				throw new IllegalStateException(
						"The ImportRegistration associated with this ImportReference has failed", _exception);
			}

			return _importReference;
		}
	}

	@Override
	public Throwable getException() {
		synchronized (this) {
			if (_state == ERROR) {
				return _exception == null ? new ServiceException("An unknown error occurred", REMOTE) : _exception;
			}
			return null;
		}
	}

	/**
	 * Like {@link #getException()} except it continues to return the exception
	 * after close has been called
	 *
	 * @return
	 */
	Throwable internalGetException() {
		synchronized (this) {
			if (_state == ERROR) {
				return _exception == null ? new ServiceException("An unknown error occurred", REMOTE) : _exception;
			}
			return _exception;
		}
	}

	@Override
	public boolean update(EndpointDescription endpoint) {
		synchronized (this) {
			if (_state == CLOSED) {
				throw new IllegalStateException("This ImportRegistration is closed");
			}

			if (_state == ERROR) {
				throw new IllegalStateException(
						"The ImportRegistration associated with this ImportReference has failed", _exception);
			}

			if (!_endpointDescription.equals(endpoint)) {
				throw new IllegalArgumentException(_endpointDescription.getId());
			}
			ImportedServiceConfig tmpConfig;
			try {
				tmpConfig = Converters.standardConverter().convert(
						endpoint.getProperties()).to(ImportedServiceConfig.class);
			} catch (Exception e) {
				throw new IllegalArgumentException("The endpoint could not be processed", e);
			}
			if (!Arrays.deepEquals(_config.com_paremus_dosgi_net_methods(),
					tmpConfig.com_paremus_dosgi_net_methods())) {
				throw new IllegalArgumentException("The methods supported by the remote endpoint have changed");
			}

			_endpointDescription = endpoint;
			_config = tmpConfig;
			_serviceTimeout.set(getServiceTimeout());

			try {
				// TODO check the handler is still valid

				Dictionary<String, Object> serviceProps = new Hashtable<>(endpoint.getProperties());
				serviceProps.remove(RemoteConstants.SERVICE_EXPORTED_INTERFACES);
				serviceProps.put(RemoteConstants.SERVICE_IMPORTED, Boolean.TRUE);
				_serviceRegistration.setProperties(serviceProps);

				_rsa.notifyImportUpdate(_serviceRegistration.getReference(), _endpointDescription, null);
			} catch (Exception e) {
				LOG.error("Update failed for endpoint " + endpoint.getId(), e);
				_state = ERROR;
				_exception = e;
				_rsa.notifyImportUpdate(_serviceRegistration.getReference(), _endpointDescription, _exception);
				try {
					_serviceRegistration.unregister();
				} catch (IllegalStateException ise) {
				}
				return false;
			}
			return true;
		}
	}

	@Override
	public String toString() {
		synchronized (this) {
			StringBuilder b = new StringBuilder(200);
			b.append("{");
			b.append("serviceRegistration: " + _serviceRegistration);
			b.append(", ");
			b.append("endpointDescription: " + _endpointDescription);
			b.append(", ");
			b.append("exception: " + _exception);
			b.append(", ");
			b.append("state: " + _state);
			b.append("}");
			return b.toString();
		}
	}

	public UUID getId() {
		return UUID.fromString(_endpointDescription.getId());
	}

	public Map<Integer, String> getMethodMappings() {
		return _methodMappings;
	}

	public void asyncFail(Throwable reason) {
		synchronized (this) {
			if (_state == CLOSED || _state == ERROR) {
				return;
			}
			_state = ERROR;
			LOG.debug("The import for endpoint {} in framework {} is being failed",
					_endpointDescription.getId(), _frameworkUUID, reason);

			try {
				if (_serviceRegistration != null)
					_serviceRegistration.unregister();
			} catch (IllegalStateException ise) {
				// This can happen if the target is shutting down
			}
			_exception = reason;

		}
		_clientConnectionManager.notifyClosing(this);

		_rsa.notifyImportError(this, _endpointDescription.getId());
	}

	EndpointDescription getEndpointDescription() {
		synchronized (this) {
			return _endpointDescription;
		}
	}

	ServiceReference<?> getServiceReference() {
		try {
			return _serviceRegistration == null ? null : _serviceRegistration.getReference();
		} catch (IllegalStateException ise) {
			return null;
		}
	}

	private final class SimpleImportReference implements ImportReference {
		@Override
		public EndpointDescription getImportedEndpoint() {
			synchronized (ImportRegistrationImpl.this) {
				return _state == CLOSED ? null : _endpointDescription;
			}
		}

		@Override
		public ServiceReference<?> getImportedService() {
			synchronized (ImportRegistrationImpl.this) {
				try {
					return _state == CLOSED || _state == ERROR ? null : _serviceRegistration.getReference();
				} catch (IllegalStateException ise) {
					LOG.warn("The service registration is no longer registered. Closing the import");
					close();
					return null;
				}
			}
		}
	}

	public Channel getChannel() {
		return _channel;
	}
}
