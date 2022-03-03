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
package org.eclipse.ot.rsa.cluster.gossip.netty;

import static java.util.stream.Collectors.toList;
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
import java.util.Collection;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.ot.rsa.cluster.api.ClusterConstants;
import org.eclipse.ot.rsa.cluster.api.ClusterInformation;
import org.eclipse.ot.rsa.cluster.api.ClusterListener;
import org.eclipse.ot.rsa.cluster.gossip.config.ClusterGossipConfig;
import org.eclipse.ot.rsa.cluster.gossip.net.NettyComms;
import org.eclipse.ot.rsa.cluster.gossip.provider.GossipImpl;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.Snapshot;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType;
import org.eclipse.ot.rsa.cluster.manager.provider.ClusterManagerImpl;
import org.eclipse.ot.rsa.cluster.manager.provider.MemberInfo;
import org.eclipse.ot.rsa.cluster.manager.provider.Update;
import org.eclipse.ot.rsa.constants.RSAConstants;
import org.eclipse.ot.rsa.logger.util.HLogger;
import org.eclipse.ot.rsa.tls.netty.provider.tls.ParemusNettyTLS;
import org.osgi.annotation.bundle.Capability;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import aQute.bnd.exceptions.Exceptions;
import io.netty.util.concurrent.EventExecutorGroup;

@Capability(namespace = IMPLEMENTATION_NAMESPACE, name = ClusterConstants.IMPLEMENTATION_NAMESPACE, version = "1.0.0")
@Component(name = RSAConstants.CLUSTER_GOSSIP_PID, configurationPolicy = REQUIRE, immediate = true)
public class NettyGossip implements ClusterInformation {
	static final HLogger			root	= HLogger.root(NettyGossip.class);
	final static Pattern			INETSOCKET_P	= Pattern.compile("(?<ip>[^:]+)(:(?<port>\\d+))");

	final HLogger					log;
	final ClusterManagerImpl		manager;
	final String					host;
	final String					cluster;
	final int						tcp;
	final int						udp;
	final UUID						framework;
	final InetAddress				address;
	final List<InetSocketAddress>	peers;

	@Activate
	public NettyGossip(ClusterGossipConfig config, BundleContext context, @Reference(name = "ssl")
	ParemusNettyTLS tls) throws Exception {
		this.host = config.bind_address();
		this.cluster = config.cluster_name();
		this.udp = config.udp_port();
		this.tcp = config.tcp_port();
		this.log = root.child(this::status);
		this.framework = UUID.fromString(context.getProperty(Constants.FRAMEWORK_UUID));
		this.peers = Arrays.stream(config.initial_peers())
			.distinct()
			.map(s -> {
				try {
					return toInetSocketAddress(s, udp);
				} catch (ConfigurationException e) {
					throw Exceptions.duck(e);
				}
			})
			.collect(toList());
		this.address = calculateAddress(this.host, peers);
		welcome();
		this.manager = new ClusterManagerImpl(context, framework, config, udp, tcp, address,
			cm -> new GossipImpl(context, cm, g -> new NettyComms(cluster, framework, config, tls, g), config, peers),
			log);
	}

