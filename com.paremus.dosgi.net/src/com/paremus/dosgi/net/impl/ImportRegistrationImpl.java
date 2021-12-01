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
import static org.osgi.framework.Constants.SERVICE_ID;
import static org.osgi.framework.ServiceException.REMOTE;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.config.ImportedServiceConfig;
import com.paremus.dosgi.net.proxy.ClientServiceFactory;
import com.paremus.dosgi.net.proxy.MethodCallHandlerFactory;

import aQute.bnd.annotation.metatype.Configurable;

public class ImportRegistrationImpl implements ImportRegistration {
	
    private static final Logger LOG = LoggerFactory.getLogger(ImportRegistrationImpl.class);

    private final ServiceRegistration<?> _serviceRegistration;
    private final BundleContext _hostBundleContext;
    private final Framework _targetFramework;
    private final ImportReference _importReference;
    private final RemoteServiceAdminImpl _rsa;
    private final MethodCallHandlerFactory _handlerFactory;

    private EndpointDescription _endpointDescription;
    private Throwable _exception;
    private RegistrationState _state = PRE_INIT;
	private ImportedServiceConfig _config;

	private final Map<Integer, String> _methodMappings;

    /**
     * Default constructor for a new service export.
     * 
     * @param rsa the exporting {@link RemoteServiceAdmin}
     * @param sref a reference to the service being exported
     * @throws NullPointerException if either argument is <code>null</code>
     * @see RemoteServiceAdminInstanceCustomizer#createExportRegistration(ServiceReference)
     */
	public ImportRegistrationImpl(EndpointDescription endpoint, Framework targetFramework,
			BundleContext hostBundleContext, RemoteServiceAdminImpl rsa, int defaultServiceTimeout) {
    	
        _endpointDescription = Objects.requireNonNull(endpoint, "The endpoint for an export must not be null");
        _targetFramework = Objects.requireNonNull(targetFramework, "The target framework for a remote service import not be null");
        _hostBundleContext = Objects.requireNonNull(hostBundleContext, "The remote service host bundle must be active to import a remote service");
        _importReference = new SimpleImportReference();
        _rsa = Objects.requireNonNull(rsa, "The Remote Service Admin must not be null");
        
        _config = Configurable.createConfigurable(ImportedServiceConfig.class, 
				_endpointDescription.getProperties());
        
        _methodMappings = _config.com_paremus_dosgi_net_methods().stream()
        	.map(s -> s.split("="))
        	.collect(Collectors.toMap(s -> Integer.valueOf(s[0]), s -> s[1]));
        
        MethodCallHandlerFactory mchf;
        try {
        	mchf = _rsa.getHandlerFactoryFor(_endpointDescription, _config, _methodMappings);
        } catch (Exception e) {
        	_handlerFactory = null;
        	_serviceRegistration = null;
        	asyncFail(e);
        	return;
        }
        _exception = null;
        _handlerFactory = mchf;
        _state = OPEN;

        Dictionary<String, Object> serviceProps = new Hashtable<>(_endpointDescription.getProperties());
        serviceProps.remove(RemoteConstants.SERVICE_EXPORTED_INTERFACES);
        serviceProps.put(RemoteConstants.SERVICE_IMPORTED, Boolean.TRUE);
        
        int serviceTimeout = _config.com_paremus_dosgi_net_timeout() > 0 ? 
        		_config.com_paremus_dosgi_net_timeout() : defaultServiceTimeout;
        
        ServiceRegistration<?> reg;
        try { 
	        reg = _hostBundleContext.registerService(
	        		endpoint.getInterfaces().toArray(new String[0]), 
	        		new ClientServiceFactory(this, endpoint, _handlerFactory, serviceTimeout), 
	        		serviceProps);
		} catch (Exception e) {
			_serviceRegistration = null;
        	asyncFail(e);
        	return;
		}
        
		// We must sync and check here as we've escaped into the wild by registering the service
        synchronized (this) {
        	if(_state == OPEN) {
        		_serviceRegistration = reg;
        	} else {
        		_serviceRegistration = null;
        		return;
        	}
		}
        try {
        	_handlerFactory.addImportRegistration(this);
        } catch (Exception e) {
            asyncFail(e);
           	return;
        }
        
        try {
        	UnregistrationListener listener = new UnregistrationListener();
			targetFramework.getBundleContext().addServiceListener(listener,
        			"(" + SERVICE_ID + "=" + getServiceReference().getProperty(SERVICE_ID) +")");
			
			if(getServiceReference() == null) {
				targetFramework.getBundleContext().removeServiceListener(listener);
			}
        } catch (Exception e) {
        	asyncFail(e);
        	return;
        }
    }
	
