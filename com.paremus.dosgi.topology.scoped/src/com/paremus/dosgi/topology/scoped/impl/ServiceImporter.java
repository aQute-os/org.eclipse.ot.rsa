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

import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_GLOBAL;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_TARGETTED;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_UNIVERSAL;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_TARGETTED_ATTRIBUTE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_TARGETTED_EXTRA_ATTRIBUTE;
import static com.paremus.dosgi.topology.scoped.activator.Activator.getUUID;
import static com.paremus.dosgi.topology.scoped.activator.TopologyManagerConstants.SCOPE_FRAMEWORK_PROPERTY;
import static java.util.Collections.disjoint;
import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.ADDED;
import static org.osgi.service.remoteserviceadmin.EndpointEvent.REMOVED;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.ENDPOINT_FRAMEWORK_UUID;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.topology.scoped.IsolationAwareRemoteServiceAdmin;

/**
 * A coordinator between discovery and transport bundles for service imports. Acts as
 * {@link ListenerHook} to the local framework to intercept service lookups and maintains
 * "import interests" that are subsequently matched to imported remote services.
 */
@SuppressWarnings("deprecation")
public class ServiceImporter {
	
	private class FrameworkEELServiceFactory implements ServiceFactory<Object> {

		private final Framework fw;
		
		public FrameworkEELServiceFactory(Framework fw) {
			this.fw = fw;
		}

		@Override
		public Object getService(Bundle bundle, ServiceRegistration<Object> registration) {
			return new EndpointListenerService(fw, bundle);
		}
		
		@Override
		public void ungetService(Bundle bundle, ServiceRegistration<Object> registration,
				Object service) {
			releaseListener(fw, bundle);
		}
	}

	private class EndpointListenerService implements EndpointEventListener, EndpointListener {
				
		private final Framework fw;
		private final Bundle client;
		
		private final AtomicReference<ListenerType> typeWatcher = new AtomicReference<>();
		
		public EndpointListenerService(Framework fw, Bundle client) {
			this.fw = fw;
			this.client = client;
		}

		@Override
		public void endpointChanged(EndpointEvent event, String filter) {
			checkEventListener();
			handleEvent(fw, client, event, filter);
		}

		private void checkEventListener() {
			if(typeWatcher.updateAndGet(old -> old == null ? ListenerType.EVENT_LISTENER : old) 
					!= ListenerType.EVENT_LISTENER) {
				throw new IllegalStateException("An RSA 1.1 EndpointEventListener must not be "
							+ "called in addition to an EndpointListener from the same bundle");
			}
		}

		@Override
		public void endpointRemoved(EndpointDescription endpoint,
				String matchedFilter) {
			checkListener();
			handleEvent(fw, client, new EndpointEvent(REMOVED, endpoint), matchedFilter);
		}
				
		@Override
		public void endpointAdded(EndpointDescription endpoint, String matchedFilter) {
			checkListener();
			handleEvent(fw, client, new EndpointEvent(ADDED, endpoint), matchedFilter);
		}
				
		private void checkListener() {
			if(typeWatcher.updateAndGet(old -> old == null ? ListenerType.LISTENER : old) 
					!= ListenerType.LISTENER) {
				throw new IllegalStateException("An RSA 1.1 EndpointListener must not be "
						+ "called in addition to an EndpointEventListener from the same bundle");
			}
		}
		
	}
	
	private static enum ListenerType {
		LISTENER, EVENT_LISTENER;
	}
	
	private static final String ROOT_SCOPE = "<<ROOT>>";

	private static final Logger logger = LoggerFactory.getLogger(ServiceImporter.class);
	
	private final Framework rootFramework;
	
	private final ConcurrentMap<Framework, String> originScopes = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Framework, Set<String>> extraScopes = new ConcurrentHashMap<>();

	private final ConcurrentMap<EndpointDescription, ConcurrentMap<Framework, Set<Bundle>>> endpoints = 
			new ConcurrentHashMap<>();
	
	private final ConcurrentMap<ImportRegistration, RemoteServiceAdmin> importsToRSA = new ConcurrentHashMap<>();

