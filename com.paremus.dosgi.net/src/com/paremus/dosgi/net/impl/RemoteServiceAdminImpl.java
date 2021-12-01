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

import static com.paremus.dosgi.net.impl.RegistrationState.OPEN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.toSignature;
import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.osgi.framework.Constants.FRAMEWORK_UUID;
import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.framework.Constants.SERVICE_ID;
import static org.osgi.framework.ServiceException.UNREGISTERED;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.ENDPOINT_FRAMEWORK_UUID;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.ENDPOINT_ID;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.ENDPOINT_SERVICE_ID;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.SERVICE_IMPORTED_CONFIGS;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.SERVICE_INTENTS;

import java.lang.reflect.Method;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointPermission;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.client.ClientConnectionManager;
import com.paremus.dosgi.net.config.Config;
import com.paremus.dosgi.net.config.ExportedServiceConfig;
import com.paremus.dosgi.net.config.ImportedServiceConfig;
import com.paremus.dosgi.net.proxy.MethodCallHandlerFactory;
import com.paremus.dosgi.net.serialize.SerializationType;
import com.paremus.dosgi.net.serialize.Serializer;
import com.paremus.dosgi.net.server.RemotingProvider;
import com.paremus.dosgi.net.server.ServiceInvoker;
import com.paremus.dosgi.topology.scoped.IsolationAwareRemoteServiceAdmin;

import aQute.bnd.annotation.metatype.Configurable;
import io.netty.util.concurrent.EventExecutorGroup;

public class RemoteServiceAdminImpl implements IsolationAwareRemoteServiceAdmin {
	
	private static final String CONFIDENTIALITY_MESSAGE = "confidentiality.message";

	private static final Logger LOG = LoggerFactory.getLogger(RemoteServiceAdminImpl.class);
	
	private static final EndpointPermission exportPermission = new EndpointPermission("*",
	        EndpointPermission.EXPORT);
	
	private final Map<Framework, Map<ServiceReference<?>, Set<ExportRegistrationImpl>>> exports
		= new HashMap<>();
	private final Map<Framework, Map<UUID, Set<ImportRegistrationImpl>>> imports
		= new HashMap<>();

	private final Framework defaultFramework;
	private final RemoteServiceAdminEventPublisher publisher;
	
	private final List<? extends RemotingProvider> remoteProviders;
	
	private final ClientConnectionManager clientConnectionManager;
	
	private final ProxyHostBundleFactory proxyHostBundleFactory;

	private final EventExecutorGroup serverWorkers;

	private Config config;

	private final List<String> intents;
	
	public RemoteServiceAdminImpl(Framework defaultFramework, RemoteServiceAdminEventPublisher publisher,
			List<? extends RemotingProvider> remoteProviders, ClientConnectionManager ccm, List<String> intents,
			ProxyHostBundleFactory phbf, EventExecutorGroup serverWorkers, Config config) {
		this.defaultFramework = defaultFramework;
		this.publisher = publisher;
		this.remoteProviders = remoteProviders;
		this.clientConnectionManager = ccm;
		this.intents = Collections.unmodifiableList(intents);
		this.proxyHostBundleFactory = phbf;
		this.serverWorkers = serverWorkers;
		this.config = config;
	}

	@Override
	public Collection<ExportRegistration> exportService(ServiceReference<?> reference, Map<String, ?> properties) {
		return exportService(defaultFramework, reference, properties);
	}
	
    @Override
    public Collection<ExportRegistration> exportService(Framework framework, ServiceReference<?> ref, 
    		Map<String, ?> additionalProperties) {
        LOG.debug("exportService: {}", ref);

        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            try {
                sm.checkPermission(exportPermission);
            }
            catch (SecurityException se) {
                return Collections.singletonList(new ExportRegistrationImpl(ref, this, se));
            }
        }

        PrivilegedAction<Collection<ExportRegistration>> exportAction = 
        		() -> privilegedExportService(framework, ref, additionalProperties);

