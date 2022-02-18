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

import static java.util.Collections.emptyList;
import static org.osgi.framework.Constants.FRAMEWORK_UUID;
import static org.osgi.framework.FrameworkUtil.createFilter;
import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.remoteserviceadmin.EndpointEventListener.ENDPOINT_LISTENER_SCOPE;
import static org.osgi.service.remoteserviceadmin.namespace.TopologyNamespace.PROMISCUOUS_POLICY;
import static org.osgi.service.remoteserviceadmin.namespace.TopologyNamespace.TOPOLOGY_NAMESPACE;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.osgi.annotation.bundle.Capability;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.converter.Converter;
import org.osgi.util.converter.Converters;
import org.osgi.util.converter.TypeReference;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.topology.common.EndpointListenerAdapter;
import com.paremus.dosgi.topology.common.EndpointListenerService;

@Capability(namespace=TOPOLOGY_NAMESPACE, version="1.1.0", attribute="policy=paremus-scoped")
@Capability(namespace=TOPOLOGY_NAMESPACE, version="1.1.0", attribute="policy=" + PROMISCUOUS_POLICY)
@SuppressWarnings("deprecation")
@Designate(ocd=PromiscuousTopologyManager.Config.class)
@Component
public class PromiscuousTopologyManager {

	@ObjectClassDefinition
	public @interface Config {
		String[] local_scopes() default {};
	}

	private static final Logger logger = LoggerFactory.getLogger(ServiceExporter.class);

	private final Converter converter = Converters.standardConverter();

	private final AtomicBoolean startupFailed = new AtomicBoolean();

	private final CountDownLatch latch = new CountDownLatch(1);

	private volatile ScheduledExecutorService worker;

	private ServiceExporter exporter;
	private ServiceImporter importer;

	private ServiceRegistration<?> listener;

	private ServiceTracker<Object, Object> serviceTracker;

	@Reference(policy=ReferencePolicy.DYNAMIC, cardinality=MULTIPLE)
	void addRSA(RemoteServiceAdmin rsa) {
		runWithWaitForStart(() -> {
			exporter.addingRSA(rsa);
			importer.addingRSA(rsa);
		});
	}

	void removeRSA(RemoteServiceAdmin rsa) {
		runWithWaitForStart(() -> {
			exporter.removingRSA(rsa);
			importer.removingRSA(rsa);
		});
	}

	@Reference(policy=ReferencePolicy.DYNAMIC, cardinality=MULTIPLE)
	void addEventListener(EndpointEventListener listener, ServiceReference<EndpointEventListener> ref) {
		runWithWaitForStart(() -> {
				exporter.addingEEL(listener, ref, getFilters(ref));
			});
	}

	private List<String> getFilters(ServiceReference<EndpointEventListener> ref) {
		List<String> filters = converter.convert(ref.getProperty(ENDPOINT_LISTENER_SCOPE))
				.defaultValue(emptyList())
				.to(new TypeReference<List<String>>() {});
		return filters;
	}

	void updatedEventListener(EndpointEventListener listener, ServiceReference<EndpointEventListener> ref) {
		runWithWaitForStart(() -> {
			exporter.updatedEEL(listener, ref, getFilters(ref));
		});
	}

	void removeEventListener(EndpointEventListener listener, ServiceReference<EndpointEventListener> ref) {
		runWithWaitForStart(() -> {
			exporter.removingEEL(listener);
		});

	}

	@Reference(policy=ReferencePolicy.DYNAMIC, cardinality=MULTIPLE)
	void addListener(EndpointListener listener, ServiceReference<EndpointEventListener> ref) {
		addEventListener(new EndpointListenerAdapter(listener), ref);
	}

	void updatedListener(EndpointListener listener, ServiceReference<EndpointEventListener> ref) {
		updatedEventListener(new EndpointListenerAdapter(listener), ref);
	}

	void removeListener(EndpointListener listener, ServiceReference<EndpointEventListener> ref) {
		removeEventListener(new EndpointListenerAdapter(listener), ref);
	}

