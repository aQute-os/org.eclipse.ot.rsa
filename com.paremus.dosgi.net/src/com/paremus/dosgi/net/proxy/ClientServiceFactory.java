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
package com.paremus.dosgi.net.proxy;

import static java.util.stream.Collectors.toList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.async.delegate.AsyncDelegate;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.impl.ImportRegistrationImpl;

/**
 * ClientServiceFactory acts as access point for imported remote services. A
 * {@link RemoteServiceAdmin} registers instances of this class in the OSGi framework with
 * the same interface(s) as the remote service. Any service lookup (e.g. via
 * {@link BundleContext#getService(org.osgi.framework.ServiceReference)}) will return a
 * per-client proxy to the remote service, created by the associated
 * {@link RemoteServiceAdminInstanceCustomizer}.
 * <p>
 * A note on class resolution. This class is a {@link ServiceFactory} since it needs
 * access to a calling bundle for correct class loading according to each client's class
 * space. Any service interfaces declared by the remote service endpoint are attempted to
 * be resolved via the calling code's {@link BundleContext}; interfaces that cannot be
 * resolved are <b>skipped</b>. This allows access to services which advertise multiple
 * interfaces when a client only needs (and imports) a subset of these interfaces.
 * 
 * @see {@link ServiceFactory}, {@link RemoteServiceAdminInstanceCustomizer}
 */
public class ClientServiceFactory implements ServiceFactory<Object> {

    private static final String ASYNC_DELEGATE_PACKAGE = "org.osgi.service.async.delegate";

	private static final String PROMISE_PACKAGE = "org.osgi.util.promise";

	private static final String OSGI_WIRING_PACKAGE = "osgi.wiring.package";

	private static final Logger LOG = LoggerFactory.getLogger(ClientServiceFactory.class.getName());

    private final EndpointDescription _endpointDescription;
    
    private final ImportRegistrationImpl _importRegistration;

    private final MethodCallHandlerFactory _handlerFactory;

	private int _serviceCallTimeout;

    /**
     * Default constructor, used by
     * {@link RemoteServiceAdminInstance#importService(EndpointDescription)}.
     */
    public ClientServiceFactory(ImportRegistrationImpl importRegistration, EndpointDescription endpoint,
    		MethodCallHandlerFactory handlerFactory, int serviceCallTimeout) {
        _endpointDescription = endpoint;
        _importRegistration = importRegistration;
        _handlerFactory = handlerFactory;
        _serviceCallTimeout = serviceCallTimeout;
    }

    public Object getService(Bundle requestingBundle, ServiceRegistration<Object> serviceRegistration) {
        LOG.debug("getService: creating proxy with interfaces: {} for bundle {}",
            _endpointDescription.getInterfaces(), requestingBundle.getSymbolicName());

        PrivilegedExceptionAction<Object> createProxyAction = () -> {
                // turn interface names into resolved classes
                List<String> classNames = _endpointDescription.getInterfaces();
                List<Class<?>> interfaces = _endpointDescription.getInterfaces().stream()
                		.filter(i -> i != null)
                		.map(i -> resolveClass(requestingBundle, i))
                		.filter(i -> i != null)
                		.collect(toList());

                // this should never happen: at least one objectClass must be loadable by the
                // client, otherwise the service would not be usable at all except by reflection
                if (interfaces.isEmpty()) {
                    throw new ClassNotFoundException("any of: " + classNames);
                }
                
                Class<?> promise = locatePromise(requestingBundle);
                Class<?> asyncDelegate = locateAsyncDelegate(requestingBundle, promise);
                
                if(asyncDelegate != null) {
                	interfaces.add(asyncDelegate);
                	if (promise == null) {
                		try {
                			promise = asyncDelegate.getMethod("async", Method.class, Object[].class)
                					.getReturnType();
                		} catch (Exception e) {
                			LOG.error("There was a problem determining the promise type from the async type");
                		}
                	}
                }

                Class<?> proxyClass = Proxy.getProxyClass(getClassLoader(requestingBundle, 
                		asyncDelegate, promise, interfaces), interfaces.toArray(new Class[0]));
                
                ServiceInvocationHandler proxyHandler = new ServiceInvocationHandler(
                		_importRegistration, _endpointDescription, requestingBundle, proxyClass,
                		interfaces, promise, asyncDelegate != null, 
                		_handlerFactory.create(requestingBundle), _serviceCallTimeout);
                
                return proxyClass.getConstructor(InvocationHandler.class).newInstance(proxyHandler);
            };

        try {
            Object proxy = AccessController.doPrivileged(createProxyAction);
            if (proxy != null) {
                return proxy;
            }
            else {
                throw new ServiceException("Received null proxy for endpoint: "
                                           + _endpointDescription.toString(), ServiceException.FACTORY_ERROR);
            }
        }
        catch (PrivilegedActionException pae) {
            throw new ServiceException("Caught exception while creating proxy for endpoint: "
                                       + _endpointDescription.toString(), ServiceException.FACTORY_EXCEPTION,
                pae.getCause());
        }
    }
    
