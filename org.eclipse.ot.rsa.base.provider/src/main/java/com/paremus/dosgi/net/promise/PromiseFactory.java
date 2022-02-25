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
package com.paremus.dosgi.net.promise;

import static java.util.Arrays.stream;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.osgi.framework.namespace.PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE;
import static org.osgi.framework.namespace.PackageNamespace.PACKAGE_NAMESPACE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleRevision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

/**
 * This factory/helper is designed to abstract away the complexities of dealing
 * with Promises. RSA has to transparently cope with a great many class spaces,
 * so we don't ever use the Promise API directly. Instead we use Netty promises
 * internally, and use this helper to convert between types and class spaces.
 *
 * <p>
 * Note that this type must not ever import org.osgi.util.promise or
 * org.osgi.util.function!
 *
 */
public class PromiseFactory {

	private static final Logger LOG = LoggerFactory.getLogger(PromiseFactory.class.getName());

	private static final Version V_1 = Version.parseVersion("1.0.0");
	private static final Version V_1_1 = Version.parseVersion("1.1.0");
	private static final Version V_1_2 = Version.parseVersion("1.2.0");
	private static final Version V_2 = Version.parseVersion("2.0.0");

	private static final String PROMISE_PKG = "org.osgi.util.promise";
	private static final String PROMISE_TYPE = PROMISE_PKG + ".Promise";

	public static Function<Object, Future<Object>> toNettyFutureAdapter(Class<?> type) {
		Function<Object, Future<Object>> toReturn = null;

		Class<?> promise = locatePromiseInterface(type);

		if (promise != null) {
			Version promiseVersion = getVersion(promise);

			if (promiseVersion.compareTo(V_1) < 0 || promiseVersion.compareTo(V_2) >= 0) {
				LOG.warn("The promise type from bundle {} with version {} may not be usable with this RSA",
						FrameworkUtil.getBundle(promise), promiseVersion);
			}
			try {
				toReturn = new ToNettyFuture(promise);
			} catch (Exception nsme) {
				LOG.warn("There was an error trying to integrate with the Promise type from {}",
						FrameworkUtil.getBundle(promise), nsme);
			}
		}

		return toReturn;
	}

	private static Class<?> locatePromiseInterface(Class<?> type) {
		Class<?> result = null;

		do {
			result = concat(of(type), stream(type.getInterfaces())).filter(c -> PROMISE_TYPE.equals(c.getName()))
					.findFirst().orElse(null);
		} while (result == null && (type = type.getSuperclass()) != null);

		return result;
	}

	public static boolean isPromise(Class<?> type) {
		return locatePromiseInterface(type) != null;
	}

	private static Version getVersion(Class<?> promise) {
		Version result;

		Bundle b = FrameworkUtil.getBundle(promise);

		if (b == null) {
			result = V_1_1;
		} else {
			BundleRevision revision = b.adapt(BundleRevision.class);
			if (revision == null) {
				result = V_1_1;
			} else {
				result = revision.getCapabilities(PACKAGE_NAMESPACE).stream()
					.map(bc -> bc.getAttributes())
					.filter(m -> PROMISE_PKG.equals(m.get(PACKAGE_NAMESPACE)))
					.map(m -> (Version) m.get(CAPABILITY_VERSION_ATTRIBUTE))
					.findFirst().orElse(Version.emptyVersion);
			}
		}

		return result;
	}

	private static class ToNettyFuture implements Function<Object, Future<Object>> {

		private final Method onResolve;
		private final Method isDone;
		private final Method getValue;
		private final Method getFailure;

		public ToNettyFuture(Class<?> promise) throws NoSuchMethodException {
			this.onResolve = promise.getMethod("onResolve", Runnable.class);
			this.isDone = promise.getMethod("isDone");
			this.getValue = promise.getMethod("getValue");
			this.getFailure = promise.getMethod("getFailure");
		}

		@SuppressWarnings("unchecked")
		@Override
		public Future<Object> apply(Object t) {
			Future<Object> toReturn;
			if (t instanceof Future) {
				toReturn = (Future<Object>) t;
			} else {
				try {
					if ((Boolean) isDone.invoke(t)) {
						toReturn = fastPath(t);
					} else {
						toReturn = chain(t);
					}
				} catch (InvocationTargetException ite) {
					toReturn = ImmediateEventExecutor.INSTANCE.newFailedFuture(ite.getTargetException());
				} catch (Exception e) {
					toReturn = ImmediateEventExecutor.INSTANCE.newFailedFuture(e);
				}
			}
			return toReturn;
		}