	private final ConcurrentMap<Framework, Set<RemoteServiceAdmin>> isolatedRSAs = new ConcurrentHashMap<>();
	
	private final Set<IsolationAwareRemoteServiceAdmin> isolationAwareRSAs = new CopyOnWriteArraySet<>();
	
	private final ConcurrentMap<RemoteServiceAdmin, ConcurrentMap<EndpointDescription, ImportRegistration>> 
		importedEndpointsByRSA = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<Framework, ConcurrentMap<EndpointDescription, Set<ImportRegistration>>> 
		importedEndpointsByFramework = new ConcurrentHashMap<>();
	
	private final ConcurrentMap<IsolationAwareRemoteServiceAdmin, ConcurrentMap<Framework,
		ConcurrentMap<EndpointDescription, ImportRegistration>>> importedEndpointsByIsolatingRSA = 
			new ConcurrentHashMap<>();
	
	/**
	 * Set a discard policy, as once we're closed it doesn't really matter what work we're
	 * asked to do. We have no state and won't add any.
	 */
	private final ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(1, r -> {
					Thread t = new Thread(r, "RSA Topology manager import worker");
					t.setDaemon(true);
					return t;
				}, new ThreadPoolExecutor.DiscardPolicy());
	
	public ServiceImporter(Framework rootFramework) {
		this.rootFramework = rootFramework;
		originScopes.put(rootFramework, ROOT_SCOPE);
		extraScopes.put(rootFramework, getExtraScopes(rootFramework));
		
		worker.scheduleWithFixedDelay(this::checkImports, 10, 10, TimeUnit.SECONDS);
	}

	private Set<String> getExtraScopes(Framework framework) {
		return ofNullable(framework.getBundleContext())
			.map(bc -> bc.getProperty(SCOPE_FRAMEWORK_PROPERTY))
			.map(s -> s.split(","))
			.map(Stream::of)
			.map(s -> s.collect(toSet()))
			.orElse(Collections.emptySet());
	}
	
	private void checkImports() {
		try {
			importedEndpointsByFramework.entrySet().stream()
				.forEach(e1 -> e1.getValue().entrySet().stream()
					.forEach(e2 -> {
						Set<ImportRegistration> regs = e2.getValue();
						
						Set<ImportRegistration> deadRegs = regs.stream()
								.filter(ir -> ir.getException() != null || ir.getImportReference() == null)
								.collect(toSet());
						
						deadRegs.stream().forEach(ir -> {
							RemoteServiceAdmin rsa = importsToRSA.remove(ir);
							
							Throwable t = ir.getException();
							if(t != null) {
								logger.warn("An ImportRegistration for endpoint {} and Remote Service Admin {} failed. Clearing it up.", 
										new Object[] {e2.getKey(), rsa, t});
							}
							ofNullable(importedEndpointsByFramework.get(e1.getKey()))
								.map(m -> m.get(e2.getKey()))
								.ifPresent(s -> s.remove(ir));
							
							if(rsa != null) {
								ofNullable(importedEndpointsByRSA.get(rsa))
									.ifPresent(m -> m.remove(e2.getKey(), ir));
								
								ofNullable(importedEndpointsByIsolatingRSA.get(rsa))
									.map(m -> m.get(e1.getKey()))
									.ifPresent(m -> m.remove(e2.getKey(), ir));
							}
						});
						
						//Re-export to try to re-create anything that was lost
						importEndpoint(e2.getKey(), e1.getKey());
					}));
		} catch (Exception e) {
			logger.error("There was a problem in the RSA topology manager import maintenance task", e);
		}
	}
	
	public void destroy() {
		if(logger.isDebugEnabled()) {
			logger.debug("Shutting down RSA Topology Manager imports");
		}
		
		worker.shutdown();
		try {
			if(!worker.awaitTermination(3, TimeUnit.SECONDS)) {
				worker.shutdownNow();
			}
		} catch (InterruptedException e) {
			logger.warn("An error occurred while shutting down the RSA Topoolgy Manager imports", e);
		}
		originScopes.clear();
		extraScopes.clear();
		endpoints.clear();
		isolatedRSAs.clear();
		isolationAwareRSAs.clear();
		importedEndpointsByRSA.clear();
		importedEndpointsByFramework.clear();
		importedEndpointsByIsolatingRSA.clear();
		
		importsToRSA.keySet().stream().forEach(ImportRegistration::close);
		importsToRSA.clear();
	}
	