	@Activate
	void start(BundleContext context, Config config) throws InvalidSyntaxException {

		/**
		 * Set a discard policy, as once we're closed it doesn't really matter what work we're
		 * asked to do. We have no state and won't add any.
		 */

		worker = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "RSA Promiscuous Topology manager export worker");
			t.setDaemon(true);
			return t;
		}, new ThreadPoolExecutor.DiscardPolicy());

		try {
			exporter = new ServiceExporter(config.local_scopes());
			importer = new ServiceImporter(config.local_scopes());

			worker.scheduleWithFixedDelay(exporter::checkExports, 10, 10, TimeUnit.SECONDS);
			worker.scheduleWithFixedDelay(importer::checkImports, 10, 10, TimeUnit.SECONDS);


			listener = context.registerService(new String[] {EndpointEventListener.class.getName()},
					new ListenerServiceFactory(importer, worker), getEndpointFilter(context));


			serviceTracker = new ServiceTracker<Object, Object>(context,
					createFilter("(service.exported.interfaces=*)"), null) {

					@Override
					public Object addingService(ServiceReference<Object> reference) {
						worker.execute(() -> exporter.exportService(reference));
						return reference;
					}

					@Override
					public void modifiedService(ServiceReference<Object> reference, Object service) {
						worker.execute(() -> exporter.updateExportedService(reference));
					}

					@Override
					public void removedService(ServiceReference<Object> reference, Object service) {
						worker.execute(() -> exporter.removeExportedService(reference));
					}
				};

			serviceTracker.open();

			latch.countDown();

			if(startupFailed.get()) {
				String message = "There was a serious error starting the Promiscuous Topology Manager";
				logger.error(message);
				throw new RuntimeException(message);
			}
		} catch (Exception e) {
			logger.error("An error occurred starting the Promiscuous Topology Manager", e);
			stop();
			throw e;
		}
	}

	private Dictionary<String, Object> getEndpointFilter(BundleContext ctx) {
		Dictionary<String, Object> table = new Hashtable<>();
		table.put(EndpointEventListener.ENDPOINT_LISTENER_SCOPE,
				"(!(" + RemoteConstants.ENDPOINT_FRAMEWORK_UUID + "=" + ctx.getProperty(FRAMEWORK_UUID) + "))");
		return table;
	}

	@Modified
	void update(Config config) {
		worker.execute(() -> importer.updateScopes(config.local_scopes()));
		worker.execute(() -> exporter.updateScopes(config.local_scopes()));
	}

	@Deactivate
	void stop() {
		worker.shutdown();
		try {
			if(!worker.awaitTermination(3, TimeUnit.SECONDS)) {
				worker.shutdownNow();
			}
		} catch (InterruptedException e) {
			logger.warn("An error occurred while shutting down the RSA Topoolgy Manager imports", e);
		}

		serviceTracker.close();
		try {
			listener.unregister();
		} catch (IllegalStateException ise) {
			logger.debug("An error occurred unregistering the listener");
		}
	}

	/**
	 * Ensure that the runnable doesn't NPE if we try to run it before
	 * the component activates, and that it waits for the latch if needed
	 *
	 * @param r
	 */
	void runWithWaitForStart(Runnable r) {

		Runnable withCheck = latch.getCount() == 0 ? r :
				() -> {
						try {
							if(latch.await(5, TimeUnit.SECONDS)) {
								r.run();
							} else {
								startupFailed.set(true);
								logger.error("There was an error starting up the Promiscuous Topology Manager. Too much time elapsed before startup was complete");
							}
						} catch (InterruptedException e) {
							startupFailed.set(true);
							logger.error("There was an error starting up the Promiscuous Topology Manager. The startup was interrupted before startup was complete", e);
						}
					};

		Executor e = worker;

		if(e == null) {
			new Thread(withCheck).start();
		} else {
			e.execute(withCheck);
		}
	}

	private class ListenerServiceFactory implements ServiceFactory<Object> {

		private final ServiceImporter importer;

		private final Executor worker;

		public ListenerServiceFactory(ServiceImporter importer, Executor worker) {
			this.importer = importer;
			this.worker = worker;
		}

		@Override
		public Object getService(Bundle bundle, ServiceRegistration<Object> registration) {
			return new EndpointListenerService(bundle) {

				@Override
				protected void handleEvent(Bundle client, EndpointEvent event, String filter) {
					worker.execute(() -> {
							switch(event.getType()) {
								case EndpointEvent.ADDED :
									importer.incomingEndpoint(bundle, event.getEndpoint());
									break;
								case EndpointEvent.REMOVED :
								case EndpointEvent.MODIFIED_ENDMATCH :
									importer.departingEndpoint(bundle, event.getEndpoint());
									break;
								case EndpointEvent.MODIFIED :
									importer.modifiedEndpoint(bundle, event.getEndpoint());
									break;
							}
						});
				}
			};
		}

		@Override
		public void ungetService(Bundle bundle, ServiceRegistration<Object> registration,
				Object service) {
			importer.releaseListener(bundle);
		}
	}
}
