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
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;

import com.paremus.dosgi.net.client.ClientConnectionManager;
import com.paremus.dosgi.net.config.Config;
import com.paremus.dosgi.net.server.RemotingProvider;
import com.paremus.dosgi.net.server.ServerConnectionManager;
import com.paremus.net.encode.EncodingSchemeFactory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocalThread;

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
	
	private final AtomicInteger threadId = new AtomicInteger(1);
	private final EventExecutorGroup serverWorkers;
	private Config config;
	
	public RemoteServiceAdminFactoryImpl(Config config, EncodingSchemeFactory esf, ByteBufAllocator allocator) {
		this.config = config;
		clientConnectionManager = new ClientConnectionManager(config, esf, allocator);
		serverConnectionManager = new ServerConnectionManager(config, esf, allocator);
		 
		serverWorkers = new DefaultEventExecutorGroup(config.server_worker_threads(), r -> {
								Thread thread = new FastThreadLocalThread(r, 
										"Paremus RSA distribution server Worker " + threadId.getAndIncrement());
								thread.setDaemon(true);
								return thread;
							});
	}
	
	
	@Override
	public RemoteServiceAdminImpl getService(Bundle bundle, ServiceRegistration<RemoteServiceAdminImpl> registration) {
		Framework framework = bundle.getBundleContext().getBundle(0).adapt(Framework.class);
		
		bundleFrameworks.put(bundle, framework);
		
		RemoteServiceAdminEventPublisher rsaep = publisherReferenceCounts
				.compute(framework, (k,v) -> {
						Tuple toReturn = v == null ? new Tuple(
						new RemoteServiceAdminEventPublisher(framework.getBundleContext()), 1) :
						new Tuple(v.rp, v.usageCount + 1);
						return toReturn;
					}).rp;
		
		rsaep.start();
		
		return new RemoteServiceAdminImpl(framework, rsaep, serverConnectionManager.getConfiguredProviders(), 
				clientConnectionManager, getSupportedIntents(), new ProxyHostBundleFactory(), serverWorkers, 
				config);
	}

	@Override
	public void ungetService(Bundle bundle, ServiceRegistration<RemoteServiceAdminImpl> registration,
			RemoteServiceAdminImpl service) {
		service.close();
		
		Framework framework = bundleFrameworks.remove(bundle);
		
		AtomicReference<RemoteServiceAdminEventPublisher> toClose = new AtomicReference<RemoteServiceAdminEventPublisher>();
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
		try {
			serverWorkers.shutdownGracefully(250, 1000, MILLISECONDS).await(2000);
		} catch (InterruptedException ie) {
			
		}
	}

	public List<String> getSupportedIntents() {
		List<String> intents = new ArrayList<>();
		intents.add("asyncInvocation");
		if(serverConnectionManager.getConfiguredProviders().stream()
			.anyMatch(RemotingProvider::isSecure)) {
			intents.add("confidentiality.message");
		}
		return intents;
	}
	
}
