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
package com.paremus.dosgi.topology.simple.impl;

import static java.util.Collections.disjoint;
import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPE_GLOBAL;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPE_TARGETTED;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_SCOPE_UNIVERSAL;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_TARGETTED_ATTRIBUTE;
import static org.eclipse.ot.rsa.multrsa.api.Constants.PAREMUS_TARGETTED_EXTRA_ATTRIBUTE;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import org.osgi.framework.Bundle;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A coordinator between discovery and transport bundles for service imports.
 */
public class ServiceImporter {

	private static final Logger logger = LoggerFactory.getLogger(ServiceImporter.class);

	private final ConcurrentMap<EndpointDescription, Set<Bundle>> endpoints =
			new ConcurrentHashMap<>();

	private final ConcurrentMap<ImportRegistration, RemoteServiceAdmin> importsToRSA = new ConcurrentHashMap<>();

	private final Set<RemoteServiceAdmin> rsas = new HashSet<>();

	private final ConcurrentMap<RemoteServiceAdmin, ConcurrentMap<EndpointDescription, ImportRegistration>>
		importedEndpointsByRSA = new ConcurrentHashMap<>();

	private final ConcurrentMap<EndpointDescription, Set<ImportRegistration>>
		importedEndpoints = new ConcurrentHashMap<>();

	private final Set<String> scopes = new LinkedHashSet<>();

	public ServiceImporter(String[] localScopes) {
		scopes.addAll(Arrays.asList(localScopes));
	}

	public void checkImports() {
		try {
			importedEndpoints.entrySet().stream()
					.forEach(e -> {
						Set<ImportRegistration> regs = e.getValue();

						Set<ImportRegistration> deadRegs = regs.stream()
								.filter(ir -> ir.getException() != null || ir.getImportReference() == null)
								.collect(toSet());

						deadRegs.stream().forEach(ir -> {
							RemoteServiceAdmin rsa = importsToRSA.remove(ir);

							Throwable t = ir.getException();
							if(t != null) {
								logger.warn("An ImportRegistration for endpoint {} and Remote Service Admin {} failed. Clearing it up.",
										e.getKey(), rsa, t);
							}
							ofNullable(importedEndpoints.get(e.getKey()))
								.ifPresent(s -> s.remove(ir));

							if(rsa != null) {
								ofNullable(importedEndpointsByRSA.get(rsa))
									.ifPresent(m -> m.remove(e.getKey(), ir));
							}
						});

						//Re-export to try to re-create anything that was lost
						importEndpoint(e.getKey());
					});
		} catch (Exception e) {
			logger.error("There was a problem in the RSA topology manager import maintenance task", e);
		}
	}

	public void destroy() {
		if(logger.isDebugEnabled()) {
			logger.debug("Shutting down RSA Topology Manager imports");
		}

		endpoints.clear();
		rsas.clear();
		importedEndpointsByRSA.clear();
		importedEndpoints.clear();

		importsToRSA.keySet().stream().forEach(ImportRegistration::close);
		importsToRSA.clear();
	}

	private boolean inScope(EndpointDescription ed) {
		Map<String,Object> endpointProps = ed.getProperties();

		boolean inScope;

		switch(String.valueOf(endpointProps.getOrDefault(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_GLOBAL))) {
			case PAREMUS_SCOPE_GLOBAL :
			case PAREMUS_SCOPE_UNIVERSAL :
				inScope = true;
				break;
			case PAREMUS_SCOPE_TARGETTED :
				//Targetted means that the target framework shares one or more scopes with
				//either the Endpoint targets
				inScope = !(disjoint(getOrDefault(endpointProps, PAREMUS_TARGETTED_ATTRIBUTE,
						emptySet()), scopes) && disjoint(getOrDefault(endpointProps,
						PAREMUS_TARGETTED_EXTRA_ATTRIBUTE, emptySet()), scopes));

				if(inScope) {
					logger.debug("The targetted endpoint {} will be imported into this framework");
				} else {
					logger.debug("The targetted endpoint {} does not match the scope of this framework");
				}
				break;
			default :
				inScope = false;
		}

		return inScope;
	}