		private Future<Object> fastPath(Object t) {
			Future<Object> toReturn;
			try {
				Object o = getFailure.invoke(t);
				if (o != null) {
					toReturn = ImmediateEventExecutor.INSTANCE.newFailedFuture((Throwable) o);
				} else {
					toReturn = ImmediateEventExecutor.INSTANCE.newSucceededFuture(getValue.invoke(t));
				}
			} catch (InvocationTargetException ite) {
				toReturn = ImmediateEventExecutor.INSTANCE.newFailedFuture(ite.getTargetException());
			} catch (Exception e) {
				toReturn = ImmediateEventExecutor.INSTANCE.newFailedFuture(e);
			}
			return toReturn;
		}

		private Future<Object> chain(Object t) throws IllegalAccessException, InvocationTargetException {
			Promise<Object> p = ImmediateEventExecutor.INSTANCE.newPromise();
			onResolve.invoke(t, (Runnable) () -> {
				try {
					Object o = getFailure.invoke(t);
					if (o != null) {
						p.setFailure((Throwable) o);
					} else {
						p.setSuccess(getValue.invoke(t));
					}
				} catch (InvocationTargetException ite) {
					p.setFailure(ite.getTargetException());
				} catch (Exception e) {
					p.setFailure(e);
				}
			});
			return p;
		}
	}

	public static Function<EventExecutor, Promise<Object>> nettyPromiseCreator(Class<?> promise,
			Timer timer) {

		Function<EventExecutor, Promise<Object>> toReturn = null;

		if(promise != null) {
			Version promiseVersion = getVersion(promise);
			if (promiseVersion.compareTo(V_1) >= 0 && promiseVersion.compareTo(V_1_2) < 0) {
				try {
					toReturn = new CustomPromiseFactory(promise, promiseVersion, timer);
				} catch (Exception nsme) {
					LOG.warn("An error occurred trying to create high-performance promise integration for promises from bundle {}",
							FrameworkUtil.getBundle(promise), nsme);
				}
			} else if (promiseVersion.compareTo(V_1) < 0 || promiseVersion.compareTo(V_2) >= 0) {
				LOG.warn("The Promises from bundle {} are at an incompatible version {} and so integration is not possible",
						FrameworkUtil.getBundle(promise), promiseVersion);
			}
		}

		if (toReturn == null) {
			toReturn = e -> e.newPromise();
		}

		return toReturn;
	}

	private static class CustomPromiseFactory implements Function<EventExecutor, Promise<Object>> {

		private static final String CUSTOM_IMPL_NAME_V1 = "com.paremus.dosgi.net.promise.FuturePromise_v1";
		private static final String CUSTOM_RESOURCE_NAME_V1 = "com/paremus/dosgi/net/promise/FuturePromise_v1.class";
		private static final String CUSTOM_IMPL_NAME_V1_1 = "com.paremus.dosgi.net.promise.FuturePromise_v1_1";
		private static final String CUSTOM_RESOURCE_NAME_V1_1 = "com/paremus/dosgi/net/promise/FuturePromise_v1_1.class";

		private final Constructor<?> promiseConstructor;

		private final Timer timer;

		public CustomPromiseFactory(Class<?> promiseClass, Version promiseVersion,
				Timer timer) throws NoSuchMethodException, SecurityException, ClassNotFoundException {
			this.timer = timer;

			ClassLoader customImplLoader = getCustomImplLoader(promiseClass);

			promiseConstructor = customImplLoader.loadClass(V_1_1.compareTo(promiseVersion) > 0 ?
					CUSTOM_IMPL_NAME_V1 : CUSTOM_IMPL_NAME_V1_1)
				.getDeclaredConstructor(EventExecutor.class, Timer.class);

			promiseConstructor.setAccessible(true);

		}