	@Deactivate
	public void destroy() {
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

	void status(Formatter f) {
		f.format("%s:%s/%s ", address.getHostAddress(), udp, cluster);
	}

	private InetSocketAddress toInetSocketAddress(String name, int udp) throws ConfigurationException {
		Matcher matcher = INETSOCKET_P.matcher(name);
		if (!matcher.matches())
			throw new ConfigurationException("peers", "improper format for peer:" + name);

		String host = matcher.group("ip");
		int port = matcher.group(2) == null ? udp : Integer.parseInt(matcher.group("port"));
		return new InetSocketAddress(host, port);
	}

	private void welcome() throws Exception {
		StringBuilder extra = new StringBuilder();

		if (address.isLoopbackAddress()) {
			extra.append(" loopback!");
		}
		if (address.isMulticastAddress()) {
			extra.append(" multicast!");
		}
		if (address.isLinkLocalAddress()) {
			extra.append(" link-local!");
		}
		NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
		if (networkInterface != null) {
			extra.append(" " + networkInterface.getDisplayName() + "(");
			extra.append("mtu=" + networkInterface.getMTU() + ",up=" + networkInterface.isUp() + ",mac="
				+ networkInterface.getHardwareAddress() + ",virtual=" + networkInterface.isVirtual() + ")");

		} else {
			extra.append(" no network-if");
		}

		log.info("%s", extra);
	}

	private InetAddress calculateAddress(String bindAddress, List<InetSocketAddress> peers)
		throws ConfigurationException {
		InetAddress address;
		try {
			address = InetAddress.getByName(bindAddress);
		} catch (UnknownHostException e) {
			throw new ConfigurationException("bind.address", "No InetAddress found", e);
		}

		try {
			NetworkInterface ni = NetworkInterface.getByInetAddress(address);
			if (ni != null) {
				return address;
			} else {
				throw new ConfigurationException("bind.address",
					"No network interface found, available: " + getNetworkInterfaces());
			}
		} catch (SocketException se) {
			throw new ConfigurationException("bind.address", "Unable to determine network interface", se);
		}
	}

	private StringBuilder getNetworkInterfaces() {
		StringBuilder sb = new StringBuilder();
		try {
			String niDel = "";
			Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
			while (nis.hasMoreElements()) {
				NetworkInterface ni = nis.nextElement();
				sb.append(niDel)
					.append(ni.getName())
					.append("(");
				Enumeration<InetAddress> addresses = ni.getInetAddresses();
				String del = "";
				while (addresses.hasMoreElements()) {
					InetAddress address = addresses.nextElement();
					sb.append(del)
						.append(address);
				}
				sb.append(")");
				niDel = "\n";
			}
		} catch (SocketException e) {
			// too bad
		}
		return sb;
	}

	public Set<Snapshot> getMemberSnapshots(SnapshotType snapshotType) {
		return manager.getMemberSnapshots(snapshotType);
	}

	public Update mergeSnapshot(Snapshot snapshot) {
		return manager.mergeSnapshot(snapshot);
	}

	public Snapshot getSnapshot(SnapshotType type, int hops) {
		return manager.getSnapshot(type, hops);
	}

	public MemberInfo getMemberInfo(UUID uuid) {
		return manager.getMemberInfo(uuid);
	}

	public Collection<MemberInfo> selectRandomPartners(int number) {
		return manager.selectRandomPartners(number);
	}

	public void listenerChange(ServiceReference<ClusterListener> ref, int state) {
		manager.listenerChange(ref, state);
	}

	@Override
	public Collection<UUID> getKnownMembers() {
		return manager.getKnownMembers();
	}

	@Override
	public Map<UUID, InetAddress> getMemberHosts() {
		return manager.getMemberHosts();
	}

	@Override
	public String getClusterName() {
		return manager.getClusterName();
	}

	@Override
	public InetAddress getAddressFor(UUID member) {
		return manager.getAddressFor(member);
	}

	@Override
	public UUID getLocalUUID() {
		return manager.getLocalUUID();
	}

	@Override
	public byte[] getMemberAttribute(UUID member, String key) {
		return manager.getMemberAttribute(member, key);
	}

	@Override
	public void updateAttribute(String key, byte[] bytes) {
		manager.updateAttribute(key, bytes);
	}

	@Override
	public Map<String, byte[]> getMemberAttributes(UUID member) {
		return manager.getMemberAttributes(member);
	}

	@Override
	public String toString() {
		return manager.toString();
	}

	public void leavingCluster(Snapshot update) {
		manager.leavingCluster(update);
	}

	public void markUnreachable(MemberInfo member) {
		manager.markUnreachable(member);
	}

	public EventExecutorGroup getEventExecutorGroup() {
		return manager.getEventExecutorGroup();
	}

}
