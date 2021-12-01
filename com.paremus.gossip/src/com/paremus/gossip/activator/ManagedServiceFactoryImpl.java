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
package com.paremus.gossip.activator;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.net.ServerSocketFactory;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.metatype.Configurable;

import com.paremus.gossip.ClusterManager;
import com.paremus.gossip.GossipReplicator;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.gossip.cluster.impl.ClusterManagerImpl;
import com.paremus.gossip.cluster.listener.ClusterListener;
import com.paremus.gossip.impl.GossipImpl;
import com.paremus.gossip.net.SocketComms;
import com.paremus.gossip.net.SpecialTCPReplicator;
import com.paremus.gossip.net.TCPReplicator;
import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncodingSchemeFactory;

public class ManagedServiceFactoryImpl implements ManagedServiceFactory {

	private static final Logger logger = LoggerFactory.getLogger(ManagedServiceFactoryImpl.class);
	
	private final UUID frameworkUUID;
	
	private final BundleContext context;
	
	private final ConcurrentHashMap<String, ClusterManager> gossipers = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<ClusterManager, ServiceRegistration<?>> registrations = new ConcurrentHashMap<>();
	
	private final ServiceTracker<ClusterListener, ServiceReference<ClusterListener>> tracker;

	private final EncodingSchemeFactory encodingSchemeFactory;
	
	public ManagedServiceFactoryImpl(UUID frameworkUUID, BundleContext context, EncodingSchemeFactory es) {
		this.frameworkUUID = frameworkUUID;
		this.context = context;
		this.encodingSchemeFactory = es;
		
		tracker = new ServiceTracker<ClusterListener, ServiceReference<ClusterListener>>(context, ClusterListener.class, null) {
			@Override
			public ServiceReference<ClusterListener> addingService(
					ServiceReference<ClusterListener> reference) {
				gossipers.values().stream().forEach((cm) -> cm.listenerChange(reference, ServiceEvent.REGISTERED));
				return reference;
			}

			@Override
			public void modifiedService(
					ServiceReference<ClusterListener> reference,
					ServiceReference<ClusterListener> service) {
				gossipers.values().stream().forEach((cm) -> cm.listenerChange(reference, ServiceEvent.MODIFIED));
			}

			@Override
			public void removedService(
					ServiceReference<ClusterListener> reference,
					ServiceReference<ClusterListener> service) {
				gossipers.values().stream().forEach((cm) -> cm.listenerChange(reference, ServiceEvent.UNREGISTERING));
			}
		};
		tracker.open();
	}

	@Override
	public String getName() {
		return "Paremus Gossip factory";
	}

	private InetAddress toInetAddress(String name) {
		try {
			return InetAddress.getByName(name);
		} catch (UnknownHostException uhe) {
			logger.error("Unable to resolve the address " + name, uhe);
			throw new RuntimeException(uhe);
		}
	}
	
	@Override
	public void updated(String pid, Dictionary<String, ?> properties)
			throws ConfigurationException {
		if(properties == null) {
			return;
		}
		
		Config config = properties == null ? null : Configurable.createConfigurable(Config.class, properties);
		
		ClusterManager cm = gossipers.compute(pid, (k, v) -> consumeAndCreate(v, pid, config));
		
		registrations.put(cm, context.registerService(ClusterInformation.class.getName(), cm, properties));

		ServiceReference<ClusterListener>[] listeners = tracker.getServiceReferences();
		if(listeners != null) {
			Arrays.stream(listeners).forEach((ref) -> cm.listenerChange(ref, ServiceEvent.REGISTERED));
		}
	}
	
