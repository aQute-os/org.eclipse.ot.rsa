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
package com.paremus.dosgi.topology.scoped.impl;

import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_ORIGIN_ROOT;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_ORIGIN_SCOPE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_GLOBAL;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_TARGETTED;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_UNIVERSAL;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_TARGETTED_ATTRIBUTE;
import static com.paremus.dosgi.topology.scoped.activator.Activator.getUUID;
import static com.paremus.dosgi.topology.scoped.activator.TopologyManagerConstants.SCOPE_FRAMEWORK_PROPERTY;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.osgi.framework.Constants.SERVICE_ID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.topology.scoped.IsolationAwareRemoteServiceAdmin;


public class ServiceExporter {

	private static final String ROOT_SCOPE = "<<ROOT>>";

	private static final Logger logger = LoggerFactory.getLogger(ServiceExporter.class);
	
	private final Framework rootFramework;
	
	private final ConcurrentMap<Framework, String> originScopes = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Framework, Set<String>> extraScopes = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Framework, Set<ServiceReference<?>>> services = 
			new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Framework, ConcurrentMap<EndpointEventListener, EndpointEventListenerInterest>> listeners = 
			new ConcurrentHashMap<>();
	
	private final ConcurrentMap<ExportRegistration, RemoteServiceAdmin> exportsToRSA = new ConcurrentHashMap<>();

	private final ConcurrentMap<ExportRegistration, EndpointDescription> exportsToAdvertisedEndpoint = new ConcurrentHashMap<>();

	private final ConcurrentMap<Framework, Set<RemoteServiceAdmin>> isolatedRSAs = new ConcurrentHashMap<>();
	
	private final Set<IsolationAwareRemoteServiceAdmin> isolationAwareRSAs = new CopyOnWriteArraySet<>();
	
	private final ConcurrentMap<RemoteServiceAdmin, ConcurrentMap<ServiceReference<?>,
	Collection<ExportRegistration>>> exportedEndpointsByRSA = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Framework, ConcurrentMap<ServiceReference<?>, Set<ExportRegistration>>> 
		exportedEndpointsByFramework = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<IsolationAwareRemoteServiceAdmin, ConcurrentMap<Framework,
		ConcurrentMap<ServiceReference<?>, Collection<ExportRegistration>>>> exportedEndpointsByIsolatingRSA = 
			new ConcurrentHashMap<>();
	
	/**
	 * Set a discard policy, as once we're closed it doesn't really matter what work we're
	 * asked to do. We have no state and won't add any.
	 */
	private final ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(1, r -> {
					Thread t = new Thread(r, "RSA Topology manager export worker");
					t.setDaemon(true);
					return t;
				}, new ThreadPoolExecutor.DiscardPolicy());

