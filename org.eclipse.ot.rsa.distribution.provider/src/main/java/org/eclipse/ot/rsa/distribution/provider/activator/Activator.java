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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.osgi.framework.Constants.BUNDLE_ACTIVATOR;
import static org.osgi.framework.Constants.SERVICE_PID;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.ot.rsa.constants.RSAConstants;
import org.eclipse.ot.rsa.distribution.config.DistributionConfig;
import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.osgi.annotation.bundle.Header;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.util.converter.Converters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;

@Header(name = BUNDLE_ACTIVATOR, value = "${@class}")
public class Activator implements BundleActivator {

	private static final Logger							LOG			= LoggerFactory.getLogger(Activator.class);

	private static final ByteBufAllocator				allocator	= new PooledByteBufAllocator(true);

	private Timer										timer;

	private BundleContext								context;

	private boolean										active;

	private Map<String, Object>							config;

	private MultithreadEventLoopGroup					serverIo;

	private MultithreadEventLoopGroup					clientIo;

	private MultithreadEventExecutorGroup				serverWorkers;

	private int											serverWorkQueueMaxLength;

	private MultithreadEventExecutorGroup				clientWorkers;

	private int											clientWorkQueueMaxLength;

	private ManagedServiceFactoryImpl					msf;

	private ServiceRegistration<ManagedServiceFactory>	msfReg;

	private final Lock									stateLock	= new ReentrantLock();

	@Override
	public void start(BundleContext context) throws Exception {

		this.context = context;

		stateLock.lock();
		try {
			active = true;
		} finally {
			stateLock.unlock();
		}

		timer = new HashedWheelTimer(r -> {
			Thread thread = new FastThreadLocalThread(r, "Paremus RSA Timeout worker");
			thread.setDaemon(true);
			return thread;
		}, 100, MILLISECONDS, 16384);

		Dictionary<String, Object> rawConfig = null;

		ServiceReference<ConfigurationAdmin> ref = context.getServiceReference(ConfigurationAdmin.class);
		if (ref != null) {
			ConfigurationAdmin cm = context.getService(ref);
			if (cm != null) {
				rawConfig = cm.getConfiguration(DistributionConfig.PID)
					.getProperties();
			}
		}

		configUpdate(rawConfig);

		ManagedService service = this::configUpdate;

		Hashtable<String, Object> table = new Hashtable<>();
		table.put(SERVICE_PID, DistributionConfig.PID);
		context.registerService(ManagedService.class, service, table);
	}

