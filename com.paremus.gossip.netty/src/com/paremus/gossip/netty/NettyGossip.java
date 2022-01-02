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
package com.paremus.gossip.netty;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.osgi.namespace.implementation.ImplementationNamespace.IMPLEMENTATION_NAMESPACE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.osgi.annotation.bundle.Capability;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.cluster.ClusterInformation;
import com.paremus.cluster.listener.ClusterListener;
import com.paremus.gossip.cluster.impl.ClusterManagerImpl;
import com.paremus.gossip.impl.GossipImpl;
import com.paremus.gossip.net.NettyComms;
import com.paremus.netty.tls.ParemusNettyTLS;

@Capability(namespace = IMPLEMENTATION_NAMESPACE, name = "com.paremus.cluster", version = "1.0.0")
@Component(configurationPid = "com.paremus.gossip.netty", configurationPolicy = REQUIRE, immediate=true)
public class NettyGossip {

	private static final Logger logger = LoggerFactory.getLogger(NettyGossip.class);

	private final ClusterManagerImpl manager;
	private final ServiceRegistration<ClusterInformation> registration;

	@Activate
	public NettyGossip(Config config, BundleContext context, @Reference(name = "ssl") ParemusNettyTLS tls,
			Map<String, Object> props) {
		
		UUID id = UUID.fromString(context.getProperty(Constants.FRAMEWORK_UUID));
		manager = create(context, id, config, tls);
		
		try {
			Hashtable<String, Object> properties = new Hashtable<>(props);
			properties.keySet().removeIf(s -> s.startsWith("."));
			registration = context.registerService(ClusterInformation.class, manager, properties);
		} catch (RuntimeException e) {
			manager.destroy();
			throw e;
		}
	}
	
	@Deactivate
	public void destroy() {
		try {
			registration.unregister();
		} catch (Exception e) {
			// Never mind
		}
		manager.destroy();
	}

	@Reference(policy = DYNAMIC, cardinality = MULTIPLE)
	void setListener(ServiceReference<ClusterListener> ref) {
		manager.listenerChange(ref, ServiceEvent.REGISTERED);
	}

	void updatedListener(ServiceReference<ClusterListener> ref) {
		manager.listenerChange(ref, ServiceEvent.MODIFIED);
	}

	void unsetListener(ServiceReference<ClusterListener> ref) {
		manager.listenerChange(ref, ServiceEvent.UNREGISTERING);
	}

	private InetSocketAddress toInetSocketAddress(String name, Config config) {
		String host;
		int port;
		int colonIndex = name.lastIndexOf(':');
		if (colonIndex == -1) {
			host = name;
			port = config.udp_port();
			// TODO log a warning
		} else {
			host = name.substring(0, colonIndex);
			port = Integer.parseInt(name.substring(colonIndex + 1));
		}
		return new InetSocketAddress(host, port);
	}

	private ClusterManagerImpl create(BundleContext context, UUID frameworkUUID, Config config, ParemusNettyTLS tls) {
		try {
			String cluster = config.cluster_name();

			List<InetSocketAddress> peers = Arrays.stream(config.initial_peers()).distinct()
					.map(s -> toInetSocketAddress(s, config)).collect(toList());

			InetAddress localAddress = validate(cluster, config, peers, config.bind_address());

			if (logger.isInfoEnabled()) {
				logger.info("Starting to gossip in cluster {}", cluster);
			}
			ClusterManagerImpl clusterManager = new ClusterManagerImpl(context, frameworkUUID, config,
					config.udp_port(), config.tcp_port(), localAddress, cm -> new GossipImpl(context, cm,
							g -> new NettyComms(cluster, frameworkUUID, config, tls, g), config, peers));
			return clusterManager;
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

	private InetAddress validate(String cluster, Config config, List<InetSocketAddress> peers, String bindAddress)
			throws ConfigurationException {
		InetAddress bind;
		try {
			bind = InetAddress.getByName(bindAddress);
		} catch (UnknownHostException e) {
			// TODO log this
			throw new ConfigurationException("bind.address", "The bind address could not be understood", e);
		}
		if (!bind.isAnyLocalAddress()) {
			// We have a fixed bind address that should be the one we advertise
			return validateSpecificBind(bind);
		} else {
			Set<InetAddress> potentialAddresses = peers.stream().filter(isa -> isa.getPort() == config.udp_port())
					.map(InetSocketAddress::getAddress).filter(this::isPeer).collect(toSet());
			if (!potentialAddresses.isEmpty()) {
				if (potentialAddresses.size() > 1) {
					InetAddress selected = potentialAddresses.stream().sorted(this::preferredIP).findFirst().get();
					logger.warn(
							"This machine is accessible via multiple initial peer addresses. This is a configuration error, and the address {} has been selected",
							selected);
					return selected;
				}
				return potentialAddresses.iterator().next();
			} else if (config.infra()) {
				logger.warn("This node is declared as an Infra, but is not listed as a peer");
			}
			return null;
		}
	}

	private boolean isPeer(InetAddress a) {
		if (a.isLoopbackAddress()) {
			logger.warn(
					"The initial peer address {} is a loopback address. This may cause reachability issues unless all members are located on the same machine",
					a);
		}
		try {
			NetworkInterface ni = NetworkInterface.getByInetAddress(a);
			if (ni != null) {
				if (!ni.isUp()) {
					logger.warn(
							"The initial peer address {} is defined for this machine, but the network interface is down");
				} else {
					return true;
				}
			}
		} catch (SocketException se) {
			logger.error("Unable to determine the interface for address " + a, se);
		}
		return false;
	}

	private int preferredIP(InetAddress i1, InetAddress i2) {
		byte[] b1 = i1.getAddress();
		byte[] b2 = i2.getAddress();
		int i = b1.length - b2.length;
		if (i == 0) {
			// Compare the bytes and select the "lower" IP
			for (int x = 0; x < b1.length; x++) {
				i = b1[x] - b2[x];
				if (i != 0) {
					return i;
				}
			}
			return 0;
		} else {
			// Prefer IPv6, which is longer than IPv4
			return -i;
		}
	}

	private InetAddress validateSpecificBind(InetAddress bind) throws ConfigurationException {
		if (bind.isLoopbackAddress()) {
			logger.warn(
					"The bind address {} is a loopback address. This will prevent gossip with any nodes on other machines",
					bind);
		}
		try {
			NetworkInterface ni = NetworkInterface.getByInetAddress(bind);
			if (ni != null) {
				if (!ni.isUp()) {
					logger.error(
							"The bind address {} is defined for this machine, but the network interface is down. Unable to start gossiping",
							bind);
					throw new ConfigurationException("bind.address", "The bind address interface is not up");
				} else {
					return bind;
				}
			} else {
				logger.error("The bind address {} does not exist on this machine. Unable to start gossiping", bind);
				throw new ConfigurationException("bind.address",
						"The bind address interface does not exist on this machine");
			}
		} catch (SocketException se) {
			logger.error("Unable to determine the interface being bound with address " + bind, se);
			throw new ConfigurationException("bind.address", "The bind address interface could not be determined", se);
		}
	}
}
