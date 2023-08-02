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
package org.eclipse.ot.rsa.distribution.provider.pushstream;

import static java.util.Arrays.stream;
import static java.util.Collections.singletonMap;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage;
import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage.CacheKey;
import org.eclipse.ot.rsa.distribution.provider.promise.RSAPromiseFactory;
import org.osgi.framework.ServiceException;

import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;

public class PushStreamFactory {

	private static final String	PUSH_STREAM_TYPE			= "org.osgi.util.pushstream.PushStream";
	private static final String	PUSH_STREAM_PROVIDER_TYPE	= "org.osgi.util.pushstream.PushStreamProvider";
	private static final String	PUSH_STREAM_BUILDER_TYPE	= "org.osgi.util.pushstream.PushStreamBuilder";
	private static final String	PUSH_EVENT_SOURCE_TYPE		= "org.osgi.util.pushstream.PushEventSource";

	public interface OnConnect<T> {
		void connect(CacheKey key, EventExecutor worker, Future<?> closeFuture, ToLongFunction<T> pushData,
			Consumer<Exception> pushClose);
	}

	public interface DataStreamFactory {
		DataStream apply(ToLongFunction<Object> onData, Consumer<Throwable> onTerminal, Object connectTo);
	}

	public interface DataStream {
		void open();

		void asyncBackPressure(long bp);

		void close();

		Future<Void> closeFuture();
	}

	public static Function<Future<?>, Object> pushStreamHandler(Class<?> pushStream, EventExecutorGroup executor,
		OnConnect<Object> onConnect, Consumer<CacheKey> onClose) throws Exception {

		Class<?> providerClass = pushStream.getClassLoader()
			.loadClass(PUSH_STREAM_PROVIDER_TYPE);
		Class<?> pushEventSourceClass = pushStream.getClassLoader()
			.loadClass(PUSH_EVENT_SOURCE_TYPE);
		Class<?> builderClass = pushStream.getClassLoader()
			.loadClass(PUSH_STREAM_BUILDER_TYPE);

		Object provider = providerClass.getConstructor()
			.newInstance();

		Method buildStream = providerClass.getMethod("buildStream", pushEventSourceClass);
		Method withExecutor = builderClass.getMethod("withExecutor", Executor.class);
		Method withScheduler = builderClass.getMethod("withScheduler", ScheduledExecutorService.class);
		Method unbuffered = builderClass.getMethod("unbuffered");
		Method build = builderClass.getMethod("build");

		BiFunction<CacheKey, EventExecutor, Object> eventSource = createPushEventSource(pushEventSourceClass, onConnect,
			onClose);

		return f -> {
			CacheKey id;
			try {
				Object[] value = (Object[]) f.sync()
					.getNow();
				id = new CacheKey((UUID) value[0], (Integer) value[1]);
			} catch (InterruptedException ie) {
				Thread.currentThread()
					.interrupt();
				throw new ServiceException("Interrupted while waiting for a remote service response",
					ServiceException.REMOTE, ie);
			}

			try {
				EventExecutor e = executor.next();
				Object builder = buildStream.invoke(provider, eventSource.apply(id, e));
				builder = withExecutor.invoke(builder, e);
				builder = withScheduler.invoke(builder, e);
				builder = unbuffered.invoke(builder);
				return build.invoke(builder);
			} catch (InvocationTargetException ite) {
				throw new ServiceException("A problem occurred building a PushStream", ServiceException.REMOTE,
					ite.getCause());
			} catch (IllegalAccessException iae) {
				throw new ServiceException("A problem occurred building a PushStream", ServiceException.REMOTE, iae);
			}
		};

	}

	public static Function<Future<?>, Object> pushEventSourceHandler(Class<?> pushEventSource,
		EventExecutorGroup executor, OnConnect<Object> onConnect, Consumer<CacheKey> onClose) throws Exception {

		BiFunction<CacheKey, EventExecutor, Object> eventSource = createPushEventSource(pushEventSource, onConnect,
			onClose);

		return f -> {
			CacheKey id;
			try {
				Object[] value = (Object[]) f.sync()
					.getNow();
				id = new CacheKey((UUID) value[0], (Integer) value[1]);
			} catch (InterruptedException ie) {
				Thread.currentThread()
					.interrupt();
				throw new ServiceException("Interrupted while waiting for a remote service response",
					ServiceException.REMOTE, ie);
			}
			EventExecutor e = executor.next();
			return eventSource.apply(id, e);
		};

	}