	private Collection<String> getOrDefault(Map<String, Object> map, String key, Object defaultValue) {
		Object o = map.getOrDefault(key, defaultValue);
		if(o instanceof String) {
			return Collections.singleton(o.toString());
		} else if (o instanceof Collection) {
			return ((Collection<?>) o).stream()
					.filter(x -> x != null)
					.map(Object::toString)
					.collect(toSet());
		} else if (o instanceof String[]) {
			return Arrays.stream((String[]) o)
					.filter(x -> x != null)
					.collect(toSet());
		}
		return Collections.singleton(String.valueOf(o));
	}

	private void destroyImports(EndpointDescription ed) {
		if(logger.isDebugEnabled()) {
			logger.debug("The endpoint {} from framework is being withdrawn",
					new Object[]{ed.getId(), ed.getFrameworkUUID()});
		}
		ofNullable(importedEndpoints.get(ed))
			.ifPresent(s -> s.stream()
					.forEach(ir -> {
						//Clear the import to RSA link and rsa to endpoint link
						RemoteServiceAdmin rsa = importsToRSA.remove(ir);
						if(rsa != null) {
							ofNullable(importedEndpointsByRSA.get(rsa))
								.ifPresent(m -> m.remove(ed));
						}
						ir.close();
					}));
	}

	private void importEndpoint(EndpointDescription e) {
		if(logger.isDebugEnabled()) {
			logger.debug("Importing endpoint {} from framework {}",
					e.getId(), e.getFrameworkUUID());
		}

		rsas.stream().forEach(r -> {
				ConcurrentMap<EndpointDescription, ImportRegistration> imports =
						importedEndpointsByRSA.computeIfAbsent(r, k -> new ConcurrentHashMap<>());
				if(!imports.containsKey(e)) {
					doImport(() -> r.importService(e), e, r, imports);
				} else if(logger.isDebugEnabled()) {
					logger.debug("The endpoint {} from framework is already imported by RSA {} ",
							new Object[] {e.getId(), e.getFrameworkUUID(), r});
				}
			});
	}

	private <T extends RemoteServiceAdmin> void doImport(Supplier<ImportRegistration> source,
			EndpointDescription e, T rsa, ConcurrentMap<EndpointDescription, ImportRegistration> importsByRSA) {

		ImportRegistration ir;
		try {
			ir = source.get();
		} catch (Exception ex) {
			logger.error("Unable to import endpoint {} from framework {} using RSA {} because {}",
					e.getId(), e.getFrameworkUUID(), rsa, ex, ex);
			return;
		}

		if (ir != null) {
			if(logger.isDebugEnabled()) {
				logger.debug("The endpoint {} from framework {} has been imported using RSA {}",
						new Object[]{e.getId(), e.getFrameworkUUID(), rsa});
			}
            // The RSA can handle it, but might not have been successful
			// If ir.getException() != null then we'll retry again in the maintenance
			// task. Sooner or later this will establish a connection (fingers crossed)
			importsToRSA.put(ir, rsa);
			importsByRSA.put(e, ir);
			importedEndpoints.compute(e, (k2, v) -> {
						Set<ImportRegistration> regs = (v == null) ? new HashSet<>() : new HashSet<>(v);
						regs.add(ir);
						return regs;
					});
        } else if(logger.isDebugEnabled()) {
			logger.debug("The endpoint {} is not supported by RSA {}",
					new Object[] {e.getId(), rsa});
        }
	}

	public void addingRSA(RemoteServiceAdmin rsa) {

		if(rsas.add(rsa)) {
			if(logger.isDebugEnabled()) {
				logger.debug("Discovered a new RemoteServiceAdmin {}",
						new Object[] {rsa});
			}
			endpoints.keySet().stream()
					.filter(e -> inScope(e))
					.forEach(e -> doImport(() -> rsa.importService(e), e, rsa,
							importedEndpointsByRSA.computeIfAbsent(rsa, r -> new ConcurrentHashMap<>())));
		}

	}