	public ServiceFactory<Object> getServiceFactory(Framework f) {
		return new FrameworkEELServiceFactory(f);
	}
	
	public void registerScope(Framework framework, String name) {
		worker.execute(() -> asyncRegisterScope(framework, name));
	}

	private void asyncRegisterScope(Framework framework, String name) {
		String old = originScopes.put(framework, name);
		Set<String> newScopes = getExtraScopes(framework);
		Set<String> oldScopes = extraScopes.put(framework, newScopes);
		if(old != null) {
			logger.info("Moving framework from scope {} with addtional scopes {} to scope {} with additional scopes {}", 
					new Object[]{old, oldScopes, name, newScopes});
			
			//Close the endpoints that are no longer in scope
			ofNullable(importedEndpointsByFramework.get(framework))
				.ifPresent(m -> m.keySet().stream()
						.filter(e -> !inScope(framework, e))
						.collect(toSet())
						.stream()
						.forEach(e -> destroyImports(e, framework)));
		} else if(logger.isDebugEnabled()){
			logger.debug("Registering framework {} with scope {}", 
					new Object[] {getUUID(framework), name});
		}
		
		Map<EndpointDescription, Set<ImportRegistration>> imported = importedEndpointsByFramework
				.computeIfAbsent(framework, k -> new ConcurrentHashMap<>());
		endpoints.keySet().stream()
			.filter(e -> !imported.containsKey(e))
			.filter(e -> inScope(framework, e))
			.forEach(e -> importEndpoint(e, framework));
	}

	private boolean inScope(Framework target, EndpointDescription ed) {
		return ofNullable(endpoints.get(ed))
				.map(m2 -> m2.keySet().stream()
						.anyMatch(src -> inScope(src, target, ed)))
				.orElse(false);
	}
	
	private boolean inScope(Framework eventSource, Framework target, EndpointDescription ed) {
		Map<String,Object> endpointProps = ed.getProperties();
		
		Set<String> scopesOfTarget = getFrameworkScopes(target);
		
		if(logger.isDebugEnabled()) {
			logger.debug("Checking scope match for endpoint {}, announced from {}, into {} with scopes {}",
					new Object[]{ed.getId(), getUUID(eventSource), getUUID(target), scopesOfTarget});
		}
		final boolean inScope;
		if(!rootFramework.equals(eventSource)) {
			//This event came from a child framework, which means a scoped third-party discovery
			inScope = checkThirdPartyDiscovery(eventSource, target, endpointProps, scopesOfTarget);
		} else {
			//Came from root discovery and importing into a scoped framework
			
			//Do not import something that we exported!
			if(!rootFramework.equals(target) && getUUID(target).equals(
					endpointProps.get(ENDPOINT_FRAMEWORK_UUID))) {
				return false;
			}
			
			//It came from elsewhere, although possibly in the same JVM. The scope
			//should be UNIVERSAL, GLOBAL, OR SYSTEM with the right value - default is GLOBAL
			if(logger.isDebugEnabled()) {
				logger.debug("The endpoint was announced from the root framework, but is targetting a child framework",
						new Object[]{ed.getId(), getUUID(eventSource), getUUID(target)});
			}			
			switch(String.valueOf(endpointProps.getOrDefault(PAREMUS_SCOPES_ATTRIBUTE, PAREMUS_SCOPE_GLOBAL))) {
				case PAREMUS_SCOPE_GLOBAL :
				case PAREMUS_SCOPE_UNIVERSAL :
					inScope = true;
					break;
				case PAREMUS_SCOPE_TARGETTED :
					//Targetted means that the target framework shares one or more scopes with 
					//either the Endpoint targets
					inScope = !(disjoint(getOrDefault(endpointProps, PAREMUS_TARGETTED_ATTRIBUTE, 
							emptySet()), scopesOfTarget) && disjoint(getOrDefault(endpointProps, 
							PAREMUS_TARGETTED_EXTRA_ATTRIBUTE, emptySet()), scopesOfTarget));
					break;
				default :
					inScope = false;
			}
		}
		if(logger.isDebugEnabled()) {
			if(inScope) {
				logger.debug("The endpoint {} should be imported into {}",
					ed.getId(), getUUID(target));
			} else {
				logger.debug("The endpoint {} should not be imported into {}",
					ed.getId(), getUUID(target));
			}
		}
		return inScope;
	}