		private ClassLoader getCustomImplLoader(Class<?> promiseClass) {
			ClassLoader factoryLoader = PromiseFactory.class.getClassLoader();
			ClassLoader promiseLoader = promiseClass.getClassLoader();

			ClassLoader customImplLoader = new ClassLoader() {

				@Override
				protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					if(name.startsWith("io.netty.util")) {
						return factoryLoader.loadClass(name);
					} else if (name.startsWith("org.osgi.util")) {
						return promiseLoader.loadClass(name);
					}

					String resource = null;
					if(CUSTOM_IMPL_NAME_V1.equals(name)) {
						resource = CUSTOM_RESOURCE_NAME_V1;
					} else if (CUSTOM_IMPL_NAME_V1_1.equals(name)) {
						resource = CUSTOM_RESOURCE_NAME_V1_1;
					}

					if(resource != null) {
						Class<?> cls = findLoadedClass(name);
						if(cls == null) {
							cls = doLoad(name, resource);
							if(resolve) {
								resolveClass(cls);
							}
						}
						return cls;
					}

					return super.loadClass(name, resolve);
				}

				private Class<?> doLoad(String name, String resource) throws ClassNotFoundException {
					try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
							InputStream is = factoryLoader.getResourceAsStream(resource)) {

						byte[] b = new byte[4096];
						int r;
						while((r = is.read(b)) > -1) {
							baos.write(b, 0, r);
						}

						return defineClass(name, baos.toByteArray(), 0, baos.size(),
								PromiseFactory.class.getProtectionDomain());
					} catch (IOException ioe) {
						LOG.error("Unable to load the type {}", name, ioe);
						throw new ClassNotFoundException(name, ioe);
					}

				}

			};
			return customImplLoader;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Promise<Object> apply(EventExecutor executor) {
			try {
				return (Promise<Object>) promiseConstructor.newInstance(executor, timer);
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e.getTargetException());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static Function<Future<?>, Object> fromNettyFutureAdapter(Class<?> promise,
			EventExecutorGroup workers) {

		Function<Future<?>, Object> toReturn = null;
		if (promise != null) {
			try {
				Function<Future<?>, Object> fallback = new FromNettyPromiseDefaultImpl(promise, workers);

				toReturn = f -> {
					Object o;
					if(promise.isInstance(f)) {
						o = f;
					} else {
						o = fallback.apply(f);
					}

					return o;
				};
			} catch (Exception nsme) {
				LOG.warn("There was an error trying to integrate with the Promise type from {}",
						FrameworkUtil.getBundle(promise), nsme);
			}
		}
		return toReturn;
	}

	private static class FromNettyPromiseDefaultImpl implements Function<Future<?>, Object> {

		private static final String DEFAULT_FACTORY_NAME = "org.osgi.util.promise.Deferred";

		private final Supplier<Object> newDeferred;
		private final Function<Object, Object> resolvedPromise;
		private final Function<Throwable, Object> failedPromise;

		private final Method factoryGetPromise;
		private final Method factoryResolve;
		private final Method factoryFail;

		public FromNettyPromiseDefaultImpl(Class<?> promiseClass, EventExecutorGroup workers)
				throws NoSuchMethodException, SecurityException, ClassNotFoundException {

			ClassLoader loader = promiseClass.getClassLoader();

			Class<?> factory = loader.loadClass(DEFAULT_FACTORY_NAME);
			factoryGetPromise = factory.getMethod("getPromise");
			factoryResolve = factory.getMethod("resolve", Object.class);
			factoryFail = factory.getMethod("fail", Throwable.class);

			Supplier<Object> newInstance;
			Function<Object, Object> resolvedInstance;
			Function<Throwable, Object> failedInstance;
			try {
				Class<?> promiseFactoryClass = loader.loadClass("org.osgi.util.promise.PromiseFactory");

				Constructor<?> c = promiseFactoryClass.getConstructor(Executor.class, ScheduledExecutorService.class);

				// Attempt to use different threads for resolve callbacks and timing
				Object promiseExecutors = c.newInstance(workers, workers);

				Method makeDeferred = promiseFactoryClass.getMethod("deferred");
				Method makeResolved = promiseFactoryClass.getMethod("resolved", Object.class);
				Method makeFailed = promiseFactoryClass.getMethod("failed", Throwable.class);

				newInstance = () -> {
						try {
							return makeDeferred.invoke(promiseExecutors);
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
					};

				resolvedInstance = o -> {
						try {
							return makeResolved.invoke(promiseExecutors, o);
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
					};

				failedInstance = t -> {
						try {
							return makeFailed.invoke(promiseExecutors, t);
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
					};
			} catch (Exception ex) {
				Constructor<?> c = factory.getConstructor();
				newInstance = () -> {
						try {
							return c.newInstance();
						} catch (Exception ex2) {
							throw new RuntimeException(ex2);
						}
					};
				resolvedInstance = o -> {
						try {
							return factoryResolve.invoke(c.newInstance(), o);
						} catch (Exception ex2) {
							throw new RuntimeException(ex2);
						}
					};

				failedInstance = t -> {
						try {
							return factoryFail.invoke(c.newInstance(), t);
						} catch (Exception ex2) {
							throw new RuntimeException(ex2);
						}
					};
			}

			newDeferred = newInstance;
			resolvedPromise = resolvedInstance;
			failedPromise = failedInstance;
		}

		@Override
		public Object apply(Future<?> f) {
			Object toReturn;
			try {

				// This check allows a thread switch to be skipped sometimes
				// A single isDone is used to avoid racing the promise
				if(f.isDone()) {
					if(f.isSuccess()) {
						toReturn = resolvedPromise.apply(f.getNow());
					} else {
						toReturn = failedPromise.apply(f.cause());
					}
				} else {
					Object deferred = newDeferred.get();
					f.addListener(x -> {
						try {
							if(f.isSuccess()) {
								factoryResolve.invoke(deferred, f.getNow());
							} else {
								factoryFail.invoke(deferred, f.cause());
							}
						} catch (Exception e) {
							factoryFail.invoke(deferred, e);
						}
					});
					toReturn = factoryGetPromise.invoke(deferred);
				}
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e.getTargetException());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			return toReturn;
		}
	}
}