	/**
	 * Set a discard policy, as once we're closed it doesn't really matter what work we're
	 * asked to do. We have no state and won't add any.
	 */
	private final ExecutorService notificationWorker = new ThreadPoolExecutor(1, 1, 
			0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
						Thread t = new Thread(r, "RSA Topology manager notification worker");
						t.setDaemon(true);
						return t;
					}, new ThreadPoolExecutor.DiscardPolicy());
	
	public ServiceExporter(Framework rootFramework) {
		this.rootFramework = rootFramework;
		originScopes.put(rootFramework, ROOT_SCOPE);
		extraScopes.put(rootFramework, getExtraScopes(rootFramework));
		
		worker.scheduleWithFixedDelay(this::checkExports, 10, 10, TimeUnit.SECONDS);
	}

	private Set<String> getExtraScopes(Framework framework) {
		return ofNullable(framework.getBundleContext())
			.map(bc -> bc.getProperty(SCOPE_FRAMEWORK_PROPERTY))
			.map(s -> s.split(","))
			.map(Stream::of)
			.map(s -> s.map(String::trim).collect(toSet()))
			.orElse(Collections.emptySet());
	}

	private void checkExports() {
		
		exportedEndpointsByFramework.entrySet().stream()
			.forEach(e1 -> e1.getValue().entrySet().stream()
					.forEach(e2 -> {
						Set<ExportRegistration> regs = e2.getValue();
						
						Set<ExportRegistration> deadRegs = regs.stream()
								.filter(er -> er.getException() != null)
								.collect(toSet());
						
						regs.removeAll(deadRegs);
						
						deadRegs.stream().forEach(er -> {
							notify(e1.getKey(), er);
							RemoteServiceAdmin rsa = exportsToRSA.remove(er);
							exportsToAdvertisedEndpoint.remove(er);
							
							ofNullable(exportedEndpointsByFramework.get(e1.getKey()))
								.map(m -> m.get(e2.getKey()))
								.ifPresent(s -> s.remove(er));
							
							if(rsa != null) {
								ofNullable(exportedEndpointsByRSA.get(rsa))
									.map(m -> m.get(e2.getKey()))
									.ifPresent(s -> s.remove(er));
								
								ofNullable(exportedEndpointsByIsolatingRSA.get(rsa))
									.map(m -> m.get(e1.getKey()))
									.map(m -> m.get(e2.getKey()))
									.ifPresent(s -> s.remove(er));
							}
						});
						
						//Re-export to try to re-create anything that was lost
						exportEndpoint(e2.getKey(), e1.getKey());
					}));
	}
	
	public void registerScope(Framework framework, String name) {
		worker.execute(() -> asyncRegisterScope(framework, name));
	}

	private void asyncRegisterScope(Framework framework, String name) {
		String old = originScopes.put(framework, name);
		Set<String> newScopes = getExtraScopes(framework);
		Set<String> oldScopes = extraScopes.put(framework, newScopes);
		if(old != null) {
			logger.info("Moving framework from scope {} with addtional scopes to scope {} with additional scopes {}", 
					new Object[]{old, oldScopes, name, newScopes});
			
			//Modify the endpoints in the new framework so that they pick up their new scope
			ofNullable(exportedEndpointsByFramework.get(framework))
				.ifPresent(m -> m.keySet().stream()
						.forEach(e -> asyncModifiedService(framework, e)));
		}
	}

	public void unregisterScope(Framework framework, String name) {
		worker.execute(() -> asyncUnregisterScope(framework, name));
	}

	private void asyncUnregisterScope(Framework framework, String name) {
		if(originScopes.remove(framework, name)) {
			Set<String> extras = extraScopes.remove(framework);
			logger.info("Removing framework with origin scope {} and extra scopes {}", name, extras);
			
			//Revoke the endpoints that are no longer in scope
			ofNullable(exportedEndpointsByFramework.get(framework))
				.ifPresent(m -> m.keySet().stream()
						.forEach(e -> destroyExports(e, framework)));
			exportedEndpointsByFramework.remove(framework);
			
			ofNullable(isolatedRSAs.remove(framework))
				.ifPresent(rsas -> rsas.stream().forEach(rsa -> exportedEndpointsByRSA.remove(rsa)));
			
			services.remove(framework);
			listeners.remove(framework);
		} else {
			String scope = originScopes.get(framework);
			if(scope == null) {
				logger.info("There was no scope associated with the framework");
			} else {
				logger.info("The framework was associated with the scope {} not {}", 
						new Object[] {scope, name});
			}
		}
	}
	
	public void addingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		worker.execute(() -> asyncAddingRSA(rsa));
	}
	
	private void asyncAddingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		if(isolationAwareRSAs.add(rsa)) {
			services.entrySet().stream()
				.forEach(e -> {
					Framework fw = e.getKey();
					e.getValue().stream()
						.forEach(s -> doExport(() -> rsa.exportService(fw, s, getExtraProps(s, fw)), 
								fw, s, rsa, exportedEndpointsByIsolatingRSA
									.computeIfAbsent(rsa, k -> new ConcurrentHashMap<>())
									.computeIfAbsent(fw, k -> new ConcurrentHashMap<>())));
				});
		}
	}

	private void exportEndpoint(ServiceReference<?> ref, Framework framework) {
		
		if(logger.isDebugEnabled()) {
			logger.debug("Exporting service {} from framework {}",
					ref.getProperty(SERVICE_ID), getUUID(framework));
		}
		ofNullable(isolatedRSAs.get(framework))
			.ifPresent(s -> s.stream().forEach(r -> {
				ConcurrentMap<ServiceReference<?>, Collection<ExportRegistration>> exports = 
						exportedEndpointsByRSA.computeIfAbsent(r, k -> new ConcurrentHashMap<>());
				if(!exports.containsKey(ref)) {
					doExport(() -> r.exportService(ref, getExtraProps(ref, framework)), framework, ref, r, exports);
				} else if(logger.isDebugEnabled()) {
					logger.debug("The service {} from framework {} is already exported by RSA {} ",
							new Object[] {ref.getProperty(SERVICE_ID), getUUID(framework), r});
				}
			}));
		
		isolationAwareRSAs.stream().forEach(r -> {
			ConcurrentMap<ServiceReference<?>, Collection<ExportRegistration>> exports = 
					exportedEndpointsByIsolatingRSA.computeIfAbsent(r, k -> new ConcurrentHashMap<>())
						.computeIfAbsent(framework, k -> new ConcurrentHashMap<>());
				if(!exports.containsKey(ref)) {
					doExport(() -> r.exportService(framework, ref, getExtraProps(ref, framework)), 
							framework, ref, r, exports);
				} else if(logger.isDebugEnabled()) {
					logger.debug("The service {} from framework {} is already exported by RSA {} ",
							new Object[] {ref.getProperty(SERVICE_ID), getUUID(framework), r});
				}
			});
	}
	
	private <T extends RemoteServiceAdmin> void doExport(Supplier<Collection<ExportRegistration>> source, 
			Framework framework, ServiceReference<?> s, T rsa, 
			ConcurrentMap<ServiceReference<?>, Collection<ExportRegistration>> exportsByRSA) {
		
		Collection<ExportRegistration> ers;
		try {
			ers = source.get();
		} catch (Exception e) {
			logger.error("Unable to export service {} from framework {} using RSA {} because {}", 
					new Object[] {s.getProperty(SERVICE_ID), getUUID(framework), rsa, e});
			return;
		}
		
		if (!ers.isEmpty()) {
			if(logger.isDebugEnabled()) {
				logger.debug("Exported service {} from framework {} using RSA {}",
						new Object[] {s.getProperty(SERVICE_ID), getUUID(framework), rsa});
			}
            // The RSA can handle it, but might not have been successful
			// If er.getException() != null then we'll retry again in the maintenance
			// task. Sooner or later this will establish a connection (fingers crossed)
			ers.stream().forEach(er -> exportsToRSA.put(er, rsa));
			exportsByRSA.put(s, new HashSet<>(ers));
			Set<ExportRegistration> set = exportedEndpointsByFramework
					.computeIfAbsent(framework, k -> new ConcurrentHashMap<>())
					.computeIfAbsent(s, k2 -> new HashSet<>());
							
			ers.stream().forEach(er -> {
				//Do not readvertise things we have already seen - update will handle if necessary
				if(set.add(er)) {
					notify(framework, er);
				}
			});			
        } else if(logger.isDebugEnabled()) {
			logger.debug("The service {} from framework {} is not supported by RSA {}",
					new Object[] {s.getProperty(SERVICE_ID), getUUID(framework), rsa});
        }
	}

	private Map<String, Object> getExtraProps(ServiceReference<?> ref, Framework f) {
		Map<String, Object> extraProps = new HashMap<>();
		
		String fwId = getUUID(rootFramework);
		extraProps.put(PAREMUS_ORIGIN_ROOT, fwId);
		
		String originScope = originScopes.get(f);
		if(originScope != null) {
			extraProps.put(PAREMUS_ORIGIN_SCOPE, originScope);
		}
		
		Object targetScope = ref.getProperty(PAREMUS_SCOPES_ATTRIBUTE);
		
		if(targetScope == null) {
			if(originScope != null) {
				Set<String> scopesToTarget = getDefaultTargetScopes(f, originScope);
				extraProps.put(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_TARGETTED);
				extraProps.put(PAREMUS_TARGETTED_ATTRIBUTE, scopesToTarget);
				
				Object targetScopes = ref.getProperty(PAREMUS_TARGETTED_ATTRIBUTE);
				if(targetScopes != null) {
					logger.warn("The service {} from framework {} specifies target scopes {}, but is not using the targetted scope. The target scopes will be overridden by {}",
							new Object[] {ref, getUUID(f), targetScopes, originScope});
					extraProps.put(PAREMUS_TARGETTED_ATTRIBUTE, scopesToTarget);
				}
				if(logger.isDebugEnabled()) {
					logger.debug("The service {} from framework {} will be advertised at system scope {}",
							new Object[] {ref, getUUID(f), scopesToTarget});
				}
			} else {
				logger.warn("The service {} from framework {} has an unknown scope, and will be made global",
						new Object[] {ref, getUUID(f)});
				extraProps.put(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_GLOBAL);
			}
		} else if (PAREMUS_SCOPE_TARGETTED.equals(targetScope)) {
			Collection<String> scopes = toStringPlus(ref.getProperty(PAREMUS_TARGETTED_ATTRIBUTE));
			if (scopes.isEmpty() ) {
				Set<String> defaultTargetScopes = getDefaultTargetScopes(f, originScope);
				logger.warn("The service {} from framework {} is using the targetted scope, but specifies no targets. The target scopes will be overridden by {}",
						new Object[] {ref, getUUID(f), defaultTargetScopes});
				scopes = (originScope != null) ? defaultTargetScopes : Collections.emptySet();
				extraProps.put(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_TARGETTED);
				extraProps.put(PAREMUS_TARGETTED_ATTRIBUTE, scopes);
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
					logger.warn("The service {} from framework {} has an unknown scope {}. This service is unlikely to be visible in remote frameworks",
							new Object[] {ref, getUUID(f), stringifiedTargetScope});
				}
			}
		}
		
		return extraProps;
	}

	private static Collection<String> toStringPlus(Object o) {
		final Collection<String> result;
		if (o == null) {
			result = Collections.emptyList();
		} else if (o instanceof String) {
			result = Collections.singleton((String)o);
		} else if (o instanceof String[]) {
			result = Arrays.asList((String[]) o);
		} else if (o instanceof Collection) {
			if (((Collection<?>) o).stream().allMatch(e -> e instanceof String)) {
				@SuppressWarnings("unchecked")
				Collection<String> tmp = (Collection<String>) o;
				result = new ArrayList<>(tmp);
			} else {
				result = Collections.emptyList();
			}
		} else {
			result = Collections.emptyList();
		}
		return result;	
	}

	private Set<String> getDefaultTargetScopes(Framework f, String originScope) {
		Set<String> scopesToTarget = concat(of(originScope), extraScopes.get(f).stream()).collect(toSet());
		return scopesToTarget;
	}
	
	private void destroyExports(ServiceReference<?> ref, Framework f) {
		ofNullable(exportedEndpointsByFramework.get(f))
			.map(m -> m.remove(ref))
			.ifPresent(s -> s.stream()
					.forEach(er -> {
						
						//Clear the import to RSA link and rsa to endpoint link
						RemoteServiceAdmin rsa = exportsToRSA.remove(er);
						if(rsa != null) {
							ofNullable(exportedEndpointsByRSA.get(rsa))
								.ifPresent(m -> m.remove(ref));
							ofNullable(exportedEndpointsByIsolatingRSA.get(rsa))
								.map(m -> m.get(f))
								.ifPresent(m -> m.remove(ref));
						}
						er.close();
						notify(f, er);
					}));
	}
	
	public void removingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		worker.execute(() -> asyncRemovingRSA(rsa));
	}
	
	private void asyncRemovingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		if(isolationAwareRSAs.remove(rsa)) {
			ofNullable(exportedEndpointsByIsolatingRSA.remove(rsa))
				.ifPresent(m -> m.entrySet().stream().forEach(e -> 
						e.getValue().entrySet().stream().forEach(e2 -> {
							Collection<ExportRegistration> ers = e2.getValue();
							
							ers.stream().forEach(er -> {
								exportsToRSA.remove(er);
								ofNullable(exportedEndpointsByFramework.get(e.getKey()))
									.ifPresent(m2 -> m2.computeIfPresent(e2.getKey(), (k, s) -> {
										Set<ExportRegistration> s2 = new HashSet<>(s);
										s2.remove(er);
										return s2.isEmpty() ? null : s2;
									}));
								er.close();
								notify(rootFramework, er);
							});
						})));
		}
	}

	public void addingRSA(Framework framework, RemoteServiceAdmin rsa) {
		worker.execute(() -> asyncAddingRSA(framework, rsa));
	}
		
	private void asyncAddingRSA(Framework framework, RemoteServiceAdmin rsa) {
		isolatedRSAs.compute(framework, (k,v) -> {
			Set<RemoteServiceAdmin> s = (v == null) ? new HashSet<>() : new HashSet<>(v);
			if(s.add(rsa)) {
				ofNullable(services.get(framework))
					.ifPresent(services -> services.stream()
							.forEach(svc -> doExport(() -> rsa.exportService(svc, getExtraProps(svc, framework)), 
									framework, svc, rsa, exportedEndpointsByRSA.computeIfAbsent(rsa, 
											x -> new ConcurrentHashMap<>()))));
			}
			return s;
		});
	}

	public void removingRSA(Framework framework, RemoteServiceAdmin rsa) {
		worker.execute(() -> asyncRemovingRSA(framework, rsa));
	}
	
	private void asyncRemovingRSA(Framework framework, RemoteServiceAdmin rsa) {
		isolatedRSAs.computeIfPresent(framework, (k, v) -> {
				Set<RemoteServiceAdmin> s = new HashSet<>(v);
				s.remove(rsa);
				return s.isEmpty() ? null : s;
			});
		
		ofNullable(exportedEndpointsByRSA.remove(rsa))
			.ifPresent(m -> m.entrySet().stream().forEach(e -> {
				ServiceReference<?> ref = e.getKey();
				Collection<ExportRegistration> ers = e.getValue();
				ers.stream().forEach(er -> {
					exportsToRSA.remove(er);
					ofNullable(exportedEndpointsByFramework.get(ref))
						.ifPresent(m2 -> m2.computeIfPresent(ref, (k, s) -> {
							Set<ExportRegistration> s2 = new HashSet<>(s);
							s2.remove(er);
							return s2.isEmpty() ? null : s2;
						}));
					
					er.close();
					notify(framework, er);
				});
			}));

	}
	
	public void addingEEL(Framework framework, EndpointEventListener eel,
			ServiceReference<?> ref, Object filters) {
		worker.execute(() -> {
			EndpointEventListenerInterest interest = new EndpointEventListenerInterest(eel, ref, filters);
			listeners.computeIfAbsent(framework, k -> new ConcurrentHashMap<>())
				.put(eel, interest);
			
			Stream<ConcurrentMap<ServiceReference<?>, Set<ExportRegistration>>> stream = 
					(rootFramework.equals(framework)) ? exportedEndpointsByFramework.values().stream() : 
						ofNullable(exportedEndpointsByFramework.get(framework))
							.map(Stream::of)
							.orElse(Stream.empty());
			
			stream.forEach(m -> m.values().stream().forEach(ers -> ers.stream()
					.forEach(er -> ofNullable(exportsToAdvertisedEndpoint.get(er))
						.ifPresent(e -> notificationWorker.execute(() -> interest.notify(null, e))))));
		});
	}

	public void updatedEEL(Framework framework, EndpointEventListener eel,
			ServiceReference<?> ref, Object filters) {
		worker.execute(() -> {
			EndpointEventListenerInterest interest = listeners.computeIfAbsent(framework, 
					k -> new ConcurrentHashMap<>()).compute(eel, (k,v) -> {
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
				.forEach(e -> notificationWorker.execute(() -> interest.notify(null, e)));
		});
	}

	public void removingEEL(Framework framework, EndpointEventListener eel) {
		listeners.computeIfPresent(framework, (k,v) -> {
			v = new ConcurrentHashMap<>(v);
			v.remove(eel);
			return v.isEmpty() ? null : v;
		});
	}

	public void addingService(Framework framework,
			ServiceReference<Object> reference) {
		worker.execute(() -> asyncAddedService(framework, reference));
	}

	public void modifiedService(Framework framework,
			ServiceReference<Object> reference) {
		worker.execute(() -> asyncModifiedService(framework, reference));
	}

	public void removedService(Framework framework,
			ServiceReference<Object> reference) {
		worker.execute(() -> asyncRemovedService(framework, reference));
	}
	
	private void asyncAddedService(Framework source, ServiceReference<?> ref) {
		Set<ServiceReference<?>> known = services
				.computeIfAbsent(source, k -> new HashSet<>());
		
		if(known.add(ref)) {
			if(logger.isDebugEnabled()) {
				logger.debug("A new service {} is being exported from framework {}",
						new Object[] {ref.getProperty(SERVICE_ID), getUUID(rootFramework)});
			}
			exportEndpoint(ref, source);
		} else {
			asyncModifiedService(source, ref);
		}
	}
	
	private void asyncModifiedService(Framework source, ServiceReference<?> ref) {
		if(logger.isDebugEnabled()) {
			logger.debug("The service {} from framework {} is being modified",
					new Object[] {ref.getProperty(SERVICE_ID), getUUID(rootFramework)});
		}
		
		if(ofNullable(services.get(source))
				.map(m -> m.contains(ref))
				.orElse(false)) {
			
			//Update 
			ofNullable(exportedEndpointsByFramework.get(source))
				.map(m -> m.get(ref))
				.ifPresent(ers -> ers.stream()
						.forEach(er -> {
							er.update(getExtraProps(ref, source));
							notify(source, er);
						}));
			
			//Handle potentially expanded scope This will not readvertise
			exportEndpoint(ref, source);
		}
	}
	
	private void asyncRemovedService(Framework source, ServiceReference<?> ref) {
		if(logger.isDebugEnabled()) {
			logger.debug("The service {} from framework {} has been removed",
					new Object[] {ref.getProperty(SERVICE_ID), getUUID(rootFramework)});
		}
		
		Set<ServiceReference<?>> s = services.get(source);
			
		if(s != null && s.remove(ref)) {
			//Time to withdraw the endpoint
			destroyExports(ref, source);
			services.computeIfPresent(source, (f,s2) -> s2.isEmpty() ? null : s2);
		}
	}
	
	private void notify(Framework f, ExportRegistration er) {
		EndpointDescription after = ofNullable(er.getExportReference())
				.map(ExportReference::getExportedEndpoint)
				.orElse(null);
		EndpointDescription before = (after == null) ? exportsToAdvertisedEndpoint.remove(er) : exportsToAdvertisedEndpoint.put(er, after);
		doNotify(f, er, before, after);
		if(f != rootFramework) {
			doNotify(rootFramework, er, before, after);
		}
		
	}

	private void doNotify(Framework f, ExportRegistration er,
			EndpointDescription before, EndpointDescription after) {
		if(logger.isDebugEnabled()) {
			logger.debug("Notifying listeners in framework {} of a state change for endpoint {}",
					new Object[] {getUUID(rootFramework), before == null ? after.getId() : before.getId()});
		}
		
		ofNullable(listeners.get(f)).ifPresent(m -> m.values().forEach(
				l -> notificationWorker.execute(() -> 
					l.notify(before, after))));
	}

	public void destroy() {
		if(logger.isDebugEnabled()) {
			logger.debug("Shutting down RSA Topology Manager exports");
		}
		
		worker.shutdown();
		try {
			if(!worker.awaitTermination(3, TimeUnit.SECONDS)) {
				worker.shutdownNow();
			}
		} catch (InterruptedException e) {
			logger.warn("An error occurred while shutting down the RSA Topoolgy Manager imports", e);
		}
		
		notificationWorker.shutdown();
		try {
			if(!notificationWorker.awaitTermination(3, TimeUnit.SECONDS)) {
				notificationWorker.shutdownNow();
			}
		} catch (InterruptedException e) {
			logger.warn("An error occurred while shutting down the RSA Topoolgy Manager imports", e);
		}
		originScopes.clear();
		extraScopes.clear();
		services.clear();
		isolatedRSAs.clear();
		isolationAwareRSAs.clear();
		exportedEndpointsByRSA.clear();
		exportedEndpointsByFramework.clear();
		exportedEndpointsByIsolatingRSA.clear();
		
		exportsToRSA.keySet().stream().forEach(ExportRegistration::close);
		exportsToRSA.clear();
	}
}