    private Class<?> resolveClass(Bundle b, String name) {
    	try {
           return b.loadClass(name);
        }
        catch (Exception any) {
            // skip this class
            return null;
        }
    }

    private Class<?> locateAsyncDelegate(Bundle requestingBundle, Class<?> promiseClass) {
		Class<?> asyncDelegate = resolveClass(requestingBundle, AsyncDelegate.class.getName());
		Version minAcceptable = new Version(1,0,0);
		Version minUnacceptable = new Version(2,0,0);
		if(asyncDelegate != null) {
			asyncDelegate = checkAcceptableVersion(requestingBundle, asyncDelegate, 
					ASYNC_DELEGATE_PACKAGE, minAcceptable, minUnacceptable);
			
			//Check class space consistency
			if(promiseClass != null && asyncDelegate != null) {
				Method async;
				try {
					async = asyncDelegate.getMethod("async", Method.class, Object[].class);
					if(!promiseClass.equals(async.getReturnType())) {
						LOG.error("The client bundle {} has inconsistent views of the \"org.osgi.service.async.delegate\" and \"org.osgi.util.promise packages\". This indicates a missing uses constraint.", requestingBundle);
						asyncDelegate = null;
					}
				} catch (Exception e) {
					LOG.error("The {} package is missing a required method on AsyncDelegate", ASYNC_DELEGATE_PACKAGE);
					throw new RuntimeException(e);
				}
			}
		} else {
			//Not wired directly - pick a suitable alternative with the right class space for Promise
			Bundle promiseBundle = promiseClass != null ? FrameworkUtil.getBundle(promiseClass) : null;
			if(promiseBundle != null) {
				BundleWiring wiring = promiseBundle.adapt(BundleWiring.class);
				
				//Find importers of the promise package that export the async API
				Set<BundleWiring> asyncExporters = wiring.getProvidedWires(OSGI_WIRING_PACKAGE).stream()
					.filter(wire -> {
						return PROMISE_PACKAGE.equals(wire.getCapability().getAttributes().get(OSGI_WIRING_PACKAGE));
					})
					.map(BundleWire::getRequirer)
					.map(BundleRevision::getWiring)
					.filter(bw -> 
						// Are any exports suitable versions of async delegate
						bw.getCapabilities(OSGI_WIRING_PACKAGE).stream()
							.filter(c -> {
								return ASYNC_DELEGATE_PACKAGE.equals(c.getAttributes().get(OSGI_WIRING_PACKAGE));
							})
							.map(c -> (Version) c.getAttributes().get("version"))
							.filter(v -> v.compareTo(minAcceptable) >= 0)
							.anyMatch(v -> v.compareTo(minUnacceptable) < 0))
					.collect(Collectors.toSet());
				
				if(resolveClass(promiseBundle, AsyncDelegate.class.getName()) != null) {
					asyncExporters.add(wiring);
				}
				asyncDelegate = getMostAppropriateAsyncDelegate(asyncExporters, promiseClass);
			} else {
				//Just find the "best" exporter
				Set<BundleWiring> asyncExporters = Arrays.stream(requestingBundle.getBundleContext().getBundles())
					.map(b -> b.adapt(BundleWiring.class))
					.filter(bw -> bw != null)
					.filter(bw -> bw.getCapabilities(OSGI_WIRING_PACKAGE).stream()
								.filter(c -> ASYNC_DELEGATE_PACKAGE.equals(c.getAttributes().get(OSGI_WIRING_PACKAGE)))
								.map(c -> (Version) c.getAttributes().get("version"))
								.filter(v -> v.compareTo(minAcceptable) >= 0)
								.anyMatch(v -> v.compareTo(minUnacceptable) < 0))
					.collect(Collectors.toSet());
				
				asyncDelegate = getMostAppropriateAsyncDelegate(asyncExporters, promiseClass);
			}
		}
		
		return asyncDelegate;
	}