	public ImportRegistrationImpl(EndpointDescription endpoint, Framework targetFramework,
			RemoteServiceAdminImpl rsa, Exception failure) {
		
		_endpointDescription = Objects.requireNonNull(endpoint, "The endpoint for an export must not be null");
        _targetFramework = Objects.requireNonNull(targetFramework, "The target framework for a remote service import not be null");
        _rsa = Objects.requireNonNull(rsa, "The Remote Service Admin must not be null");
        
        _serviceRegistration = null;
        _importReference = null;
        _handlerFactory = null;
        _hostBundleContext = null;
        _config = null;
        _methodMappings = null;
        
        _state = ERROR;
        _exception = failure;
	}

	private class UnregistrationListener implements ServiceListener {
		@Override
		public void serviceChanged(ServiceEvent event) {
			if(event.getType() == ServiceEvent.UNREGISTERING) {
				BundleContext context = _targetFramework.getBundleContext();
				if(context != null) {
					context.removeServiceListener(this);
				}
				synchronized (ImportRegistrationImpl.this) {
					if (_state == CLOSED || _state == ERROR) {
		                return;
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
     * Default implementation of {@link ExportRegistration#close()}, removing this export
     * from the associated {@link RemoteServiceAdminInstance}.
     * <p>
     * Implementors who added additional state for exported service (e.g. a transport
     * channel) will probably want to override this method and, after calling this
     * {@link #close()}, release any custom state. A good indicator for this is the usage
     * count of the export as indicated by {@link #getInstanceCount()}.
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

            // we must remove ourselves from the RSA *before* closing, otherwise
            // RSAListeners will not have access to the ImportReference in received
            // events.
            _rsa.removeImportRegistration(this, _endpointDescription.getId());

            _state = CLOSED;
            try {
            	if(_serviceRegistration != null) _serviceRegistration.unregister();
            } catch (IllegalStateException ise) {
            	//This can happen if the target is shutting down
            }
            _handlerFactory.close(this);
        }
    }

	@Override
    public ImportReference getImportReference() {
        synchronized (this) {
            if (_state == CLOSED) {
                return null;
            }

            if (_state == ERROR) {
                throw new IllegalStateException("The ImportRegistration associated with this ImportReference has failed", _exception);
            }

            return _importReference;
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

	public boolean update(EndpointDescription endpoint) {
    	synchronized (this) {
            if (_state == CLOSED) {
                throw new IllegalStateException("This ImportRegistration is closed");
            }

            if (_state == ERROR) {
                throw new IllegalStateException("The ImportRegistration associated with this ImportReference has failed", _exception);
            }
            
            if(!_endpointDescription.equals(endpoint)) {
            	throw new IllegalArgumentException(_endpointDescription.getId());
            }
            ImportedServiceConfig tmpConfig = Configurable.createConfigurable(ImportedServiceConfig.class, 
    				_endpointDescription.getProperties());
            if(!_config.com_paremus_dosgi_net_methods().equals(tmpConfig.com_paremus_dosgi_net_methods())) {
            	throw new IllegalArgumentException("The methods supported by the remote endpoint have changed");
            }
            
            _endpointDescription = endpoint;
            _config = tmpConfig;
            
            try {
            	//TODO check the handler is still valid
            	
            	
            	Dictionary<String, Object> serviceProps = new Hashtable<String, Object>(endpoint.getProperties());
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
            	} catch(IllegalStateException ise) {}
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

	public ImportedServiceConfig getConfig() {
		return _config;
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
            LOG.debug("The import for endpoint {} in framework {} is being failed", new Object[] {
            		 _endpointDescription.getId(), 
            		 _hostBundleContext.getProperty("org.osgi.framework.uuid)"), reason});

            try {
            	if(_serviceRegistration != null) _serviceRegistration.unregister();
            } catch (IllegalStateException ise) {
            	//This can happen if the target is shutting down
            }
            _exception = reason;
            
            _handlerFactory.close(this);
            
            _rsa.notifyImportError(this, _endpointDescription.getId());
		}
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


}
