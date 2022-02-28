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

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.ot.rsa.distribution.provider.impl.RegistrationState.OPEN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.toSignature;
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
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.ot.rsa.constants.RSAConstants;
import org.eclipse.ot.rsa.distribution.config.ExportedServiceConfig;
import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.eclipse.ot.rsa.distribution.provider.client.ClientConnectionManager;
import org.eclipse.ot.rsa.distribution.provider.serialize.SerializationType;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.server.RemotingProvider;
import org.eclipse.ot.rsa.distribution.provider.server.ServiceInvoker;
import org.eclipse.ot.rsa.multrsa.api.MultiFrameworkRemoteServiceAdmin;
import org.eclipse.ot.rsa.servicecap.util.ServiceCapability;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointPermission;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.converter.Converters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;

@ServiceCapability({
	MultiFrameworkRemoteServiceAdmin.class, RemoteServiceAdmin.class
})
public class RemoteServiceAdminImpl implements MultiFrameworkRemoteServiceAdmin {

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
	private final EventExecutorGroup clientWorkers;

	private final Timer timer;

	private TransportConfig config;

	private final List<String> intents;

	private final RemoteServiceAdminFactoryImpl factory;

	public RemoteServiceAdminImpl(RemoteServiceAdminFactoryImpl factory, Framework defaultFramework, RemoteServiceAdminEventPublisher publisher,
			List<? extends RemotingProvider> remoteProviders, ClientConnectionManager ccm, List<String> intents,
			ProxyHostBundleFactory phbf, EventExecutorGroup serverWorkers, EventExecutorGroup clientWorkers,
			Timer timer, TransportConfig config) {
		this.factory = factory;
		this.defaultFramework = defaultFramework;
		this.publisher = publisher;
		this.remoteProviders = remoteProviders;
		this.clientConnectionManager = ccm;
		this.intents = Collections.unmodifiableList(intents);
		this.proxyHostBundleFactory = phbf;
		this.serverWorkers = serverWorkers;
		this.clientWorkers = clientWorkers;
		this.timer = timer;
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
		String target = config.endpoint_export_target();

		if(!target.isEmpty()) {
			try {
				if(!FrameworkUtil.createFilter(target).match(ref)) {
					LOG.debug("The service reference {} is excluded by the configured export filter {} and will not be exported", ref, target);
					return emptyList();
				}
			} catch (InvalidSyntaxException e) {
				LOG.error("Unable to filter the export operation due to a filter syntax error.", e);
				return singleton(new ExportRegistrationImpl(ref, this, e));
			}
		}

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
				break;
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

		return singleton(reg);
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
        Map<String, Object> serviceProperties = new HashMap<>();
        for (String k : ref.getPropertyKeys()) {
        	if(k.charAt(0) != '.') {
        		serviceProperties.put(k, ref.getProperty(k));
        	}
        }

        // overlay properties with any additional properties
        if (additionalProperties != null) {
            overlayProperties(serviceProperties, additionalProperties);
        }

        ExportedServiceConfig config;
		try {
			config = Converters.standardConverter().convert(
					serviceProperties).to(ExportedServiceConfig.class);
		} catch (Exception ex) {
			LOG.error("A failure occurred trying to export service {}", ref, ex);
			return null;
		}

        String[] configs = config.service_exported_configs();
		if(configs.length > 0 && !Arrays.asList(configs)
			.contains(RSAConstants.DISTRIBUTION_CONFIGURATION_TYPE)) {
        	LOG.info("Unable to export the service {} as it only supports the configuration types {}",
        			ref, configs);
        	return null;
        }

        Set<String> requiredIntents = Stream.concat(
        		Arrays.stream(config.service_exported_intents()),
	            Arrays.stream(config.service_exported_intents_extra()))
        			.collect(toSet());

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

        List<String> objectClass = Arrays.asList(config.objectClass());

        List<Class<?>> exportedClasses = Arrays.stream(config.service_exported_interfaces())
        		.flatMap(s -> "*".equals(s) ? objectClass.stream() : Stream.of(s))
        		.filter(s -> objectClass.contains(s))
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

	        if(requiredIntents.contains(CONFIDENTIALITY_MESSAGE) ||
	        		requiredIntents.contains("osgi.confidential")) {
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

	        if(validProviders.isEmpty()) {
	        	LOG.debug("There are no remoting providers for this RSA able to export the service {}", ref);
	        	return null;
	        }

			SerializationType serializationType = SerializationType
				.of(config.org_eclipse_ot_rsa_exported_serialization());

	        Bundle classSpace = FrameworkUtil.getBundle(service.getClass());

			Serializer serializer = serializationType
	        		 .getFactory().create(classSpace == null ? ref.getBundle() : classSpace);

	        SortedMap<String, Method> methodMappings = exportedClasses.stream()
	        		 .map(Class::getMethods)
	        		 .flatMap(Arrays::stream)
	        		 .collect(Collectors.toMap(m -> toSignature(m), Function.identity(),
	        				 (a,b) -> a, TreeMap::new));

	        Function<RemotingProvider, ServiceInvoker> invoker =
	        		rp -> new ServiceInvoker(rp, id, serializer, service, methodMappings
	        		.values().toArray(new Method[0]), serverWorkers, timer);

	        List<String> connectionStrings = validProviders.stream()
	        		 .map(rp -> rp.registerService(id, invoker.apply(rp)))
	        		 .flatMap(Collection::stream)
	        		 .map(URI::toString)
	        		 .collect(toList());

	        if(connectionStrings.isEmpty()) {
	        	LOG.warn("No remoting providers successfully exposed the service {}", ref);
	        	throw new IllegalArgumentException("No remoting providers are able to expose the service " + ref);
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
			Set<String> intents, List<String> connectionStrings, Map<String, Method> methodMappings, Framework framework) {

		Set<String> packages = exportedClasses.stream()
					.map(Class::getPackage)
					.map(Package::getName)
					.collect(toSet());

		BundleWiring wiring = ref.getBundle().adapt(BundleWiring.class);
		Map<String, Version> importedVersions = wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)
			.stream()
			.map(bw -> bw.getCapability().getAttributes())
			.filter(m -> packages.contains(m.get(PackageNamespace.PACKAGE_NAMESPACE)))
			.collect(toMap(m -> (String) m.get(PackageNamespace.PACKAGE_NAMESPACE), m -> (Version) m.get("version")));

		packages.removeAll(importedVersions.keySet());

		wiring.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)
			.stream()
			.map(c -> c.getAttributes())
			.filter(m -> packages.contains(m.get(PackageNamespace.PACKAGE_NAMESPACE)))
			.forEach(m -> importedVersions.putIfAbsent((String) m.get(PackageNamespace.PACKAGE_NAMESPACE),
				(Version) m.get("version")));

		importedVersions.entrySet().stream()
			.forEach(e -> serviceProperties.put(RemoteConstants.ENDPOINT_PACKAGE_VERSION_ + e.getKey(), e.getValue().toString()));

        serviceProperties.put(ENDPOINT_ID, id.toString());
        serviceProperties.put(ENDPOINT_FRAMEWORK_UUID, framework.getBundleContext().getProperty(FRAMEWORK_UUID));
        serviceProperties.put(ENDPOINT_SERVICE_ID, ref.getProperty(SERVICE_ID));
        serviceProperties.put(SERVICE_INTENTS,
        		Stream.concat(intents.stream(),
        				ofNullable(config.service_intents())
        					.map(Arrays::stream)
        					.orElse(Stream.empty()))
        			.collect(toList()));
		serviceProperties.put(RSAConstants.DISTRIBUTION_CONFIGURATION_TYPE, connectionStrings);
		serviceProperties.put(SERVICE_IMPORTED_CONFIGS, RSAConstants.DISTRIBUTION_CONFIGURATION_TYPE);
        serviceProperties.put(OBJECTCLASS, exportedClasses.stream()
        					.map(Class::getName)
        					.collect(toList()).toArray(new String[0]));

        AtomicInteger i = new AtomicInteger();
        List<String> methodMappingData = methodMappings.keySet().stream()
        		.sequential()
        		.map(s -> new StringBuilder()
        				.append(i.getAndIncrement())
        				.append('=')
        				.append(s)
        				.toString())
        		.collect(toList());

		if (serviceProperties.containsKey(RSAConstants.DISTRIBUTION_METHODS)) {
        	throw new IllegalArgumentException("The org.eclipse.ot.rsa.distribution.provider.methods property is not user editable");
        }

		serviceProperties.put(RSAConstants.DISTRIBUTION_METHODS, methodMappingData);

        if(!this.config.endpoint_marker().isEmpty()) {
			serviceProperties.put(RSAConstants.DISTRIBUTION_ENDPOINT_MARKER, this.config.endpoint_marker());
        }
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
				exportRegistration.internalGetException());
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
            String property = doPrivGetFrameworkId(framework);
			EndpointPermission importPermission = new EndpointPermission(e,
                property, EndpointPermission.IMPORT);
            sm.checkPermission(importPermission);
        }

        PrivilegedAction<ImportRegistration> importAction = () -> privilegedImportService(framework, e);

        return AccessController.doPrivileged(importAction);
	}

	private String doPrivGetFrameworkId(Framework framework) {
		PrivilegedAction<String> getFwId = () -> framework.getBundleContext().getProperty(FRAMEWORK_UUID);
		String property = AccessController.doPrivileged(getFwId);
		return property;
	}

	private ImportRegistration privilegedImportService(Framework framework, EndpointDescription e) {

		if (!e.getConfigurationTypes()
			.contains(RSAConstants.DISTRIBUTION_CONFIGURATION_TYPE)) {
			LOG.info("Unable to import the endpoint {} because it uses unsupported configuration types {}",
					e, e.getConfigurationTypes());
			return null;
		}

		Map<String, Object> endpointProperties = e.getProperties();

		String target = config.endpoint_import_target();
		if(!target.isEmpty()) {
			try {
				if(!FrameworkUtil.createFilter(target).match(new Hashtable<>(endpointProperties))) {
					LOG.debug("The endpointDescription {} is excluded by the configured import filter {} and will not be exported", e, target);
					return null;
				}
			} catch (InvalidSyntaxException ex) {
				LOG.error("Unable to filter the export operation due to a filter syntax error.", ex);
				return new ImportRegistrationImpl(e, framework, this, ex);
			}
		}

		ExportedServiceConfig edConfig;
		try {
			edConfig = Converters.standardConverter().convert(
			    		endpointProperties).to(ExportedServiceConfig.class);
		} catch (Exception ex) {
			LOG.error("A failure occurred trying to import endpoint {}", e, ex);
			return null;
		}

        Set<String> unsupported = Stream.concat(
        		ofNullable(edConfig.service_exported_intents())
            	.map(Arrays::stream)
            	.orElse(Stream.empty()),
            ofNullable(edConfig.service_exported_intents_extra())
            	.map(Arrays::stream)
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
			reg = new ImportRegistrationImpl(e, framework, proxyHostContext, this,
					clientConnectionManager, config.client_default_timeout(),
					clientWorkers, timer);

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

	/**
	 * Remove a registered ImportRegistration. Must not be called while holding
	 * a lock on the ImportRegistration
	 * @param importRegistration
	 * @param endpointId
	 */
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
				importRegistration.getEndpointDescription(), importRegistration.internalGetException());
	}

	/**
	 * Notify of a failed ImportRegistration. Must not be called while holding
	 * a lock on the ImportRegistration
	 * @param importRegistration
	 * @param endpointId
	 */
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

		List<Set<ExportRegistrationImpl>> toShow = factory.getRemoteServiceAdmins().stream()
			.flatMap(impl -> impl.localGetExportedServices(framework).stream())
			.collect(toList());

		return doGetExportedServices(framework, toShow.stream());
	}

	private List<Set<ExportRegistrationImpl>> localGetExportedServices(Framework framework) {
		List<Set<ExportRegistrationImpl>> toShow;
		synchronized (exports) {
			toShow = ofNullable(exports.get(framework))
				.map(m -> m.values().stream()
						.collect(toList()))
				.orElse(emptyList());
		}
		return toShow;
	}

	@Override
	public Collection<ExportReference> getAllExportedServices() {

		Map<Framework, Set<ExportRegistrationImpl>> toShow = factory.getRemoteServiceAdmins().stream()
			.map(RemoteServiceAdminImpl::localGetAllExportedServices)
			.reduce(new HashMap<>(), (a,b) -> {
					Map<Framework, Set<ExportRegistrationImpl>> toReturn = new HashMap<>(a);

					b.entrySet().stream()
						.forEach(e -> toReturn.compute(e.getKey(),
								(k,v) -> Stream.concat(v.stream(), e.getValue().stream()).collect(toSet())));

					return toReturn;
				});

		return toShow.entrySet().stream()
				.map(e -> doGetExportedServices(e.getKey(), Stream.of(e.getValue())))
				.flatMap(Collection::stream)
				.collect(toSet());
	}

	private Map<Framework, Set<ExportRegistrationImpl>> localGetAllExportedServices() {
		Map<Framework,Set<ExportRegistrationImpl>> toShow;
		synchronized (exports) {
			toShow = exports.entrySet().stream()
					.collect(toMap(Entry::getKey, e -> e.getValue().values().stream()
							.flatMap(Set::stream)
							.collect(toSet())));
		}
		return toShow;
	}

	private Collection<ExportReference> doGetExportedServices(Framework framework, Stream<Set<ExportRegistrationImpl>> stream) {

		Predicate<ExportReference> securityCheck;

		SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
        	String fwId = doPrivGetFrameworkId(framework);
        	securityCheck = er -> {
        			try {
        				sm.checkPermission(new EndpointPermission(er.getExportedEndpoint(),
        					fwId, EndpointPermission.READ));
        				return true;
        			} catch (SecurityException se) {
        				return false;
        			}
        		};
        } else {
        	securityCheck = er -> true;
        }

		return stream.flatMap(Set::stream)
			.filter(i -> i.getState() == OPEN)
			.map(ExportRegistration::getExportReference)
			.filter(er -> er != null)
			.filter(securityCheck)
			.collect(toSet());
	}

	@Override
	public Collection<ImportReference> getImportedEndpoints() {
		return getImportedEndpoints(defaultFramework);
	}

	@Override
	public Collection<ImportReference> getImportedEndpoints(Framework framework) {

		List<Set<ImportRegistrationImpl>> toShow = factory.getRemoteServiceAdmins().stream()
				.flatMap(impl -> impl.localGetImportedServices(framework).stream())
				.collect(toList());

		return doGetImportedServices(framework, toShow.stream());
	}

	private List<Set<ImportRegistrationImpl>> localGetImportedServices(Framework framework) {
		List<Set<ImportRegistrationImpl>> toShow;
		synchronized (imports) {
			toShow = ofNullable(imports.get(framework))
				.map(m -> m.values().stream()
						.collect(toList()))
				.orElse(emptyList());
		}
		return toShow;
	}

	@Override
	public Collection<ImportReference> getAllImportedEndpoints() {

		Map<Framework, Set<ImportRegistrationImpl>> toShow = factory.getRemoteServiceAdmins().stream()
				.map(RemoteServiceAdminImpl::localGetAllImportedEndpoints)
				.reduce(new HashMap<>(), (a,b) -> {
						Map<Framework, Set<ImportRegistrationImpl>> toReturn = new HashMap<>(a);

						b.entrySet().stream()
							.forEach(e -> toReturn.compute(e.getKey(),
									(k,v) -> Stream.concat(v.stream(), e.getValue().stream()).collect(toSet())));

						return toReturn;
					});

		return toShow.entrySet().stream()
				.map(e -> doGetImportedServices(e.getKey(), Stream.of(e.getValue())))
				.flatMap(Collection::stream)
				.collect(toSet());
	}

	private Map<Framework, Set<ImportRegistrationImpl>> localGetAllImportedEndpoints() {
		Map<Framework,Set<ImportRegistrationImpl>> toShow;
		synchronized (imports) {
			toShow = imports.entrySet().stream()
					.collect(toMap(Entry::getKey, e -> e.getValue().values().stream()
							.flatMap(Set::stream)
							.collect(toSet())));
		}
		return toShow;
	}

	private Collection<ImportReference> doGetImportedServices(Framework framework, Stream<Set<ImportRegistrationImpl>> stream) {

		Predicate<ImportReference> securityCheck;

		SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
        	String fwId = doPrivGetFrameworkId(framework);
        	securityCheck = ir -> {
        			try {
        				sm.checkPermission(new EndpointPermission(ir.getImportedEndpoint(),
        					fwId, EndpointPermission.READ));
        				return true;
        			} catch (SecurityException se) {
        				return false;
        			}
        		};
        } else {
        	securityCheck = er -> true;
        }

		return stream.flatMap(Set::stream)
				.filter(i -> i.getState() == OPEN)
				.map(ImportRegistration::getImportReference)
				.filter(i -> i != null)
				.filter(securityCheck)
				.collect(toSet());
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
        Map<String, String> lowerKeys = new HashMap<>(serviceProperties.size());
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

	void close() {
		Set<ImportRegistration> importsToClose;
		synchronized (imports) {
			importsToClose = imports.values().stream()
				.flatMap(m -> m.values().stream())
				.flatMap(Set::stream)
				.collect(toSet());
		}

		importsToClose.stream()
			.forEach(ImportRegistration::close);

		Set<ExportRegistration> exportsToClose;
		synchronized (exports) {
			exportsToClose = exports.values().stream()
				.flatMap(m -> m.values().stream())
				.flatMap(Set::stream)
				.collect(toSet());
		}

		exportsToClose.stream()
			.forEach(ExportRegistration::close);
	}
}