	private static BiFunction<CacheKey, EventExecutor, Object> createPushEventSource(Class<?> pushEventSourceClass,
		OnConnect<Object> onConnect, Consumer<CacheKey> onClose) throws Exception {
		return new CustomPushEventSourceFactory(pushEventSourceClass, onConnect, onClose);
	}

	private static class CustomPushEventSourceFactory implements BiFunction<CacheKey, EventExecutor, Object> {

		private static final String			CUSTOM_IMPL_NAME		= PushEventSourceImpl.class.getName();
		private static final String			CUSTOM_RESOURCE_NAME	= resourceName(CUSTOM_IMPL_NAME);

		private final OnConnect<Object>		onConnect;
		private final Consumer<CacheKey>	onClose;

		private final Constructor<?>		pesConstructor;

		public CustomPushEventSourceFactory(Class<?> pushEventSourceClass, OnConnect<Object> onConnect,
			Consumer<CacheKey> onClose) throws Exception {
			this.onConnect = onConnect;
			this.onClose = onClose;

			ClassLoader customImplLoader = getCustomImplLoader(pushEventSourceClass,
				singletonMap(CUSTOM_IMPL_NAME, CUSTOM_RESOURCE_NAME), "io.netty.util.concurrent",
				AbstractRSAMessage.CacheKey.class.getName(), PushStreamFactory.OnConnect.class.getName());

			pesConstructor = customImplLoader.loadClass(CUSTOM_IMPL_NAME)
				.getConstructor(CacheKey.class, OnConnect.class, Consumer.class, EventExecutor.class);
			pesConstructor.setAccessible(true);
		}

		@Override
		public Object apply(CacheKey key, EventExecutor executor) {
			try {
				return pesConstructor.newInstance(key, onConnect, onClose, executor);
			} catch (InvocationTargetException e) {
				// TODO Auto-generated catch block
				throw new RuntimeException(e.getTargetException());
			} catch (Exception e) {
				// TODO Auto-generated catch block
				throw new RuntimeException(e);
			}
		}
	}

	private static ClassLoader getCustomImplLoader(Class<?> pushEventSourceClass,
		Map<String, String> customImplToCustomResource, String safePackage, String... safeClasses) {

		ClassLoader factoryLoader = PushStreamFactory.class.getClassLoader();

		ClassLoader customImplLoader = new ClassLoader(pushEventSourceClass.getClassLoader()) {

			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith(safePackage) || Arrays.stream(safeClasses)
					.anyMatch(s -> name.equals(s))) {
					return factoryLoader.loadClass(name);
				}

				String resource = null;
				if (customImplToCustomResource.containsKey(name)) {
					resource = customImplToCustomResource.get(name);
				}

				if (resource != null) {
					return doLoad(name, resource);
				}

				return super.loadClass(name, resolve);
			}