	private Set<String> getFrameworkScopes(Framework target) {
		Set<String> scopesOfTarget = concat(of(originScopes.get(target)), 
							ofNullable(extraScopes.get(target))
								.map(Set::stream)
								.orElse(Stream.empty()))
				.filter(s -> s != null)
				.collect(toSet());
		return scopesOfTarget;
	}

	private boolean checkThirdPartyDiscovery(Framework eventSource,
			Framework target, Map<String, Object> endpointProps, Set<String> scopesOfTarget) {
		if(endpointProps.containsKey(PAREMUS_SCOPES_ATTRIBUTE)) {
			//This was sent by us, or deliberately scoped
			if(logger.isDebugEnabled()) {
				logger.debug("The endpoint {} has scope {}",
						new Object[]{endpointProps.get(RemoteConstants.ENDPOINT_ID), 
							endpointProps.get(PAREMUS_SCOPES_ATTRIBUTE)});
			}
			switch(String.valueOf(endpointProps.get(PAREMUS_SCOPES_ATTRIBUTE))) {
				case PAREMUS_SCOPE_GLOBAL:
				case PAREMUS_SCOPE_UNIVERSAL:
					//Ignore clusters as they bypassed gossip-based discovery
					return true;
				case PAREMUS_SCOPE_TARGETTED :
					//Targetted means that the target framework shares one or more scopes with 
					//either the Endpoint targets, or the discovering framework if the property is unset
					return !(disjoint(getOrDefault(endpointProps, PAREMUS_TARGETTED_ATTRIBUTE, 
							emptySet()), scopesOfTarget) && disjoint(getOrDefault(endpointProps, 
							PAREMUS_TARGETTED_EXTRA_ATTRIBUTE, emptySet()), scopesOfTarget));
				default :
					// Unknown scoping has been provided, so keep the import to the framework that discovered it
					return eventSource.equals(target);
			}
			
		} else {
			// No scoping has been provided, so keep the import to the framework that discovered it
			if(logger.isDebugEnabled()) {
				logger.debug("The endpoint {} has no scope, and will only be imported in the announcing framework {}",
						new Object[]{endpointProps.get(RemoteConstants.ENDPOINT_ID), getUUID(eventSource)});
			}
			return eventSource.equals(target);
		}
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
	
	private void destroyImports(EndpointDescription ed, Framework f) {
		if(logger.isDebugEnabled()) {
			logger.debug("The endpoint {} is being withdrawn from {}",
					new Object[]{ed.getId(), getUUID(f)});
		}
		ofNullable(importedEndpointsByFramework.get(f))
			.map(m -> m.remove(ed))
			.ifPresent(s -> s.stream()
					.forEach(ir -> {
						//Clear the import to RSA link and rsa to endpoint link
						RemoteServiceAdmin rsa = importsToRSA.remove(ir);
						if(rsa != null) {
							ofNullable(importedEndpointsByRSA.get(rsa))
								.ifPresent(m -> m.remove(ed));
							ofNullable(importedEndpointsByIsolatingRSA.get(rsa))
								.map(m -> m.get(f))
								.ifPresent(m -> m.remove(ed));
						}
						ir.close();
					}));
	}
	
	private void importEndpoint(EndpointDescription e, Framework framework) {
		if(logger.isDebugEnabled()) {
			logger.debug("Importing endpoint {} into framework {}",
					e.getId(), getUUID(framework));
		}
		
		ofNullable(isolatedRSAs.get(framework))
			.ifPresent(s -> s.stream().forEach(r -> {
				ConcurrentMap<EndpointDescription, ImportRegistration> imports = 
						importedEndpointsByRSA.computeIfAbsent(r, k -> new ConcurrentHashMap<>());
				if(!imports.containsKey(e)) {
					doImport(() -> r.importService(e), framework, e, r, imports);
				} else if(logger.isDebugEnabled()) {
					logger.debug("The endpoint {} is already imported into framework {} by RSA {} ",
							new Object[] {e.getId(), getUUID(framework), r});
				}
			}));
		
		isolationAwareRSAs.stream().forEach(r -> {
			ConcurrentMap<EndpointDescription, ImportRegistration> imports = 
					importedEndpointsByIsolatingRSA.computeIfAbsent(r, k -> new ConcurrentHashMap<>())
						.computeIfAbsent(framework, k -> new ConcurrentHashMap<>());
				if(!imports.containsKey(e)) {
					doImport(() -> r.importService(framework, e), framework, e, r, imports);
				} else if(logger.isDebugEnabled()) {
					logger.debug("The endpoint {} is already imported into framework {} by RSA {} ",
							new Object[] {e.getId(), getUUID(framework), r});
				}
			});
	}

	private <T extends RemoteServiceAdmin> void doImport(Supplier<ImportRegistration> source, 
			Framework framework, EndpointDescription e, T rsa, 
			ConcurrentMap<EndpointDescription, ImportRegistration> importsByRSA) {
		
		ImportRegistration ir;
		try {
			ir = source.get();
		} catch (Exception ex) {
			logger.error("Unable to import endpoint {} into framework {} using RSA {} because {}", 
					new Object[] {e.getId(), getUUID(framework), rsa, ex, ex});
			return;
		}
		
		if (ir != null) {
			if(logger.isDebugEnabled()) {
				logger.debug("The endpoint {} has been imported into {} using RSA {}",
						new Object[]{e.getId(), getUUID(framework), rsa});
			}
            // The RSA can handle it, but might not have been successful
			// If ir.getException() != null then we'll retry again in the maintenance
			// task. Sooner or later this will establish a connection (fingers crossed)
			importsToRSA.put(ir, rsa);
			importsByRSA.put(e, ir);
			importedEndpointsByFramework.computeIfAbsent(framework, k -> new ConcurrentHashMap<>())
					.compute(e, (k2, v) -> {
						Set<ImportRegistration> regs = (v == null) ? new HashSet<>() : new HashSet<>(v);
						regs.add(ir);
						return regs;
					});
        } else if(logger.isDebugEnabled()) {
			logger.debug("The endpoint {} is not supported by RSA {}",
					new Object[] {e.getId(), rsa});
        }
	}

	public void unregisterScope(Framework framework, String name) {
		worker.execute(() -> asyncUnregisterScope(framework, name));
	}

	private void asyncUnregisterScope(Framework framework, String name) {
		if(originScopes.remove(framework, name)) {
			extraScopes.remove(framework);
			logger.debug("Removing framework with scope {}", name);
			
			//Close the endpoints that are no longer in scope
			ofNullable(importedEndpointsByFramework.get(framework))
				.ifPresent(m -> m.keySet().stream()
						.forEach(e -> destroyImports(e, framework)));
			importedEndpointsByFramework.remove(framework);
			
			ofNullable(isolatedRSAs.remove(framework))
				.ifPresent(rsas -> rsas.stream().forEach(rsa -> importedEndpointsByRSA.remove(rsa)));
			
			endpoints.replaceAll((k, v) -> {
				ConcurrentMap<Framework, Set<Bundle>> map = new ConcurrentHashMap<>(v);
				map.remove(framework);
				return map;
			});
			endpoints.values().removeIf(Map::isEmpty);
			
		} else {
			String scope = originScopes.get(framework);
			if(scope == null) {
				logger.warn("There was no scope associated with the framework");
			} else {
				logger.warn("The framework was associated with the scope {} not {}", 
						new Object[] {scope, name});
			}
		}
	}

	public void addingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		worker.execute(() -> asyncAddingRSA(rsa));
	}
	