	private void configUpdate(Dictionary<String, ?> props) throws ConfigurationException {
		ServiceRegistration<?> toUnregister = null;
		ManagedServiceFactoryImpl toDestroy = null;
		EventLoopGroup toDestroy2 = null;
		EventLoopGroup toDestroy3 = null;
		EventExecutorGroup toDestroy4 = null;
		EventExecutorGroup toDestroy5 = null;

		ManagedServiceFactoryImpl toRegister = null;
		ManagedServiceFactoryImpl toPassTo = null;

		stateLock.lock();
		try {
			if (!active) {
				return;
			}

			Map<String, Object> rawConfig;
			if (props == null) {
				rawConfig = new HashMap<>();
			} else {
				rawConfig = ManagedServiceFactoryImpl.toMap(props);
			}

			if (rawConfig.equals(config)) {
				return;
			}

			config = rawConfig;

			DistributionConfig cfg = Converters.standardConverter()
				.convert(rawConfig)
				.to(DistributionConfig.class);

			if (serverIo == null || serverIo.executorCount() != cfg.server_io_threads()) {
				toUnregister = toUnregister == null ? msfReg : toUnregister;
				toDestroy = toDestroy == null ? msf : toDestroy;
				msfReg = null;
				msf = null;
				toDestroy2 = serverIo;

				AtomicInteger ioThreadId = new AtomicInteger(1);

				serverIo = new NioEventLoopGroup(cfg.server_io_threads(), r -> {
					String name = (cfg.share_io_threads() ? "Paremus RSA distribution IO: "
						: "Paremus RSA distribution server IO: ") + ioThreadId.getAndIncrement();
					Thread thread = new FastThreadLocalThread(r, name);
					thread.setDaemon(true);
					return thread;
				});
			}

			if (cfg.share_io_threads()) {
				if (clientIo != serverIo) {
					toUnregister = toUnregister == null ? msfReg : toUnregister;
					toDestroy = toDestroy == null ? msf : toDestroy;
					msfReg = null;
					msf = null;
					toDestroy3 = clientIo;

					clientIo = serverIo;
				}
			} else if (clientIo == null || clientIo == serverIo
				|| clientIo.executorCount() != cfg.client_io_threads()) {
				toUnregister = toUnregister == null ? msfReg : toUnregister;
				toDestroy = toDestroy == null ? msf : toDestroy;
				msfReg = null;
				msf = null;
				toDestroy3 = clientIo == serverIo ? null : clientIo;

				AtomicInteger ioThreadId = new AtomicInteger(1);

				clientIo = new NioEventLoopGroup(cfg.client_io_threads(), r -> {
					String name = "Paremus RSA distribution client IO: " + ioThreadId.getAndIncrement();
					Thread thread = new FastThreadLocalThread(r, name);
					thread.setDaemon(true);
					return thread;
				});
			}

			if (serverWorkers == null || serverWorkers.executorCount() != cfg.server_io_threads()
				|| serverWorkQueueMaxLength != cfg.server_task_queue_depth()) {
				toUnregister = toUnregister == null ? msfReg : toUnregister;
				toDestroy = toDestroy == null ? msf : toDestroy;
				msfReg = null;
				msf = null;
				toDestroy4 = serverWorkers;

				serverWorkQueueMaxLength = cfg.server_task_queue_depth();

				AtomicInteger ioThreadId = new AtomicInteger(1);

				serverWorkers = new RSAExecutorGroup(cfg.server_io_threads(), r -> {
					String name = (cfg.share_io_threads() ? "Paremus RSA Server Worker " : "Paremus RSA Worker ")
						+ ioThreadId.getAndIncrement();
					Thread thread = new FastThreadLocalThread(r, name);
					thread.setDaemon(true);
					return thread;
				}, serverWorkQueueMaxLength);
			}

			if (cfg.share_worker_threads()) {
				if (clientWorkers != serverWorkers) {
					toUnregister = toUnregister == null ? msfReg : toUnregister;
					toDestroy = toDestroy == null ? msf : toDestroy;
					msfReg = null;
					msf = null;
					toDestroy5 = clientWorkers;

					clientWorkers = serverWorkers;
				}
			} else if (clientWorkers == null || clientWorkers == serverWorkers
				|| clientWorkers.executorCount() != cfg.client_worker_threads()
				|| clientWorkQueueMaxLength != cfg.client_task_queue_depth()) {
				toUnregister = toUnregister == null ? msfReg : toUnregister;
				toDestroy = toDestroy == null ? msf : toDestroy;
				msfReg = null;
				msf = null;
				toDestroy5 = clientWorkers == serverWorkers ? null : clientWorkers;

				clientWorkQueueMaxLength = cfg.client_task_queue_depth();

				AtomicInteger ioThreadId = new AtomicInteger(1);

				clientWorkers = new RSAExecutorGroup(cfg.client_io_threads(), r -> {
					String name = "Paremus RSA Client Worker " + ioThreadId.getAndIncrement();
					Thread thread = new FastThreadLocalThread(r, name);
					thread.setDaemon(true);
					return thread;
				}, clientWorkQueueMaxLength);
			}

			if (msf == null) {
				msf = new ManagedServiceFactoryImpl(context, timer, serverIo, clientIo, serverWorkers, clientWorkers,
					allocator);
				toRegister = msf;
			}

			toPassTo = msf;
		} catch (Exception e) {
			LOG.error("An unexpected error occurred processing a configuration update", e);

			toUnregister = msfReg;
			toDestroy = msf;
			msfReg = null;
			msf = null;
			toDestroy2 = serverIo;
			serverIo = null;
			toDestroy3 = clientIo;
			clientIo = null;
			toDestroy4 = serverWorkers;
			serverWorkers = null;
			toDestroy5 = clientWorkers;
			clientWorkers = null;

			toPassTo = null;
		} finally {
			stateLock.unlock();
		}

		destroy(toUnregister, toDestroy, toDestroy2, toDestroy3, toDestroy4, toDestroy5);

		if (toRegister != null) {
			registerMSF(toRegister);
		}

		if (toPassTo != null) {
			toPassTo.updated(RSAConstants.DISTRIBUTION_CONFIGURATION_TYPE, props == null ? new Hashtable<>() : props);
		}
	}