	private ClusterManager consumeAndCreate(ClusterManager old, String pid, Config config) {
		if(old != null) {
			Optional.ofNullable(registrations.get(old)).ifPresent(ServiceRegistration::unregister);
			old.destroy();
		}
		
		Function<InetAddress, Stream<InetSocketAddress>> mapAddresstoSocketAddress = 
				(address) -> IntStream.iterate(config.base_udp_port(), (i) -> i + config.port_increment())
					.limit(config.max_members()).mapToObj(((i) -> new InetSocketAddress(address, i)));
				
		try {
			String cluster = config.cluster_name();
			InetAddress localAddress = validate(cluster, config.infra(), config.initial_peers(), config.bind_address());
			
			List<SocketAddress> peers = config.initial_peers().stream().map(this::toInetAddress)
					.flatMap(mapAddresstoSocketAddress).collect(toList());
			

			if(logger.isInfoEnabled()) {
				logger.info("Starting to gossip in cluster {}", cluster);
			}
			
			EncodingScheme encodingScheme = encodingSchemeFactory.createEncodingScheme(
					() -> ofNullable(gossipers.get(pid)).ifPresent(ClusterManager::notifyKeyChange));
			
			ServerSocketFactory socketFactory;
			boolean useSpecialTCPReplicator;
			if(encodingScheme.isConfidential() && !encodingScheme.requiresCertificates()) {
				socketFactory = ServerSocketFactory.getDefault();
				useSpecialTCPReplicator = true;
			} else {
				socketFactory = encodingScheme.getServerSocketFactory();
				useSpecialTCPReplicator = false;
			}
			
			SocketComms comms = new SocketComms(cluster, frameworkUUID, config, encodingScheme,
					socketFactory);
			
			ClusterManagerImpl clusterManager = new ClusterManagerImpl(context, frameworkUUID, config, 
					comms.getUdpPort(), comms.getTcpPort(), localAddress);
			
			GossipImpl impl = new GossipImpl(context, clusterManager, comms, config, peers);
			
			clusterManager.setInternalListener(impl);
			AtomicInteger threadId = new AtomicInteger();
			GossipReplicator replicator = useSpecialTCPReplicator ?
					new SpecialTCPReplicator(frameworkUUID, impl, encodingScheme, comms) :
					new TCPReplicator(frameworkUUID, impl, encodingScheme, comms);
			comms.startListening(impl, replicator, 
					Executors.newScheduledThreadPool(6, r -> {
						Thread t = new Thread(r, "Gossip Communications worker - " 
								+ cluster + " " + threadId.incrementAndGet());
						t.setDaemon(true);
						return t;
					}));
			
			return clusterManager;
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

	private InetAddress validate(String cluster, boolean infra,
			Set<String> initial_peers, String bindAddress) throws ConfigurationException {
		InetAddress bind = toInetAddress(bindAddress);
		
		if(!bind.isAnyLocalAddress()) {
			//We have a fixed bind address that should be the one we advertise
			return validateSpecificBind(bind);
		} else {
			Set<InetAddress> potentialAddresses = initial_peers.stream()
				.map(this::toInetAddress).filter(this::isPeer).collect(toSet());
		
			if(!potentialAddresses.isEmpty()) {
				if(potentialAddresses.size() > 1) {
					InetAddress selected = potentialAddresses.stream().sorted(this::preferredIP).findFirst().get();
					logger.warn("This machine is accessible via multiple initial peer addresses. This is a configuration error, and the address {} has been selected", selected);
					return selected;
				}
				return potentialAddresses.iterator().next();
			} else if(infra) {
				logger.warn("This node is declared as an Infra, but is not listed as a peer");
			}
			return null;
		}
	}

	private boolean isPeer(InetAddress a) {
		if(a.isLoopbackAddress()) {
			logger.warn("The initial peer address {} is a loopback address. This may cause reachability issues unless all members are located on the same machine", a);
		}
		try {
			NetworkInterface ni = NetworkInterface.getByInetAddress(a); 
			if(ni != null) {
				if(!ni.isUp()) {
					logger.warn("The initial peer address {} is defined for this machine, but the network interface is down");
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
		if(i == 0){
			//Compare the bytes and select the "lower" IP
			for(int x = 0; x < b1.length; x++) {
				i = b1[x] - b2[x];
				if(i != 0) {
					return i;
				}
			}
			return 0;
		} else {
			//Prefer IPv6, which is longer than IPv4
			return -i;
		}
	}

	private InetAddress validateSpecificBind(InetAddress bind) throws ConfigurationException {
		if(bind.isLoopbackAddress()) {
			logger.warn("The bind address {} is a loopback address. This will prevent gossip with any nodes on other machines", bind);
		}
		try {
			NetworkInterface ni = NetworkInterface.getByInetAddress(bind); 
			if(ni != null) {
				if(!ni.isUp()) {
					logger.error("The bind address {} is defined for this machine, but the network interface is down. Unable to start gossiping", bind);
					throw new ConfigurationException("bind.address", "The bind address interface is not up");
				} else {
					return bind;
				}
			} else {
				logger.error("The bind address {} does not exist on this machine. Unable to start gossiping", bind);
				throw new ConfigurationException("bind.address", "The bind address interface does not exist on this machine");
			}
		} catch (SocketException se) {
			logger.error("Unable to determine the interface being bound with address " + bind, se);
			throw new ConfigurationException("bind.address", "The bind address interface could not be determined", se);
		}
	}

	@Override
	public void deleted(String pid) {
		ofNullable(gossipers.remove(pid)).ifPresent(
				(cm) -> {
					ofNullable(registrations.remove(cm)).ifPresent(ServiceRegistration::unregister);
					cm.destroy();
				});
	}
	
	public void destroy() {
		gossipers.keySet().stream().forEach(this::deleted);
	}

}