	private Class<?> getMostAppropriateAsyncDelegate(Set<BundleWiring> asyncExporters, Class<?> promiseClass) {
		Class<?> asyncDelegate;
		asyncDelegate = asyncExporters.stream()
				.sorted((a,b) -> {
					long aCount = a.getProvidedWires(OSGI_WIRING_PACKAGE).stream()
						.filter(wire -> {
							return ASYNC_DELEGATE_PACKAGE.equals(wire.getCapability().getAttributes().get(OSGI_WIRING_PACKAGE));
						}).count();

					long bCount = b.getProvidedWires(OSGI_WIRING_PACKAGE).stream()
							.filter(wire -> {
								return ASYNC_DELEGATE_PACKAGE.equals(wire.getCapability().getAttributes().get(OSGI_WIRING_PACKAGE));
							}).count();
					
					int diff = (int) (bCount - aCount);
					return diff == 0 ? b.getBundle().compareTo(a.getBundle()) : diff;
				}).map(bw -> {
						try {
							return bw.getClassLoader().loadClass(AsyncDelegate.class.getName());
						} catch (ClassNotFoundException cnfe) {
							return null;
						}
					})
				.filter(c -> c != null)
				.filter(c -> {
						try {
							return promiseClass == null ? true : 
								c.getMethod("async", Method.class, Object[].class)
									.getReturnType().equals(promiseClass);
						} catch (Exception e) {
							return false;
						}
					})
				.findFirst().orElse(null);
		return asyncDelegate;
	}

	private Class<?> locatePromise(Bundle requestingBundle) {
		Class<?> promiseClass = resolveClass(requestingBundle, Promise.class.getName());
		
		if(promiseClass != null) {
			promiseClass = checkAcceptableVersion(requestingBundle, promiseClass, 
					PROMISE_PACKAGE, new Version(1,0,0), new Version(2,0,0));
		}
		return promiseClass;
	}

	private Class<?> checkAcceptableVersion(Bundle requestingBundle, Class<?> apiClass, String apiPackage,
			Version minAcceptable, Version minUnacceptable) {
		Bundle promiseBundle = FrameworkUtil.getBundle(apiClass);
		if(promiseBundle != null) {
			BundleWiring wiring = promiseBundle.adapt(BundleWiring.class);
			
			SortedSet<Version> promiseVersions = wiring.getCapabilities(OSGI_WIRING_PACKAGE).stream()
				.filter(c -> {
					return apiPackage.equals(c.getAttributes().get(OSGI_WIRING_PACKAGE));
				})
				.map(c -> (Version) c.getAttributes().get("version"))
				.collect(Collector.of(
						() -> (SortedSet<Version>) new TreeSet<Version>(), 
						(s,v) -> s.add(v),
						(a,b) -> {
							SortedSet<Version> newSet = new TreeSet<>(a);
							newSet.addAll(b);
							return newSet;
						}));
			
			if(promiseVersions.isEmpty()) {
				LOG.warn("Unable to determine the package version of the package {} for the service {} and the client {} as the client has a private copy. Errors may occur if the version is incompatible with the range \"[{},{})\".",
						new Object[] {apiPackage, _endpointDescription.getId(), requestingBundle, minAcceptable, minUnacceptable});
			} else if(promiseVersions.last().compareTo(minAcceptable) < 0) {
				LOG.warn("Unable to support the package {} for the service {} and the client {} as the wired package version {} is too low. A minimum of version {} is required.",
						new Object[] {apiPackage, _endpointDescription.getId(), requestingBundle, promiseVersions.last(), minAcceptable});
				apiClass = null;
			} else if(promiseVersions.first().compareTo(minUnacceptable) >= 0) {
				LOG.warn("Unable to support the package {} for the service {} and the client {} as the wired package version {} is too high. The version must be below {}.",
						new Object[] {apiPackage, _endpointDescription.getId(), requestingBundle, promiseVersions.first(), minUnacceptable});
				apiClass = null;
			}
		} else {
			LOG.warn("Unable to determine the version of the package {} visible to the service {} and the client {}. Errors may occur if the version is incompatible with the range \"[{},{})\".",
					new Object[] {apiPackage, _endpointDescription.getId(), requestingBundle, minAcceptable, minUnacceptable});
		}
		return apiClass;
	}
	
	private ClassLoader getClassLoader(Bundle requestingBundle, Class<?> async, Class<?> promise, 
			List<Class<?>> interfaces) {
		return new ClassLoader(requestingBundle.adapt(BundleWiring.class).getClassLoader()) {
			@Override
			protected Class<?> findClass(String name)
					throws ClassNotFoundException {
				if(Promise.class.getName().equals(name) && promise != null) {
					return promise;
				}
				if(AsyncDelegate.class.getName().equals(name) && async != null) {
					return async;
				} 
				for(Class<?> clz : interfaces) {
					ClassLoader classLoader = clz.getClassLoader();
					if(classLoader != null) {
						try {
							return classLoader.loadClass(name);
						} catch (ClassNotFoundException cnfe) {}
					}
				}
				
				throw new ClassNotFoundException(name);
			}
        };
	}

	public void ungetService(Bundle requestingBundle, ServiceRegistration<Object> sreg, final Object serviceObject) { }
}