	private void registerMSF(ManagedServiceFactoryImpl toRegister) {
		Hashtable<String, Object> table = new Hashtable<>();
		table.put(SERVICE_PID, TransportConfig.PID);
		ServiceRegistration<ManagedServiceFactory> toUnregister = context.registerService(ManagedServiceFactory.class,
			toRegister, table);
		stateLock.lock();
		try {
			if (msf == toRegister) {
				msfReg = toUnregister;
				toUnregister = null;
				toRegister = null;
			}
		} finally {
			stateLock.unlock();
		}

		destroy(toUnregister, toRegister, null, null, null, null);
	}

	@Override
	public void stop(BundleContext context) throws Exception {

		ServiceRegistration<?> toUnregister = null;
		ManagedServiceFactoryImpl toDestroy = null;
		EventLoopGroup toDestroy2 = null;
		EventLoopGroup toDestroy3 = null;
		EventExecutorGroup toDestroy4 = null;
		EventExecutorGroup toDestroy5 = null;

		stateLock.lock();
		try {
			active = false;
			toUnregister = msfReg;
			msfReg = null;
			toDestroy = msf;
			msf = null;
			toDestroy2 = serverIo;
			serverIo = null;
			toDestroy3 = clientIo;
			clientIo = null;
			toDestroy4 = serverWorkers;
			serverWorkers = null;
			toDestroy5 = clientWorkers;
			clientWorkers = null;
		} finally {
			stateLock.unlock();
		}
		destroy(toUnregister, toDestroy, toDestroy2, toDestroy3, toDestroy4, toDestroy5);

		try {
			awaitTermination(toDestroy2);
			awaitTermination(toDestroy3);
			awaitTermination(toDestroy4);
			awaitTermination(toDestroy5);
		} catch (InterruptedException e) {
			LOG.debug("Will not wait for shutdown as this thread is interrupted", e);
			Thread.currentThread()
				.interrupt();
		}

		timer.stop();
	}

	private void awaitTermination(EventExecutorGroup toDestroy) throws InterruptedException {
		if (toDestroy != null) {
			toDestroy.awaitTermination(3, TimeUnit.SECONDS);
		}
	}

	private void destroy(ServiceRegistration<?> toUnregister, ManagedServiceFactoryImpl toDestroy,
		EventLoopGroup toDestroy2, EventLoopGroup toDestroy3, EventExecutorGroup toDestroy4,
		EventExecutorGroup toDestroy5) {

		if (toUnregister != null) {
			try {
				toUnregister.unregister();
			} catch (IllegalStateException ise) {}
		}

		if (toDestroy != null) {
			toDestroy.destroy();
		}

		shutdown(toDestroy2);
		shutdown(toDestroy3);
		shutdown(toDestroy4);
		shutdown(toDestroy5);
	}

	private void shutdown(EventExecutorGroup toDestroy) {
		if (toDestroy != null) {
			toDestroy.shutdownGracefully(1, 2, TimeUnit.SECONDS);
		}
	}
}
