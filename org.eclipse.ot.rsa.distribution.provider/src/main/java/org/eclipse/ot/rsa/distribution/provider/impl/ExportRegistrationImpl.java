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

import static org.eclipse.ot.rsa.distribution.provider.impl.RegistrationState.CLOSED;
import static org.eclipse.ot.rsa.distribution.provider.impl.RegistrationState.ERROR;
import static org.eclipse.ot.rsa.distribution.provider.impl.RegistrationState.OPEN;
import static org.eclipse.ot.rsa.distribution.provider.impl.RegistrationState.PRE_INIT;
import static org.osgi.framework.ServiceException.REMOTE;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExportRegistrationImpl implements ExportRegistration {

	private static final Logger LOG = LoggerFactory.getLogger(ExportRegistrationImpl.class);

    private final ServiceReference<?> _serviceReference;
    private final Framework _sourceFramework;
    private final ExportReference _exportReference;
    private final RemoteServiceAdminImpl _rsa;
    private final ServiceTracker<? super Object, ServiceReference<?>> _serviceTracker;
    private final UUID _id;

    private EndpointDescription _endpointDescription;
    private Throwable _exception;
    private RegistrationState _state = PRE_INIT;

	private Map<String, ?> _extraProperties;

    /**
     * Default constructor for a new service export.
     *
     * @param sref a reference to the service being exported
     * @param extraProperties the extra properties with which this service is being exported
     * @param endpoint the description of the exported endpoint
     * @param sourceFramework the framework from which this service is being exported
     * @param rsa the exporting {@link RemoteServiceAdmin}
     * @throws NullPointerException if either argument is <code>null</code>
     */
    @SuppressWarnings("unchecked")
	public ExportRegistrationImpl(ServiceReference<?> sref, Map<String, ?> extraProperties,
			EndpointDescription endpoint, Framework sourceFramework, RemoteServiceAdminImpl rsa) {

        _serviceReference = Objects.requireNonNull(sref, "The service reference for an export must not be null");
        _extraProperties = extraProperties;
        _sourceFramework = Objects.requireNonNull(sourceFramework, "The source framework of the service reference for an export must not be null");
        _endpointDescription = Objects.requireNonNull(endpoint, "The endpoint for an export must not be null");
        _id = UUID.fromString(_endpointDescription.getId());
        _exportReference = new SimpleExportReference();
        _rsa = rsa;
        _exception = null;

        _serviceTracker =  new ServiceTracker<Object, ServiceReference<?>>(_serviceReference.getBundle()
        		.getBundleContext(), (ServiceReference<Object>) _serviceReference, null) {

        	@Override
			public ServiceReference<?> addingService(ServiceReference<Object> sr) {
                return sr;
            }

        	@Override
			public void removedService(ServiceReference<Object> sr, ServiceReference<?> s) {
            	synchronized (ExportRegistrationImpl.this) {
            		if(_state != CLOSED) {
            			LOG.info("The exported service {} has been unregistered and so the export will be automatically closed.", sr);
            			close();
            		}
            	}
            }
        };
    }

    /**
     * Default constructor for a failed service export.
     *
     * @param sref a reference to the service being exported
     * @param rsa the exporting {@link RemoteServiceAdmin}
     * @param failure the error that occurred when exporting the service
     * @throws NullPointerException if either argument is <code>null</code>
     */
    public ExportRegistrationImpl(ServiceReference<?> sref, RemoteServiceAdminImpl rsa, Throwable failure) {

    	_serviceReference = Objects.requireNonNull(sref, "The service reference for an export must not be null");
    	_sourceFramework = null;
    	_endpointDescription = null;
    	_extraProperties = null;
    	_exportReference = new SimpleExportReference();
    	_rsa = rsa;
    	_exception = failure;
    	_state = ERROR;

    	_serviceTracker = null;
    	_id = null;
    }

	RegistrationState start() {
		RegistrationState postInitState;
		synchronized (this) {
			if(_state == PRE_INIT) {
				try {
					_serviceTracker.open();
					_state = OPEN;
				} catch (IllegalStateException ise) {
					_state = ERROR;
					_exception = ise;
				}
			}
			postInitState = _state;
		}

        if(postInitState == OPEN && _serviceTracker.getService() == null) {
        	LOG.info("The exported service {} has been unregistered and so the export will be automatically closed.", _serviceReference);
			close();
        }

        synchronized (this) {
        	return _state;
		}
	}

	RegistrationState getState() {
		synchronized (this) {
			return _state;
		}
	}

    public Framework getSourceFramework() {
		return _sourceFramework;
	}

	/**
     * Default implementation of {@link ExportRegistration#close()}, removing this export
     * from the associated {@link RemoteServiceAdminImpl}.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (_state == CLOSED) {
                return;
            }

            // we must remove ourselves from the RSA *before* closing, otherwise
            // RSAListeners will not have access to the ExportReference in received
            // events.
            _rsa.removeExportRegistration(this, _serviceReference);

            _state = CLOSED;
            if(_serviceTracker != null) _serviceTracker.close();
        }
    }

    @Override
    public ExportReference getExportReference() {
        synchronized (this) {
            if (_state == CLOSED) {
                return null;
            }

            if (_state == ERROR) {
                throw new IllegalStateException("The ExportRegistration associated with this ExportReference has failed", _exception);
            }

            return _exportReference;
        }
    }

    @Override
    public Throwable getException() {
        synchronized (this) {
        	if(_state == ERROR) {
    			return _exception == null ? new ServiceException("An unknown error occurred", REMOTE) : _exception;
	    	}
	    	return null;
        }
    }

    /**
     * Like {@link #getException()} except it continues to return the exception after
     * close has been called
     * @return
     */
    Throwable internalGetException() {
    	synchronized (this) {
        	if(_state == ERROR) {
        			return _exception == null ? new ServiceException("An unknown error occurred", REMOTE) : _exception;
        	}
        	return _exception;
        }
	}

    @Override
	public EndpointDescription update(Map<String, ?> properties) {
    	synchronized (this) {
    		if (_state == CLOSED) {
                return null;
            }

            if (_state == ERROR) {
                LOG.info("Clearing exception {} on Export {} because it is being updated", _exception, this);
                _exception = null;
                _state = OPEN;
            }
            if(properties != null) {
            	_extraProperties = properties;
            }

            try {
            	EndpointDescription ed = _rsa.updateExport(_sourceFramework, _serviceReference,
            			_extraProperties, _id, _endpointDescription);
            	if(ed != null) {
            		_endpointDescription = ed;
            	}
				return ed;
			} catch (Exception e) {
				LOG.error("Error updating service", e);
				_state = ERROR;
				_exception = e;
				return null;
			}
        }
	}

	@Override
    public String toString() {
        synchronized (this) {
            StringBuilder b = new StringBuilder(200);
            b.append("{");
            b.append("serviceReference: " + _serviceReference);
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

    private final class SimpleExportReference implements ExportReference {
    	@Override
		public EndpointDescription getExportedEndpoint() {
		    synchronized (ExportRegistrationImpl.this) {
		        return _state == CLOSED ? null : _endpointDescription;
		    }
		}
    	@Override
		public ServiceReference<?> getExportedService() {
		    synchronized (ExportRegistrationImpl.this) {
		        return _state == CLOSED ? null : _serviceReference;
		    }
		}
	}

	ServiceReference<?> getServiceReference() {
		return _serviceReference;
	}

	public UUID getId() {
		return _id;
	}

	EndpointDescription getEndpointDescription() {
		synchronized (this) {
			return _endpointDescription;
		}
	}

}
