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
package org.eclipse.ot.rsa.distribution.provider.activator;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.eclipse.ot.rsa.constants.RSAConstants;
import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.eclipse.ot.rsa.distribution.provider.impl.RemoteServiceAdminFactoryImpl;
import org.eclipse.ot.rsa.multrsa.api.MultiFrameworkRemoteServiceAdmin;
import org.eclipse.ot.rsa.tls.netty.provider.tls.NettyTLS;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.util.converter.Converters;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;

public class ManagedServiceFactoryImpl implements ManagedServiceFactory {

	private static final Logger																			logger			= LoggerFactory
		.getLogger(ManagedServiceFactoryImpl.class);

	private final BundleContext																			context;

	private final Timer																					timer;

	private final EventLoopGroup																		serverIo;

	private final EventLoopGroup																		clientIo;

	private final EventExecutorGroup																	serverWorkers;

	private final EventExecutorGroup																	clientWorkers;

	private final ByteBufAllocator																		allocator;

	private final ConcurrentHashMap<String, ServiceTracker<NettyTLS, NettyTLS>>							trackers		= new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, RemoteServiceAdminFactoryImpl>								rsas			= new ConcurrentHashMap<>();
	private final ConcurrentHashMap<RemoteServiceAdminFactoryImpl, ServiceRegistration<?>>				registrations	= new ConcurrentHashMap<>();

	private final ConcurrentHashMap<ServiceReference<NettyTLS>, List<RemoteServiceAdminFactoryImpl>>	usedBy			= new ConcurrentHashMap<>();

	private volatile boolean																			open			= true;

	public ManagedServiceFactoryImpl(BundleContext context, Timer timer, EventLoopGroup serverIo,
		EventLoopGroup clientIo, EventExecutorGroup serverWorkers, EventExecutorGroup clientWorkers,
		ByteBufAllocator allocator) {
		this.context = context;
		this.timer = timer;
		this.serverIo = serverIo;
		this.clientIo = clientIo;
		this.serverWorkers = serverWorkers;
		this.clientWorkers = clientWorkers;
		this.allocator = allocator;
	}

	@Override
	public String getName() {
		return "Paremus RSA additional Transports provider";
	}

	@Override
	public void updated(String pid, Dictionary<String, ?> properties) throws ConfigurationException {

		deleted(pid);

		if (!open) {
			return;
		}

		TransportConfig config;
		try {
			config = Converters.standardConverter()
				.convert(toMap(properties))
				.to(TransportConfig.class);
		} catch (Exception e) {
			logger.error("Unable to process the configuration for pid {}", pid, e);
			throw new ConfigurationException(null, e.getMessage());
		}

		if (config.server_protocols().length == 0 && config.client_protocols().length == 0) {
			logger.info("The pid {} defines no RSA transports, so no RSA will be created", pid);
			return;
		}

		String filter = config.encoding_scheme_target();

		Predicate<ServiceReference<NettyTLS>> selector;
		if (filter.isEmpty()) {
			selector = r -> true;
		} else {
			Filter f;
			try {
				f = context.createFilter(filter);
			} catch (InvalidSyntaxException e) {
				logger.error("Unable to process the encoding scheme target filter for pid {}", pid, e);
				deleted(pid);
				throw new ConfigurationException("encoding.scheme.target", e.getMessage());
			}
			selector = r -> f.match(r);
		}

		ServiceTracker<NettyTLS, NettyTLS> tracker = new ServiceTracker<NettyTLS, NettyTLS>(context, NettyTLS.class,
			null) {
			@Override
			public NettyTLS addingService(ServiceReference<NettyTLS> reference) {
				NettyTLS esf = super.addingService(reference);

				if (esf != null && selector.test(reference) && !rsas.containsKey(pid)) {
					setup(reference, esf, config);
				}

				return esf;
			}

			private boolean setup(ServiceReference<NettyTLS> reference, NettyTLS esf, TransportConfig cfg) {
				RemoteServiceAdminFactoryImpl newRSA;
				try {
					newRSA = new RemoteServiceAdminFactoryImpl(context, config, esf, allocator, serverIo, clientIo,
						serverWorkers, clientWorkers, timer);
				} catch (IllegalArgumentException iae) {
					logger.error("The RSA could not be created with encoding scheme {}", reference, iae);
					return false;
				}

				rsas.put(pid, newRSA);
				usedBy.compute(reference,
					(k, v) -> v == null ? singletonList(newRSA) : concat(v.stream(), of(newRSA)).collect(toList()));

				Hashtable<String, Object> props = new Hashtable<>();

				Enumeration<String> keys = properties.keys();

				while (keys.hasMoreElements()) {
					String key = keys.nextElement();
					if (!key.startsWith(".")) {
						props.put(key, properties.get(key));
					}
				}

				props.put(RemoteConstants.REMOTE_INTENTS_SUPPORTED, newRSA.getSupportedIntents());
				props.put(RemoteConstants.REMOTE_CONFIGS_SUPPORTED,
					Collections.singletonList(RSAConstants.DISTRIBUTION_CONFIGURATION_TYPE));

				ServiceRegistration<?> newRSAReg = context.registerService(new String[] {
					RemoteServiceAdmin.class.getName(), MultiFrameworkRemoteServiceAdmin.class.getName()
				}, newRSA, props);

				registrations.put(newRSA, newRSAReg);
				return true;
			}

			@Override
			public void modifiedService(ServiceReference<NettyTLS> reference, NettyTLS service) {
				if (!selector.test(reference)) {
					removeAndAdd(reference);
				}
			}

			private void removeAndAdd(ServiceReference<NettyTLS> reference) {
				RemoteServiceAdminFactoryImpl rsa = rsas.get(pid);
				if (rsa != null) {
					if (usedBy.getOrDefault(reference, emptyList())
						.contains(rsa)) {

						usedBy.compute(reference, (k, v) -> {
							List<RemoteServiceAdminFactoryImpl> l = v == null ? emptyList() : new ArrayList<>(v);
							l.remove(rsa);
							return l.isEmpty() ? null : l;
						});
						rsas.remove(pid, rsa);

						ServiceRegistration<?> reg = registrations.remove(rsa);

						if (reg != null) {
							try {
								reg.unregister();
							} catch (IllegalStateException ise) {
								// No matter
							}
						}

						rsa.close();
					} else {
						return;
					}
				}
				getTracked().entrySet()
					.stream()
					.filter(e -> setup(e.getKey(), e.getValue(), config))
					.findFirst();
			}

			@Override
			public void removedService(ServiceReference<NettyTLS> reference, NettyTLS service) {
				removeAndAdd(reference);
				super.removedService(reference, service);
			}
		};
		trackers.put(pid, tracker);

		tracker.open();

		if (!open) {
			deleted(pid);
		}
	}

	static Map<String, Object> toMap(Dictionary<String, ?> properties) {
		Map<String, Object> map = new HashMap<>();
		Enumeration<String> keys = properties.keys();
		while (keys.hasMoreElements()) {
			String key = keys.nextElement();
			map.put(key, properties.get(key));
		}
		return map;
	}

	public void destroy() {
		open = false;
		trackers.keySet()
			.stream()
			.forEach(this::deleted);
	}

	@Override
	public void deleted(String pid) {
		ofNullable(trackers.remove(pid)).ifPresent(ServiceTracker::close);
	}
}
