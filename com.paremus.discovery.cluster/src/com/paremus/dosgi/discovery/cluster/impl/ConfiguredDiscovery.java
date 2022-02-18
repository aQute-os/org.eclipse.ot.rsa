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
package com.paremus.dosgi.discovery.cluster.impl;

import static com.paremus.dosgi.discovery.cluster.impl.ClusterDiscoveryImpl.PAREMUS_DISCOVERY_DATA;
import static com.paremus.dosgi.scoping.discovery.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;
import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.namespace.implementation.ImplementationNamespace.IMPLEMENTATION_NAMESPACE;
import static org.osgi.namespace.service.ServiceNamespace.SERVICE_NAMESPACE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.remoteserviceadmin.namespace.DiscoveryNamespace.DISCOVERY_NAMESPACE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.osgi.annotation.bundle.Capability;
import org.osgi.annotation.bundle.Requirement;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.cluster.ClusterInformation;
import com.paremus.cluster.listener.ClusterListener;
import com.paremus.dosgi.discovery.cluster.ClusterDiscovery;
import com.paremus.dosgi.discovery.cluster.local.LocalDiscoveryListener;
import com.paremus.dosgi.scoping.discovery.ScopeManager;
import com.paremus.net.info.ClusterNetworkInformation;
import com.paremus.netty.tls.ParemusNettyTLS;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;

@SuppressWarnings("deprecation")
@Capability(namespace=SERVICE_NAMESPACE, attribute=OBJECTCLASS + ":List<String>=com.paremus.cluster.listener.ClusterListener", uses=ClusterListener.class)
@Capability(namespace=SERVICE_NAMESPACE, attribute=OBJECTCLASS + ":List<String>=\"org.osgi.service.remoteserviceadmin.EndpointEventListener,org.osgi.service.remoteserviceadmin.EndpointListener\"", uses=ClusterListener.class)
@Capability(namespace=DISCOVERY_NAMESPACE, attribute="protocols:List<String>=com.paremus.cluster", version="1.0", uses=EndpointEventListener.class)
@Requirement(namespace=IMPLEMENTATION_NAMESPACE, name="com.paremus.cluster", version="1.0", effective="active")
@Component(configurationPid=PAREMUS_DISCOVERY_DATA, configurationPolicy=REQUIRE, immediate=true)
public class ConfiguredDiscovery implements ScopeManager, ClusterDiscovery {
	private static final String LOCAL_FW_UUID_PLACEHOLDER = "${LOCAL_FW_UUID}";

	private static final Logger logger = LoggerFactory.getLogger(ClusterDiscoveryImpl.class);

	private final EventExecutorGroup worker;
	private final EventExecutorGroup remoteEventDelivery;

	private final ClusterDiscoveryImpl discovery;
	private final ServiceRegistration<ClusterListener> clusterListenerReg;
	private final ServiceRegistration<?> endpointEventListenerReg;

	private final Set<String> targetClusters;

	@Activate
	public ConfiguredDiscovery(BundleContext context, @Reference ParemusNettyTLS tls, Config cfg) {

		UUID fwId = UUID.fromString(context.getProperty(org.osgi.framework.Constants.FRAMEWORK_UUID));

		targetClusters = stream(cfg.target_clusters()).collect(toSet());

		worker = new DefaultEventExecutorGroup(1, r -> {
				Thread t = new FastThreadLocalThread(r, "Paremus Cluster RSA Discovery Worker");
				t.setDaemon(true);
				return t;
			});

		remoteEventDelivery = new DefaultEventExecutorGroup(1, r -> {
			Thread t = new FastThreadLocalThread(r, "Paremus Cluster RSA Discovery Event Delivery");
			t.setDaemon(true);
			return t;
		});

    	try {
			LocalDiscoveryListener newlistener = new LocalDiscoveryListener(cfg.rebroadcast_interval(),
					worker);
			discovery = new ClusterDiscoveryImpl(context, fwId, newlistener, tls, cfg, worker, remoteEventDelivery);

			Hashtable<String, Object> props = new Hashtable<>();
			props.put(ClusterListener.LIMIT_KEYS, Arrays.asList(PAREMUS_DISCOVERY_DATA, PAREMUS_SCOPES_ATTRIBUTE));

			if(cfg.target_clusters().length > 0) {
				props.put(ClusterListener.CLUSTER_NAMES, Arrays.asList(cfg.target_clusters()));
			}

			clusterListenerReg = context.registerService(ClusterListener.class, discovery::clusterEvent, props);

			endpointEventListenerReg = context.registerService(new String[] {EndpointEventListener.class.getName(),
					EndpointListener.class.getName()}, newlistener, getFilters(cfg, fwId));
    	} catch (RuntimeException e) {
    		logger.error("Unable to start the gossip based discovery", e);
    		destroy();
    		throw e;
    	}
	}