	private void asyncAddingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		if(isolationAwareRSAs.add(rsa)) {
			if(logger.isDebugEnabled()) {
				logger.debug("Discovered a new isolation aware RemoteServiceAdmin {}", rsa);
			}
			
			endpoints.keySet().stream()
				.forEach(e -> originScopes.keySet().stream()
						.filter(target -> inScope(target, e))
						.forEach(target -> doImport(() -> rsa.importService(target, e), target, e, rsa, 
								importedEndpointsByIsolatingRSA.computeIfAbsent(rsa, k -> new ConcurrentHashMap<>())
											.computeIfAbsent(target, k -> new ConcurrentHashMap<>()))));
		}
	}

	public void removingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		worker.execute(() -> asyncRemovingRSA(rsa));
	}
	
	private void asyncRemovingRSA(IsolationAwareRemoteServiceAdmin rsa) {
		if(isolationAwareRSAs.remove(rsa)) {
			if(logger.isDebugEnabled()) {
				logger.debug("The isolation aware RemoteServiceAdmin {} is being unregistered", rsa);
			}
			ofNullable(importedEndpointsByIsolatingRSA.remove(rsa))
				.ifPresent(m -> m.entrySet().stream().forEach(e -> 
						e.getValue().entrySet().stream().forEach(e2 -> {
							ImportRegistration ir = e2.getValue();
							importsToRSA.remove(ir);
							ofNullable(importedEndpointsByFramework.get(e.getKey()))
								.ifPresent(m2 -> m2.computeIfPresent(e2.getKey(), (k, s) -> {
									Set<ImportRegistration> s2 = new HashSet<>(s);
									s2.remove(ir);
									return s2.isEmpty() ? null : s2;
								}));
							ir.close();
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
				if(logger.isDebugEnabled()) {
					logger.debug("Discovered a new RemoteServiceAdmin {} in framework {}", 
							new Object[] {rsa, getUUID(framework)});
				}
				endpoints.keySet().stream()
						.filter(e -> inScope(framework, e))
						.forEach(e -> doImport(() -> rsa.importService(e), framework, e, rsa, 
								importedEndpointsByRSA.computeIfAbsent(rsa, r -> new ConcurrentHashMap<>())));
			}
			return s;
		});
	}

	public void removingRSA(Framework framework, RemoteServiceAdmin rsa) {
		worker.execute(() -> asyncRemovingRSA(framework, rsa));
	}
	
	private void asyncRemovingRSA(Framework framework, RemoteServiceAdmin rsa) {
		isolatedRSAs.computeIfPresent(framework, (k, v) -> {
				if(logger.isDebugEnabled()) {
					logger.debug("The RemoteServiceAdmin {} in framework {} is being unregistered", 
							new Object[] {rsa, getUUID(framework)});
				}
				Set<RemoteServiceAdmin> s = new HashSet<>(v);
				s.remove(rsa);
				return s.isEmpty() ? null : s;
			});
		
		ofNullable(importedEndpointsByRSA.remove(rsa))
			.ifPresent(m -> m.entrySet().stream().forEach(e -> {
				ImportRegistration ir = e.getValue();
				importsToRSA.remove(ir);
				ofNullable(importedEndpointsByFramework.get(e.getKey()))
					.ifPresent(m2 -> m2.computeIfPresent(e.getKey(), (k, s) -> {
						Set<ImportRegistration> s2 = new HashSet<>(s);
						s2.remove(ir);
						return s2.isEmpty() ? null : s2;
					}));
				ir.close();
			}));

	}
	
	private void handleEvent(Framework framework, Bundle bundle, EndpointEvent e, String filter) {
		switch(e.getType()) {
			case EndpointEvent.ADDED :
				worker.execute(() -> incomingEndpoint(framework, bundle, e.getEndpoint()));
				break;
			case EndpointEvent.REMOVED :
			case EndpointEvent.MODIFIED_ENDMATCH :
				worker.execute(() -> departingEndpoint(framework, bundle, e.getEndpoint()));
				break;
			case EndpointEvent.MODIFIED :
				worker.execute(() -> modifiedEndpoint(framework, bundle, e.getEndpoint()));
				break;
		}
	}
	
	public void releaseListener(Framework f, Bundle bundle) {
		try {
			worker.execute(() -> removeSponsor(f, bundle));
		} catch (RejectedExecutionException ree) {
			// This isn't a problem, it just means that our listener is already closed
		}
	}
	
	private void removeSponsor(Framework f, Bundle bundle) {
		endpoints.entrySet().stream()
			.filter(e -> e.getValue().containsKey(f))
			.filter(e -> e.getValue().get(f).contains(bundle))
			.map(Entry::getKey)
			.collect(toSet())
			.stream()
			.forEach(ed -> departingEndpoint(f, bundle, ed));
	}

	private void incomingEndpoint(Framework source, Bundle sponsor, EndpointDescription ed) {
		ConcurrentMap<Framework, Set<Bundle>> sponsors = endpoints
				.computeIfAbsent(ed, k -> new ConcurrentHashMap<>());
		
		Set<Bundle> sb = sponsors.computeIfAbsent(source, k -> new HashSet<>());
		boolean newAddForThisScope = sb.isEmpty();
		sb.add(sponsor);
		if(newAddForThisScope) {
			if(logger.isDebugEnabled()) {
				logger.debug("Discovered an endpoint {} from framework {}", 
						new Object[] {ed.getId(), getUUID(source)});
			}
			originScopes.keySet().stream()
				.filter(fw -> inScope(source, fw, ed))
				.forEach(fw -> importEndpoint(ed, fw));
		}
	}
	
	private void modifiedEndpoint(Framework source, Bundle sponsor, EndpointDescription ed) {
		if(endpoints.containsKey(ed)) {
			if(logger.isDebugEnabled()) {
				logger.debug("Modified an endpoint {} from framework {}", 
						new Object[] {ed.getId(), getUUID(source)});
			}
			//Destroy imports for frameworks that are now out of scope
			originScopes.keySet().stream()
				.filter(fw -> importedEndpointsByFramework
						.getOrDefault(fw, new ConcurrentHashMap<>()).containsKey(ed))
				.filter(fw -> !inScope(source, fw, ed))
				.forEach(fw -> destroyImports(ed, fw));
			
			//We have to replace the key because it has the same identity
			//but different internal properties!
			endpoints.put(ed, endpoints.remove(ed));
			
			importedEndpointsByFramework.values().stream()
				.filter(m -> m.containsKey(ed))
				.forEach(m -> m.put(ed, m.remove(ed)));
	
			importedEndpointsByRSA.values().stream()
				.filter(m -> m.containsKey(ed))
				.forEach(m -> m.put(ed, m.remove(ed)));
			
			importedEndpointsByIsolatingRSA.values().forEach(m -> 
				m.values().stream()
					.filter(m2 -> m2.containsKey(ed))
					.forEach(m2 -> m2.put(ed, m2.remove(ed))));
			
			//Update 
			importedEndpointsByFramework.values().stream()
				.filter(m -> m.containsKey(ed))
				.map(m -> m.get(ed))
				.forEach(s -> s.forEach(ir -> ir.update(ed)));
			
			//Handle expanded scope
			originScopes.keySet().stream()
				.filter(fw -> inScope(source, fw, ed))
				.filter(fw -> !importedEndpointsByFramework.computeIfAbsent(fw, k -> new ConcurrentHashMap<>()).containsKey(ed))
				.forEach(fw -> importEndpoint(ed, fw));
			
		}
		//This will sort out the sponsoring if this is a new framework source
		incomingEndpoint(source, sponsor, ed);
	}
	
	private void departingEndpoint(Framework source, Bundle sponsor, EndpointDescription ed) {
		
		Map<Framework, Set<Bundle>> m = endpoints.get(ed);
		
		if(m != null) {
			
			m.computeIfPresent(source, (k, v) -> {
				Set<Bundle> sb = new HashSet<>(v);
				sb.remove(sponsor);
				return sb.isEmpty() ? null : sb;
			});
			
			if(m.isEmpty()) {
				if(logger.isDebugEnabled()) {
					logger.debug("Revoking an endpoint {} from framework {}", 
							new Object[] {ed.getId(), getUUID(source)});
				}
				//Time to withdraw the endpoint
				endpoints.remove(ed);
				
				importedEndpointsByFramework.entrySet().stream()
					.filter(e -> e.getValue().containsKey(ed))
					.forEach(e -> destroyImports(ed, e.getKey()));
			}
		}
	}
}