			private Class<?> doLoad(String name, String resource) throws ClassNotFoundException {
				try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
					InputStream is = factoryLoader.getResourceAsStream(resource)) {

					byte[] b = new byte[4096];
					int r;
					while ((r = is.read(b)) > -1) {
						baos.write(b, 0, r);
					}

					return defineClass(name, baos.toByteArray(), 0, baos.size(),
						RSAPromiseFactory.class.getProtectionDomain());
				} catch (IOException ioe) {
					// TODO log this
					throw new ClassNotFoundException(name, ioe);
				}

			}

		};
		return customImplLoader;
	}

	private static final String	PUSHSTREAM_CONNECTOR_CUSTOM_IMPL_NAME		= PushStreamPushEventConsumerImpl.class
		.getName();
	private static final String	PUSHSTREAM_CONNECTOR_CUSTOM_RESOURCE_NAME	= resourceName(
		PUSHSTREAM_CONNECTOR_CUSTOM_IMPL_NAME);

	private static final String	COMMON_PARENT_CUSTOM_IMPL_NAME				= AbstractPushEventConsumerImpl.class
		.getName();
	private static final String	COMMON_PARENT_CUSTOM_RESOURCE_NAME			= resourceName(
		COMMON_PARENT_CUSTOM_IMPL_NAME);

	public static DataStreamFactory pushStreamConnector(Class<?> pushStream, Timer timer) throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put(PUSHSTREAM_CONNECTOR_CUSTOM_IMPL_NAME, PUSHSTREAM_CONNECTOR_CUSTOM_RESOURCE_NAME);
		map.put(COMMON_PARENT_CUSTOM_IMPL_NAME, COMMON_PARENT_CUSTOM_RESOURCE_NAME);
		return new StreamConsumerFactory(pushStream, map, PUSHSTREAM_CONNECTOR_CUSTOM_IMPL_NAME, timer);
	}

	private static final String	PUSH_EVENT_SOURCE_CONNECTOR_CUSTOM_IMPL_NAME		= PushEventSourcePushEventConsumerImpl.class
		.getName();
	private static final String	PUSH_EVENT_SOURCE_CONNECTOR_CUSTOM_RESOURCE_NAME	= resourceName(
		PUSH_EVENT_SOURCE_CONNECTOR_CUSTOM_IMPL_NAME);

	public static DataStreamFactory pushEventSourceConnector(Class<?> pushEventSource, Timer timer) throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put(PUSH_EVENT_SOURCE_CONNECTOR_CUSTOM_IMPL_NAME, PUSH_EVENT_SOURCE_CONNECTOR_CUSTOM_RESOURCE_NAME);
		map.put(COMMON_PARENT_CUSTOM_IMPL_NAME, COMMON_PARENT_CUSTOM_RESOURCE_NAME);
		return new StreamConsumerFactory(pushEventSource, map, PUSH_EVENT_SOURCE_CONNECTOR_CUSTOM_IMPL_NAME, timer);
	}

	private static class StreamConsumerFactory implements DataStreamFactory {

		private final Constructor<?>	constructor;
		private final Timer				timer;

		public StreamConsumerFactory(Class<?> connectorTargetType, Map<String, String> resourceMappings,
			String connectorClassName, Timer timer) throws Exception {

			this.timer = timer;
			ClassLoader customImplLoader = getCustomImplLoader(connectorTargetType, resourceMappings, "io.netty.util",
				PushStreamFactory.DataStream.class.getName(), ServiceException.class.getName());

			constructor = customImplLoader.loadClass(connectorClassName)
				.getConstructor(ToLongFunction.class, Consumer.class, connectorTargetType, Timer.class);
			constructor.setAccessible(true);
		}

		@Override
		public DataStream apply(ToLongFunction<Object> onData, Consumer<Throwable> onTerminal, Object toConnectTo) {
			try {
				return (DataStream) constructor.newInstance(onData, onTerminal, toConnectTo, timer);
			} catch (InvocationTargetException e) {
				// TODO Auto-generated catch block
				throw new RuntimeException(e.getTargetException());
			} catch (Exception e) {
				// TODO Auto-generated catch block
				throw new RuntimeException(e);
			}
		}
	}

	public static boolean isPushStream(Class<?> returnType) {
		return locateInterface(returnType, PUSH_STREAM_TYPE) != null;
	}

	public static boolean isPushEventSource(Class<?> returnType) {
		return locateInterface(returnType, PUSH_EVENT_SOURCE_TYPE) != null;
	}

	private static Class<?> locateInterface(Class<?> type, String toCheck) {
		Class<?> result = null;

		do {
			result = concat(of(type), stream(type.getInterfaces())).filter(c -> toCheck.equals(c.getName()))
				.findFirst()
				.orElse(null);
		} while (result == null && (type = type.getSuperclass()) != null);

		return result;
	}

	private static String resourceName(String fqn) {
		return fqn.replace('.', '/')
			.concat(".class");
	}
}