	static Hashtable<String, Object> getFilters(Config cfg, UUID id) {
		Hashtable<String, Object> props;
		props = new Hashtable<>();
		String[] extraFilters = cfg.additional_filters();
		List<String> filters = new ArrayList<>(extraFilters.length + 1);
		String localFilter = "("+ RemoteConstants.ENDPOINT_FRAMEWORK_UUID + "=" + id + ")";
		if(cfg.local_id_filter_extension().isEmpty()) {
			filters.add(localFilter);
		} else {
			String extension = cfg.local_id_filter_extension().replace(LOCAL_FW_UUID_PLACEHOLDER, id.toString());
			filters.add("(&" + extension + localFilter + ")");
		}
		for(String filter : extraFilters) {
			filters.add(filter.replace(LOCAL_FW_UUID_PLACEHOLDER, id.toString()));
		}
		props.put(EndpointEventListener.ENDPOINT_LISTENER_SCOPE, filters);
		return props;
	}

	@Deactivate
	public void destroy() {

		if(clusterListenerReg != null) {
			try {
				clusterListenerReg.unregister();
			} catch (IllegalStateException ise) {}
		}

		if(endpointEventListenerReg != null) {
			try {
				endpointEventListenerReg.unregister();
			} catch (IllegalStateException ise) {}
		}

		PromiseCombiner pc = new PromiseCombiner();
		if(discovery != null) {
			pc.add(discovery.destroy());
		}
		pc.add(worker.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS));
		pc.add(remoteEventDelivery.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS));

		try {
			Promise<Void> promise = ImmediateEventExecutor.INSTANCE.newPromise();
			pc.finish(promise);
			promise.sync();
		} catch (Exception e) {
			logger.debug("An error occurred while shutting down the RSA cluster discovery", e);
		}
	}

	@Reference(policy=DYNAMIC, cardinality=MULTIPLE)
	void setClusterInformation(ClusterInformation ci) {
		if(targetClusters.isEmpty() || targetClusters.contains(ci.getClusterName())) {
			discovery.addClusterInformation(ci);
		}
	}

	void unsetClusterInformation(ClusterInformation ci) {
		if(targetClusters.isEmpty() || targetClusters.contains(ci.getClusterName())) {
			discovery.removeClusterInformation(ci);
		}
	}

	@Reference(policy=DYNAMIC, cardinality=MULTIPLE)
	void setClusterNetworkInformation(ClusterNetworkInformation cni) {
		if(targetClusters.isEmpty() || targetClusters.contains(cni.getClusterName())) {
			discovery.addNetworkInformation(cni);
		}
	}

	void unsetClusterNetworkInformation(ClusterNetworkInformation cni) {
		if(targetClusters.isEmpty() || targetClusters.contains(cni.getClusterName())) {
			discovery.removeNetworkInformation(cni);
		}
	}

	@Override
	public String getRootCluster() {
		return discovery.getRootCluster();
	}

	@Override
	public Set<String> getClusters() {
		return discovery.getClusters();
	}

	@Override
	public Set<String> getCurrentScopes() {
		return discovery.getCurrentScopes();
	}

	@Override
	public void addLocalScope(String name) {
		discovery.addLocalScope(name);
	}

	@Override
	public void removeLocalScope(String name) {
		discovery.removeLocalScope(name);
	}

	@Override
	public Set<String> getBaseScopes() {
		return discovery.getBaseScopes();
	}
}