	public void removingRSA(RemoteServiceAdmin rsa) {

		if(logger.isDebugEnabled()) {
			logger.debug("The RemoteServiceAdmin {} is being unregistered",
					new Object[] {rsa});
		}

		rsas.remove(rsa);

		ofNullable(importedEndpointsByRSA.remove(rsa))
			.ifPresent(m -> m.entrySet().stream().forEach(e -> {
				ImportRegistration ir = e.getValue();
				importsToRSA.remove(ir);
				ofNullable(importedEndpoints.computeIfPresent(e.getKey(), (k, s) -> {
						Set<ImportRegistration> s2 = new HashSet<>(s);
						s2.remove(ir);
						return s2.isEmpty() ? null : s2;
					}));
				ir.close();
			}));
	}

	public void releaseListener(Bundle bundle) {
		try {
			removeSponsor(bundle);
		} catch (RejectedExecutionException ree) {
			// This isn't a problem, it just means that our listener is already closed
		}
	}

	private void removeSponsor(Bundle bundle) {
		endpoints.entrySet().stream()
			.filter(e -> e.getValue().contains(bundle))
			.map(Entry::getKey)
			.collect(toSet())
			.stream()
			.forEach(ed -> departingEndpoint(bundle, ed));
	}

	public void incomingEndpoint(Bundle sponsor, EndpointDescription ed) {
		Set<Bundle> sponsors = endpoints
				.computeIfAbsent(ed, k -> new HashSet<>());
		boolean newAddForThisScope = sponsors.isEmpty();
		sponsors.add(sponsor);
		if(newAddForThisScope) {
			if(logger.isDebugEnabled()) {
				logger.debug("Discovered an endpoint {} from framework {}",
						new Object[] {ed.getId(), ed.getFrameworkUUID()});
			}

			if(inScope(ed)) {
				importEndpoint(ed);
			}
		}
	}

	public void modifiedEndpoint(Bundle sponsor, EndpointDescription ed) {
		if(endpoints.containsKey(ed)) {
			if(logger.isDebugEnabled()) {
				logger.debug("Modified an endpoint {} from framework {}",
						new Object[] {ed.getId(), ed.getFrameworkUUID()});
			}
			//Destroy imports for frameworks that are now out of scope
			if(!inScope(ed) && importedEndpoints.containsKey(ed)) {
				destroyImports(ed);
			}

			//We have to replace the key because it has the same identity
			//but different internal properties!
			endpoints.put(ed, endpoints.remove(ed));
			if(importedEndpoints.containsKey(ed)) {
				importedEndpoints.put(ed, importedEndpoints.remove(ed));
			}
			importedEndpointsByRSA.values().stream()
				.filter(m -> m.containsKey(ed))
				.forEach(m -> m.put(ed, m.remove(ed)));

			//Update
			importedEndpoints.getOrDefault(ed, emptySet())
				.forEach(ir -> ir.update(ed));

			//Handle expanded scope
			if(inScope(ed) && !importedEndpoints.containsKey(ed)) {
				importEndpoint(ed);
			}
		}
		//This will sort out the sponsoring
		incomingEndpoint(sponsor, ed);
	}

	public void departingEndpoint(Bundle sponsor, EndpointDescription ed) {

		Set<Bundle> m = endpoints.computeIfPresent(ed, (k, v) -> {
				Set<Bundle> sb = new HashSet<>(v);
				sb.remove(sponsor);
				return sb.isEmpty() ? null : sb;
			});

		if(m == null) {
			if(logger.isDebugEnabled()) {
				logger.debug("Revoking an endpoint {} from framework {}",
						new Object[] {ed.getId(), ed.getFrameworkUUID()});
			}
			destroyImports(ed);
		}
	}

	public void updateScopes(String[] local_scopes) {
		Set<String> oldScopes = new HashSet<>(scopes);

		scopes.clear();
		scopes.addAll(Arrays.asList(local_scopes));

		logger.info("Updating from scopes {} to scopes {}",
				new Object[]{oldScopes, scopes});

		//Close the endpoints that are no longer in scope
		importedEndpoints.keySet().stream()
			.filter(e -> !inScope(e))
			.collect(toSet())
			.stream()
			.forEach(e -> destroyImports(e));

		endpoints.keySet().stream()
			.filter(e -> !importedEndpoints.containsKey(e))
			.filter(e -> inScope(e))
			.forEach(e -> importEndpoint(e));
	}
}