        return AccessController.doPrivileged(exportAction);
    }

	private Collection<ExportRegistration> privilegedExportService(Framework framework, ServiceReference<?> ref,
			Map<String, ?> props) {
		ExportRegistrationImpl reg;
		synchronized (exports) {
			Map<ServiceReference<?>, Set<ExportRegistrationImpl>> exportsForFramework = 
					exports.computeIfAbsent(framework, k -> new HashMap<>());
			ExportRegistrationImpl tmpReg;
			try {
				EndpointDescription endpoint = createEndpointDescription(framework, ref, props);
				if(endpoint == null) {
					return Collections.emptySet();
				}
				tmpReg = new ExportRegistrationImpl(ref, props, endpoint, framework, this);
			} catch (IllegalArgumentException ex) {
				publisher.notifyExportError(ref, ex);
				throw ex;
			} catch (UnsupportedOperationException ex) {
				publisher.notifyExportError(ref, ex);
				return Collections.emptySet();
			} catch (Exception e) {
				publisher.notifyExportError(ref, e);
				tmpReg = new ExportRegistrationImpl(ref, this, e);
			}
			reg = tmpReg;
			exportsForFramework.compute(ref, (k,v) -> {
				return Stream.concat(Stream.of(reg), ofNullable(v).map(Set::stream).orElse(Stream.empty()))
						.collect(toSet());
			});
		}

		switch(reg.getState()) {
			case PRE_INIT :
				notifyExport(reg, reg.start());
			case OPEN:
				LOG.info("The service {} already had an open export from this RSA", ref);
				break;
			case ERROR:
				LOG.info("The service {} failed to export from this RSA", ref);
				break;
			case CLOSED:
			default:
				break;
		}
		
		return Collections.singleton(reg);
	}

	private void notifyExport(ExportRegistrationImpl reg, RegistrationState registrationState) {
		switch(registrationState) {
			case ERROR:
				publisher.notifyExportError(reg.getServiceReference(), reg.getException());
				break;
			case OPEN:
				ExportReference er = reg.getExportReference();
				publisher.notifyExport(er.getExportedService(), er.getExportedEndpoint());
				break;
			case PRE_INIT:
				throw new IllegalStateException("The registration should have been initialized");
			case CLOSED:
			default:
				break;
		
		}
	}

	private EndpointDescription createEndpointDescription(Framework framework, 
			ServiceReference<?> ref, Map<String, ?> additionalProperties) {
		return createEndpointDescription(framework, ref, additionalProperties, UUID.randomUUID());
	}
	
	private EndpointDescription createEndpointDescription(Framework framework, 
			ServiceReference<?> ref, Map<String, ?> additionalProperties, UUID id) {
		
		// gather properties from service
        Map<String, Object> serviceProperties = new HashMap<String, Object>();
        for (String k : ref.getPropertyKeys()) {
        	if(k.charAt(0) != '.') {
        		serviceProperties.put(k, ref.getProperty(k));
        	}
        }

        // overlay properties with any additional properties
        if (additionalProperties != null) {
            overlayProperties(serviceProperties, additionalProperties);
        }

        ExportedServiceConfig config = Configurable.createConfigurable(
        		ExportedServiceConfig.class, serviceProperties);
        
        if(ofNullable(config.service_exported_configs())
        		.map(ec -> !ec.contains("com.paremus.dosgi.net"))
        		.orElse(false)) {
        	LOG.info("Unable to export the service {} as it only supports the configuration types {}",
        			ref, config.service_exported_configs());
        	return null;
        }

        Set<String> requiredIntents = Stream.concat(
        		ofNullable(config.service_exported_intents())
	            	.map(Collection::stream)
	            	.orElse(Stream.empty()), 
	            ofNullable(config.service_exported_intents_extra())
	            	.map(Collection::stream)
	            	.orElse(Stream.empty())).collect(toSet());
        	
        Set<String> supportedIntents = new HashSet<>(intents);
        Set<String> unsupported = requiredIntents.stream()
        	.filter(s -> !supportedIntents.contains(s))
        	.collect(Collectors.toSet());
        
        if (!unsupported.isEmpty()) {
        	LOG.info("Unable to export the service {} as the following intents are not supported {}"
		                            , ref, unsupported);
        	throw new UnsupportedOperationException(unsupported.toString());
		}
        
        Object service = ref.getBundle().getBundleContext().getService(ref);
        if(service == null) {
        	LOG.info("Unable to obtain the service object for {}", ref);
        	throw new ServiceException("The service object was null and so cannot be exported", UNREGISTERED);
        }
        
        List<Class<?>> exportedClasses = config.service_exported_interfaces().stream()
        		.flatMap(s -> "*".equals(s) ? config.objectClass().stream() : Stream.of(s))
        		.filter(s -> config.objectClass().contains(s))
        		.map(n -> {
	        			try {
	        				ClassLoader loader = service.getClass().getClassLoader();
	        				Class<?> toReturn = (loader != null) ? loader.loadClass(n) : Class.forName(n);
							return toReturn;
		        		} catch (ClassNotFoundException cnfe) {
	        				LOG.error("The service {} exports the type {} but cannot load it.", ref, n);
	        				return null;
	        			}
        			})
        		.filter(c -> c != null)
        		.filter(c -> c.isInstance(service))
        		.collect(toList());

        // no interface can be resolved..why?
        if (exportedClasses.isEmpty()) {
        	LOG.error("Unable to obtain any exported types for service {} with exported interfaces {}", 
        			ref, config.service_exported_interfaces());
        	throw new IllegalArgumentException("Unable to load any exported types for the service " + ref + 
        			" with exported interfaces " + config.service_exported_interfaces());
        }

        try {
	        Predicate<RemotingProvider> remotingSelector;
	        
	        if(requiredIntents.contains(CONFIDENTIALITY_MESSAGE)) {
	        	remotingSelector = rp -> rp.isSecure();
	        } else {
	        	remotingSelector = rp -> true;
	        }
	        
	        List<RemotingProvider> validProviders = remoteProviders.stream()
	        		.filter(remotingSelector)
	        		.collect(Collectors.<RemotingProvider>toList());
	        List<RemotingProvider> invalidProviders = remoteProviders.stream()
	        		.filter(remotingSelector.negate())
	        		.collect(Collectors.<RemotingProvider>toList());
	        
	        invalidProviders.stream()
	         	.forEach(rp -> rp.unregisterService(id));
	         
	        SerializationType serializationType;
	        try {
	        	serializationType = config.com_paremus_dosgi_net_serialization();
	        } catch (Exception e) {
	        	throw new IllegalArgumentException("Invalid com.paremus.dosgi.net.serialization property", e);
	        }
			Serializer serializer = serializationType
	        		 .getFactory().create(ref.getBundle());
	         
	         AtomicInteger i = new AtomicInteger();
	         Map<Integer, Method> methodMappings = exportedClasses.stream()
	        		 .map(Class::getMethods)
	        		 .flatMap(Arrays::stream)
	        		 .collect(Collectors.toMap(m -> i.incrementAndGet(), Function.identity()));
	         
	        ServiceInvoker invoker = new ServiceInvoker(id, serializer, service, methodMappings, serverWorkers);
	       
	        List<String> connectionStrings = validProviders.stream()
	        		 .map(rp -> rp.registerService(id, invoker))
	        		 .filter(uri -> uri != null)
	        		 .map(URI::toString)
	        		 .collect(toList());
	         
	        if(connectionStrings.isEmpty()) {
	        	LOG.warn("No remoting providers are available to expose the service {}", ref);
	        	throw new IllegalArgumentException("No remoting providers are available to expose the service " + ref);
	        }
	        addRSAProperties(serviceProperties, id, ref, config, exportedClasses, 
	        		supportedIntents, connectionStrings, methodMappings, framework);
	        
	        return new EndpointDescription(serviceProperties);
        } catch (Exception e) {
        	remoteProviders.stream()
        		.forEach(rp -> rp.unregisterService(id));
        	throw e;
        }
	}

	private void addRSAProperties(Map<String, Object> serviceProperties, UUID id, 
			ServiceReference<?> ref, ExportedServiceConfig config, List<Class<?>> exportedClasses,
			Set<String> intents, List<String> connectionStrings, Map<Integer, Method> methodMappings, Framework framework) {
		
		Set<String> packages = exportedClasses.stream()
					.map(Class::getPackage)
					.map(Package::getName)
					.collect(toSet());
		
		BundleWiring wiring = ref.getBundle().adapt(BundleWiring.class);
		Map<String, Version> importedVersions = wiring.getRequiredWires("osgi.wiring.package").stream()
			.map(bw -> bw.getCapability().getAttributes())
			.filter(m -> packages.contains(m.get("osgi.wiring.package")))
			.collect(toMap(m -> (String) m.get("osgi.wiring.package"), m -> (Version) m.get("version")));
		
		packages.removeAll(importedVersions.keySet());
		
		wiring.getCapabilities("osgi.wiring.package").stream()
			.map(c -> c.getAttributes())
			.filter(m -> packages.contains(m.get("osgi.wiring.package")))
			.forEach(m -> importedVersions.putIfAbsent((String)m.get("osgi.wiring.package"), (Version)m.get("version")));
		
		importedVersions.entrySet().stream()
			.forEach(e -> serviceProperties.put(RemoteConstants.ENDPOINT_PACKAGE_VERSION_ + e.getKey(), e.getValue().toString()));
        
        serviceProperties.put(ENDPOINT_ID, id.toString());
        serviceProperties.put(ENDPOINT_FRAMEWORK_UUID, framework.getBundleContext().getProperty(FRAMEWORK_UUID));
        serviceProperties.put(ENDPOINT_SERVICE_ID, ref.getProperty(SERVICE_ID));
        serviceProperties.put(SERVICE_INTENTS, 
        		Stream.concat(intents.stream(), 
        				ofNullable(config.service_intents())
        					.map(Collection::stream)
        					.orElse(Stream.empty()))
        			.collect(toSet()));
        serviceProperties.put("com.paremus.dosgi.net", connectionStrings);
        serviceProperties.put(SERVICE_IMPORTED_CONFIGS, "com.paremus.dosgi.net");
        serviceProperties.put(OBJECTCLASS, exportedClasses.stream()
        					.map(Class::getName)
        					.collect(toList()).toArray(new String[0]));
        
        List<String> methodMappingData = methodMappings.entrySet().stream()
        		.map(e -> new StringBuilder()
        				.append(e.getKey())
        				.append('=')
        				.append(toSignature(e.getValue()))
        				.toString())
        		.collect(toList());
        
        if(serviceProperties.containsKey("com.paremus.dosgi.net.methods")) {
        	throw new IllegalArgumentException("The com.paremus.dosgi.net.methods property is not user editable");
        }
        
        serviceProperties.put("com.paremus.dosgi.net.methods", methodMappingData);
	}

	void removeExportRegistration(ExportRegistrationImpl exportRegistration,
			ServiceReference<?> serviceReference) {
		synchronized (exports) {
			UUID id = exportRegistration.getId();
			// Exports that failed early may not have an id at all.
			if(id != null) {
				remoteProviders.stream()
					.forEach(rp -> {
						rp.unregisterService(id);
					});
			}
			
			exports.compute(exportRegistration.getSourceFramework(), (k,v) -> {
				Map<ServiceReference<?>, Set<ExportRegistrationImpl>> m = 
						v == null ? new HashMap<>() : new HashMap<>(v);
				
						m.computeIfPresent(serviceReference, (ref, set) -> {
							Set<ExportRegistrationImpl> s2 = set.stream()
									.filter(e -> e != exportRegistration)
									.collect(toSet());
							return s2.isEmpty() ? null : s2;
						});
				return m.isEmpty() ? null : m;
			});
		}
		ofNullable(serviceReference.getBundle())
			.map(Bundle::getBundleContext)
			.ifPresent(bc -> bc.ungetService(serviceReference));
		publisher.notifyExportRemoved(serviceReference, exportRegistration.getEndpointDescription(),
				exportRegistration.getException());
	}

	@Override
	public ImportRegistration importService(EndpointDescription endpoint) {
		return importService(defaultFramework, endpoint);
	}

	@Override
	public ImportRegistration importService(Framework framework, EndpointDescription e) {
		LOG.debug("importService: {}", e);

        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            EndpointPermission importPermission = new EndpointPermission(e,
                framework.getBundleContext().getProperty(FRAMEWORK_UUID), EndpointPermission.IMPORT);
            sm.checkPermission(importPermission);
        }

        PrivilegedAction<ImportRegistration> importAction = () -> privilegedImportService(framework, e);

        return AccessController.doPrivileged(importAction);
	}
	
	private ImportRegistration privilegedImportService(Framework framework, EndpointDescription e) {
		
		if(!e.getConfigurationTypes().contains("com.paremus.dosgi.net")) {
			LOG.info("Unable to import the endpoint {} because it uses unsupported configuration types {}",
					e, e.getConfigurationTypes());
			return null;
		}
		
		ExportedServiceConfig edConfig = Configurable.createConfigurable(
	        		ExportedServiceConfig.class, e.getProperties());
		
        Set<String> unsupported = Stream.concat(
        		ofNullable(edConfig.service_exported_intents())
            	.map(Collection::stream)
            	.orElse(Stream.empty()), 
            ofNullable(edConfig.service_exported_intents_extra())
            	.map(Collection::stream)
            	.orElse(Stream.empty()))
		        	.filter(s -> !intents.contains(s))
		        	.collect(Collectors.toSet());
        
        if (!unsupported.isEmpty()) {
        	LOG.info("Unable to import the endpoint {} as the following intents are not supported {}"
		                            , e, unsupported);
        	return null;
		}
		
		UUID id = UUID.fromString(e.getId());
		
		BundleContext proxyHostContext = proxyHostBundleFactory.getProxyBundle(framework).getBundleContext();
		
		ImportRegistrationImpl reg;
		if(proxyHostContext == null) {
			// Don't bother with a stack trace
			IllegalStateException failure = new IllegalStateException(
					"The RSA host bundle context in target framework " + framework  + " is not active");
			failure.setStackTrace(new StackTraceElement[0]);
			reg = new ImportRegistrationImpl(e, framework, this, 
					failure);
		} else {
			reg = new ImportRegistrationImpl(e, framework, 
					proxyHostContext, this, config.client_default_timeout());
			
			synchronized (imports) {
				imports.computeIfAbsent(framework, k -> new HashMap<>())
					.compute(id, (k,v) -> 
						Stream.concat(Stream.of(reg), 
								ofNullable(v).map(Set::stream).orElseGet(() -> Stream.empty()))
							.collect(toSet()));
			}
		}
		
		switch(reg.getState()) {
			case ERROR:
				publisher.notifyImportError(reg.getEndpointDescription(), reg.getException());
				break;
			case OPEN:
				publisher.notifyImport(reg.getServiceReference(), reg.getEndpointDescription());
				break;
			case CLOSED:
			case PRE_INIT:
				reg.asyncFail(new IllegalStateException("The registration was not fully initialized, and was found in state " + reg.getState()));
				break;
		}
		
		return reg;
	}

	void removeImportRegistration(ImportRegistrationImpl importRegistration,
			String endpointId) {
		synchronized (imports) {
			imports.computeIfPresent(importRegistration.getTargetFramework(), (k,v) -> {
				Map<UUID, Set<ImportRegistrationImpl>> m2 = new HashMap<>(v);
						m2.computeIfPresent(UUID.fromString(endpointId), (id,set) -> {
							Set<ImportRegistrationImpl> s2 = set.stream()
									.filter(ir -> ir != importRegistration)
									.collect(toSet());
							return s2.isEmpty() ? null : s2;
						});
				return m2.isEmpty() ? null : m2;
			});
		}
		
		publisher.notifyImportRemoved(importRegistration.getServiceReference(), 
				importRegistration.getEndpointDescription(), importRegistration.getException());
	}

	void notifyImportError(ImportRegistrationImpl importRegistration, String endpointId) {
		synchronized (imports) {
			imports.computeIfPresent(importRegistration.getTargetFramework(), (k,v) -> {
				Map<UUID, Set<ImportRegistrationImpl>> m2 = new HashMap<>(v);
						m2.computeIfPresent(UUID.fromString(endpointId), (id,set) -> {
							Set<ImportRegistrationImpl> s2 = set.stream()
									.filter(ir -> ir != importRegistration)
									.collect(toSet());
							return s2.isEmpty() ? null : s2;
						});
				return m2.isEmpty() ? null : m2;
			});
		}
		
		publisher.notifyImportError(importRegistration.getEndpointDescription(), 
				importRegistration.getException());
	}

	void notifyImportUpdate(ServiceReference<?> reference, EndpointDescription endpointDescription,
			Throwable exception) {
		publisher.notifyImportUpdate(reference, endpointDescription, exception);
	}

	@Override
	public Collection<ExportReference> getExportedServices() {
		return getExportedServices(defaultFramework);
	}

	@Override
	public Collection<ExportReference> getExportedServices(Framework framework) {
		synchronized (exports) {
			return ofNullable(exports.get(framework))
				.map(m -> m.values().stream()
						.flatMap(Set::stream)
						.filter(e -> e.getState() == OPEN)
						.map(ExportRegistration::getExportReference)
						.collect(Collectors.toSet()))
				.orElse(emptySet());
		}
	}

	@Override
	public Collection<ExportReference> getAllExportedServices() {
		synchronized (exports) {
			return exports.values().stream()
					.flatMap(m -> m.values().stream())
					.flatMap(Set::stream)
					.filter(i -> i.getState() == OPEN)
					.map(ExportRegistration::getExportReference)
					.collect(toSet());
		}
	}

	@Override
	public Collection<ImportReference> getImportedEndpoints() {
		return getImportedEndpoints(defaultFramework);
	}

	@Override
	public Collection<ImportReference> getImportedEndpoints(Framework framework) {
		synchronized (imports) {
			return ofNullable(imports.get(framework))
				.map(m -> m.values().stream()
						.flatMap(Set::stream)
						.filter(i -> i.getState() == OPEN)
						.map(ImportRegistration::getImportReference)
						.collect(toSet()))
				.orElse(emptySet());
		}
	}

	@Override
	public Collection<ImportReference> getAllImportedEndpoints() {
		synchronized (imports) {
			return imports.values().stream()
					.flatMap(m -> m.values().stream())
					.flatMap(Set::stream)
					.filter(i -> i.getState() == OPEN)
					.map(ImportRegistration::getImportReference)
					.collect(toSet());
		}
	}

	/**
     * Overlays (overwrites or adds) a set of key/value pairs onto a given Map. Keys are
     * handled case-insensitively: an original mapping of <code>fooBar=x</code> will be
     * overwritten with <code>FooBar=y</code>. Mappings with {@link Constants#OBJECTCLASS}
     * and {@link Constants#SERVICE_ID} keys are <b>not</b> overwritten, regardless of
     * case.
     * 
     * @param serviceProperties a <b>mutable</b> Map of key/value pairs
     * @param additionalProperties additional key/value mappings to overlay
     * @throws NullPointerException if either argument is <code>null</code>
     */
    static void overlayProperties(Map<String, Object> serviceProperties,
                                         Map<String, ?> additionalProperties) {
        Objects.requireNonNull(serviceProperties, "The service properties were null");

        if (additionalProperties == null || additionalProperties.isEmpty()) {
            // nothing to do
            return;
        }

        // Maps lower case key to original key
        Map<String, String> lowerKeys = new HashMap<String, String>(serviceProperties.size());
        for (Entry<String, Object> sp : serviceProperties.entrySet()) {
            lowerKeys.put(sp.getKey().toLowerCase(), sp.getKey());
        }

        // keys that must not be overwritten
        String lowerObjClass = OBJECTCLASS.toLowerCase();
        String lowerServiceId = SERVICE_ID.toLowerCase();

        for (Entry<String, ?> ap : additionalProperties.entrySet()) {
            String key = ap.getKey().toLowerCase();
            if (lowerObjClass.equals(key) || lowerServiceId.equals(key)) {
                // exportService called with additional properties map that contained
                // illegal key; the key is ignored
                continue;
            }
            else if (lowerKeys.containsKey(key)) {
                String origKey = lowerKeys.get(key);
                serviceProperties.put(origKey, ap.getValue());
            }
            else {
                serviceProperties.put(ap.getKey(), ap.getValue());
                lowerKeys.put(key, ap.getKey());
            }
        }
    }

	EndpointDescription updateExport(Framework source, ServiceReference<?> ref, 
			Map<String, ?> additionalProperties, UUID id, EndpointDescription previous) {
		EndpointDescription ed = null;
		try {
			ed = createEndpointDescription(source, ref, additionalProperties, id);
			publisher.notifyExportUpdate(ref, ed, null);
		} catch (Exception e) {
			publisher.notifyExportUpdate(ref, previous, e);
		}
		return ed;
	}

	MethodCallHandlerFactory getHandlerFactoryFor(EndpointDescription endpointDescription, 
			ImportedServiceConfig config, Map<Integer, String> methodMappings) {
		
		List<URI> uris = config.com_paremus_dosgi_net();
		SerializationType type = config.com_paremus_dosgi_net_serialization();
		
		return uris.stream()
			.map(uri -> clientConnectionManager.getFactoryFor(uri, endpointDescription, type.getFactory(), methodMappings))
			.filter(mchf -> mchf != null)
			.findFirst().orElseThrow(() -> 
				new IllegalArgumentException("Unable to connect to any of the endpoint locations " + uris));
	}

	
	void close() {
		synchronized (imports) {
			imports.values().stream()
				.flatMap(m -> m.values().stream())
				.flatMap(Set::stream)
				.collect(toSet()).stream()
				.forEach(ImportRegistration::close);
		}
		synchronized (exports) {
			exports.values().stream()
				.flatMap(m -> m.values().stream())
				.flatMap(Set::stream)
				.collect(toSet()).stream()
				.forEach(ExportRegistration::close);
		}
	}

}
