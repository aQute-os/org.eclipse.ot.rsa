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
package org.eclipse.ot.rsa.topology.promiscuous.simple.impl;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPE_GLOBAL;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPE_TARGETTED;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPE_UNIVERSAL;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_TARGETTED_ATTRIBUTE;
import static org.osgi.framework.Constants.SERVICE_ID;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.eclipse.ot.rsa.topology.promiscuous.common.EndpointEventListenerInterest;
import org.osgi.framework.ServiceReference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.converter.Converters;
import org.osgi.util.converter.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ServiceExporter {

	private static final Logger logger = LoggerFactory.getLogger(ServiceExporter.class);

	private final Set<ServiceReference<?>> services = new HashSet<>();

	private final ConcurrentMap<EndpointEventListener, EndpointEventListenerInterest> listeners =
			new ConcurrentHashMap<>();

	private final Set<RemoteServiceAdmin> rsas = new HashSet<>();

	private final ConcurrentMap<ExportRegistration, RemoteServiceAdmin> exportsToRSA = new ConcurrentHashMap<>();

	private final ConcurrentMap<ExportRegistration, EndpointDescription> exportsToAdvertisedEndpoint = new ConcurrentHashMap<>();

	private final ConcurrentMap<RemoteServiceAdmin, ConcurrentMap<ServiceReference<?>,
	Collection<ExportRegistration>>> exportedEndpointsByRSA = new ConcurrentHashMap<>();

	private final ConcurrentMap<ServiceReference<?>, Set<ExportRegistration>>
		exportedEndpoints= new ConcurrentHashMap<>();

	private final Set<String> scopes = new LinkedHashSet<>();

	public ServiceExporter(String[] localScopes) {
		scopes.addAll(Arrays.asList(localScopes));
	}

	public void checkExports() {

		exportedEndpoints.entrySet().stream()
			.forEach(e -> {
						Set<ExportRegistration> regs = e.getValue();

						Set<ExportRegistration> deadRegs = regs.stream()
								.filter(er -> er.getException() != null)
								.collect(toSet());

						regs.removeAll(deadRegs);

						deadRegs.stream().forEach(er -> {
							notify(er);
							RemoteServiceAdmin rsa = exportsToRSA.remove(er);
							exportsToAdvertisedEndpoint.remove(er);

							ofNullable(exportedEndpoints.get(e.getKey()))
								.ifPresent(s -> s.remove(er));

							if(rsa != null) {
								ofNullable(exportedEndpointsByRSA.get(rsa))
									.map(m -> m.get(e.getKey()))
									.ifPresent(s -> s.remove(er));
							}
						});

						//Re-export to try to re-create anything that was lost
						exportEndpoint(e.getKey());
					});
	}

	private void exportEndpoint(ServiceReference<?> ref) {

		if(logger.isDebugEnabled()) {
			logger.debug("Service {} is being registered for export",
					ref.getProperty(SERVICE_ID));
		}
		rsas.stream().forEach(r -> {
				ConcurrentMap<ServiceReference<?>, Collection<ExportRegistration>> exports =
						exportedEndpointsByRSA.computeIfAbsent(r, k -> new ConcurrentHashMap<>());
				if(!exports.containsKey(ref)) {
					doExport(() -> r.exportService(ref, getExtraProps(ref)), ref, r, exports);
				} else if(logger.isDebugEnabled()) {
					logger.debug("The service {} is already exported by RSA {} ",
							new Object[] {ref.getProperty(SERVICE_ID), r});
				}
			});
	}

	private <T extends RemoteServiceAdmin> void doExport(Supplier<Collection<ExportRegistration>> source,
			ServiceReference<?> s, T rsa, ConcurrentMap<ServiceReference<?>,
			Collection<ExportRegistration>> exportsByRSA) {

		Collection<ExportRegistration> ers;
		try {
			ers = source.get();
		} catch (Exception e) {
			logger.error("Unable to export service {} using RSA {} because {}",
					new Object[] {s.getProperty(SERVICE_ID), rsa, e});
			return;
		}

		if (!ers.isEmpty()) {
			if(logger.isDebugEnabled()) {
				logger.debug("Exported service {} using RSA {}",
						new Object[] {s.getProperty(SERVICE_ID), rsa});
			}
            // The RSA can handle it, but might not have been successful
			// If er.getException() != null then we'll retry again in the maintenance
			// task. Sooner or later this will establish a connection (fingers crossed)
			ers.stream().forEach(er -> exportsToRSA.put(er, rsa));
			exportsByRSA.put(s, new HashSet<>(ers));
			Set<ExportRegistration> set = exportedEndpoints
					.computeIfAbsent(s, k2 -> new HashSet<>());

			ers.stream().forEach(er -> {
				//Do not readvertise things we have already seen - update will handle if necessary
				if(set.add(er)) {
					notify(er);
				}
			});
        } else if(logger.isDebugEnabled()) {
			logger.debug("The service {} is not supported by RSA {}",
					new Object[] {s.getProperty(SERVICE_ID), rsa});
        }
	}

	private void destroyExports(ServiceReference<?> ref) {
		ofNullable(exportedEndpoints.remove(ref))
			.ifPresent(s -> s.stream()
					.forEach(er -> {

						//Clear the import to RSA link and rsa to endpoint link
						RemoteServiceAdmin rsa = exportsToRSA.remove(er);
						if(rsa != null) {
							ofNullable(exportedEndpointsByRSA.get(rsa))
								.ifPresent(m -> m.remove(ref));
						}
						er.close();
						notify(er);
					}));
	}

	public void addingRSA(RemoteServiceAdmin rsa) {
		if(rsas.add(rsa)) {
			services.stream().forEach(svc -> doExport(() -> rsa.exportService(svc, getExtraProps(svc)),
					svc, rsa, exportedEndpointsByRSA.computeIfAbsent(rsa,
							x -> new ConcurrentHashMap<>())));
		}
	}

	private Map<String, Object> getExtraProps(ServiceReference<?> ref) {
		Map<String, Object> extraProps = new HashMap<>();

		Object targetScope = ref.getProperty(PAREMUS_SCOPES_ATTRIBUTE);

		if(targetScope == null) {
			extraProps.put(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_GLOBAL);
			Object targetScopes = ref.getProperty(PAREMUS_TARGETTED_ATTRIBUTE);

			if(targetScopes != null) {
				logger.warn("The service {} specifies target scopes {}, but is not using the targetted scope. This service will be made targetted}",
						new Object[] {ref.getProperty(SERVICE_ID), targetScopes});
				extraProps.put(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_TARGETTED);
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("The service {} has no scope information, and so will be advertised at global scope",
							new Object[] {ref.getProperty(SERVICE_ID)});
				}
			}
		} else if (PAREMUS_SCOPE_TARGETTED.equals(targetScope)) {
			Collection<String> scopes = Converters.standardConverter()
					.convert(ref.getProperty(PAREMUS_TARGETTED_ATTRIBUTE))
					.to(new TypeReference<List<String>>() {});
			if (scopes.isEmpty()) {
				if(!this.scopes.isEmpty()) {
					Set<String> targetScopes = new LinkedHashSet<>(this.scopes);
					logger.warn("The service {} is using the targetted scope, but specifies no targets. The target scopes will be overridden by {}",
							new Object[] {ref.getProperty(SERVICE_ID), targetScopes});
					extraProps.put(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_TARGETTED);
					extraProps.put(PAREMUS_TARGETTED_ATTRIBUTE, targetScopes);
				} else {
					logger.warn("The service {} is using the targetted scope, but specifies no targets. There are no scopes for this topology manager so the service will be advertised at global scope",
							new Object[] {ref.getProperty(SERVICE_ID)});
					extraProps.put(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_GLOBAL);
				}
			}
		} else {
			//Check declared scope is valid
			String stringifiedTargetScope = String.valueOf(targetScope);
			switch (stringifiedTargetScope) {
				case PAREMUS_SCOPE_UNIVERSAL:
				case PAREMUS_SCOPE_GLOBAL:
				case PAREMUS_SCOPE_TARGETTED:
					break;
				default : {
					logger.warn("The service {} has an unknown scope {}. This service is unlikely to be visible in remote frameworks",
							new Object[] {ref.getProperty(SERVICE_ID), stringifiedTargetScope});
				}
			}
		}

		return extraProps;
	}

	public void removingRSA(RemoteServiceAdmin rsa) {
		rsas.remove(rsa);

		ofNullable(exportedEndpointsByRSA.remove(rsa))
			.ifPresent(m -> m.entrySet().stream().forEach(e -> {
				ServiceReference<?> ref = e.getKey();
				Collection<ExportRegistration> ers = e.getValue();
				ers.stream().forEach(er -> {
					exportsToRSA.remove(er);
					ofNullable(exportedEndpoints.computeIfPresent(ref, (k, s) -> {
							Set<ExportRegistration> s2 = new HashSet<>(s);
							s2.remove(er);
							return s2.isEmpty() ? null : s2;
						}));

					er.close();
					notify(er);
				});
			}));

	}

	public void addingEEL(EndpointEventListener eel,
			ServiceReference<?> ref, List<String> filters) {

		EndpointEventListenerInterest interest = new EndpointEventListenerInterest(eel, ref, filters);
		listeners.put(eel, interest);

		exportedEndpoints.values().stream().forEach(ers -> ers.stream()
				.forEach(er -> ofNullable(exportsToAdvertisedEndpoint.get(er))
					.ifPresent(e -> interest.notify(null, e))));
	}

	public void updatedEEL(EndpointEventListener eel,
			ServiceReference<?> ref, List<String> filters) {
		EndpointEventListenerInterest interest = listeners.compute(eel, (k,v) -> {
					EndpointEventListenerInterest i;
					if(v != null) {
						i = v;
						i.updateFilters(filters);
					} else {
						i = new EndpointEventListenerInterest(eel, ref, filters);
					}
					return i;
				});

		exportsToAdvertisedEndpoint.values().stream()
			.filter(e -> e != null)
			.forEach(e -> interest.notify(null, e));
	}

	public void removingEEL(EndpointEventListener eel) {
		listeners.remove(eel);
	}

	public void exportService(ServiceReference<?> ref) {

		if(services.add(ref)) {
			if(logger.isDebugEnabled()) {
				logger.debug("A new service {} is being exported",
						new Object[] {ref.getProperty(SERVICE_ID)});
			}
			exportEndpoint(ref);
		} else {
			updateExportedService(ref);
		}
	}

	public void updateExportedService(ServiceReference<?> ref) {

		if(services.contains(ref)) {

			if(logger.isDebugEnabled()) {
				logger.debug("The exported service {} is being modified",
						new Object[] {ref.getProperty(SERVICE_ID)});
			}

			// Update
			Map<String, Object> extraProps = getExtraProps(ref);

			ofNullable(exportedEndpoints.get(ref))
				.ifPresent(ers -> ers.stream()
						.forEach(er -> {
							er.update(extraProps);
							notify(er);
						}));

			// Handle potentially increased number of acceptable RSAs
			exportEndpoint(ref);
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("The service {} had not previously been exported, but is being presented as an update. Attempting to export it",
						new Object[] {ref.getProperty(SERVICE_ID)});
			}
			exportService(ref);
		}
	}

	public void removeExportedService(ServiceReference<?> ref) {

		if(services.remove(ref)) {
			if(logger.isDebugEnabled()) {
				logger.debug("The exported service {} has been removed",
						new Object[] {ref.getProperty(SERVICE_ID)});
			}
			//Time to withdraw the endpoint
			destroyExports(ref);
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("The service {} is being removed from export, but is not known to this topology manager",
						new Object[] {ref.getProperty(SERVICE_ID)});
			}
		}
	}

	private void notify(ExportRegistration er) {
		EndpointDescription after = ofNullable(er.getExportReference())
				.map(ExportReference::getExportedEndpoint)
				.orElse(null);
		EndpointDescription before = (after == null) ? exportsToAdvertisedEndpoint.remove(er) : exportsToAdvertisedEndpoint.put(er, after);
		doNotify(er, before, after);
	}

	private void doNotify(ExportRegistration er,
			EndpointDescription before, EndpointDescription after) {
		if(logger.isDebugEnabled()) {
			logger.debug("Notifying Endpoint Event listeners of a state change for endpoint {}",
					new Object[] {before == null ? after.getId() : before.getId()});
		}

		listeners.values().forEach(l -> l.notify(before, after));
	}

	public void destroy() {
		if(logger.isDebugEnabled()) {
			logger.debug("Shutting down RSA Topology Manager exports");
		}

		services.clear();
		rsas.clear();
		exportedEndpointsByRSA.clear();
		exportedEndpoints.clear();

		exportsToRSA.keySet().stream().forEach(ExportRegistration::close);
		exportsToRSA.clear();
	}

	public void updateScopes(String[] local_scopes) {
		Set<String> oldScopes = new HashSet<>(scopes);

		scopes.clear();
		scopes.addAll(Arrays.asList(local_scopes));

		logger.info("Updating from scopes {} to scopes {}",
				new Object[]{oldScopes, scopes});

		// Update the endpoints to pick up potential scope changes
		exportedEndpoints.keySet().stream()
				.forEach(this::updateExportedService);
	}
}
