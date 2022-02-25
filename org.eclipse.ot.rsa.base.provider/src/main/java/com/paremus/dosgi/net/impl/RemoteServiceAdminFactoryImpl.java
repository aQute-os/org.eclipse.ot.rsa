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
package com.paremus.dosgi.net.impl;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;

import com.paremus.dosgi.net.client.ClientConnectionManager;
import com.paremus.dosgi.net.config.TransportConfig;
import com.paremus.dosgi.net.server.RemotingProvider;
import com.paremus.dosgi.net.server.ServerConnectionManager;
import com.paremus.netty.tls.ParemusNettyTLS;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;

public class RemoteServiceAdminFactoryImpl implements ServiceFactory<RemoteServiceAdminImpl> {

	private static class Tuple {
		final RemoteServiceAdminEventPublisher rp;
		final int usageCount;

		public Tuple(RemoteServiceAdminEventPublisher rp, int usageCount) {
			this.rp = rp;
			this.usageCount = usageCount;
		}
	}

	private final ServerConnectionManager serverConnectionManager;
	private final ClientConnectionManager clientConnectionManager;

	private final ConcurrentMap<Bundle, Framework> bundleFrameworks
		= new ConcurrentHashMap<>();
	private final ConcurrentMap<Framework, Tuple> publisherReferenceCounts
		= new ConcurrentHashMap<>();

	private final List<RemoteServiceAdminImpl> impls = new CopyOnWriteArrayList<>();

	private final EventExecutorGroup serverWorkers;
	private final EventExecutorGroup clientWorkers;
	private final Timer timer;
	private final TransportConfig config;
	private final BundleContext context;

	public RemoteServiceAdminFactoryImpl(BundleContext context, TransportConfig config, ParemusNettyTLS tls,
			ByteBufAllocator allocator, EventLoopGroup serverIo, EventLoopGroup clientIo,
			EventExecutorGroup serverWorkers, EventExecutorGroup clientWorkers, Timer timer) {
		this.context = context;
		this.config = config;
		this.timer = timer;

		this.serverWorkers = serverWorkers;
		this.clientWorkers = clientWorkers;

		clientConnectionManager = new ClientConnectionManager(config, tls, allocator, clientIo, clientWorkers, timer);
		serverConnectionManager = new ServerConnectionManager(config, tls, allocator, serverIo, timer);
	}


	@Override
	public RemoteServiceAdminImpl getService(Bundle bundle, ServiceRegistration<RemoteServiceAdminImpl> registration) {
		Framework framework = bundle.getBundleContext().getBundle(0).adapt(Framework.class);

		bundleFrameworks.put(bundle, framework);

		RemoteServiceAdminEventPublisher rsaep = publisherReferenceCounts
				.compute(framework, (k,v) -> {
						Tuple toReturn = v == null ? new Tuple(
						new RemoteServiceAdminEventPublisher(context), 1) :
						new Tuple(v.rp, v.usageCount + 1);
						return toReturn;
					}).rp;

		rsaep.start();

		RemoteServiceAdminImpl impl = new RemoteServiceAdminImpl(this, framework, rsaep, serverConnectionManager.getConfiguredProviders(),
				clientConnectionManager, getSupportedIntents(), new ProxyHostBundleFactory(), serverWorkers,
				clientWorkers, timer, config);
		impls.add(impl);
		return impl;
	}

	@Override
	public void ungetService(Bundle bundle, ServiceRegistration<RemoteServiceAdminImpl> registration,
			RemoteServiceAdminImpl service) {
		impls.remove(service);
		service.close();

		Framework framework = bundleFrameworks.remove(bundle);

		AtomicReference<RemoteServiceAdminEventPublisher> toClose = new AtomicReference<>();
		publisherReferenceCounts
			.computeIfPresent(framework, (k,v) -> {
					toClose.set(null);
					if(v.usageCount == 1) {
						toClose.set(v.rp);
						return null;
					} else {
						return new Tuple(v.rp, v.usageCount - 1);
					}
				});

		ofNullable(toClose.get())
			.ifPresent(RemoteServiceAdminEventPublisher::destroy);
	}

	public void close() {
		serverConnectionManager.close();
		clientConnectionManager.close();
	}

	public List<String> getSupportedIntents() {
		List<String> intents = new ArrayList<>();
		intents.add("asyncInvocation");
		intents.add("osgi.basic");
		intents.add("osgi.async");
		intents.addAll(Arrays.asList(config.additional_intents()));
		if(serverConnectionManager.getConfiguredProviders().stream()
			.anyMatch(RemotingProvider::isSecure)) {
			intents.add("confidentiality.message");
			intents.add("osgi.confidential");
		}
		return intents;
	}

	Collection<RemoteServiceAdminImpl> getRemoteServiceAdmins() {
		return impls.stream().collect(toList());
	}

}
