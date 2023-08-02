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
package org.eclipse.ot.rsa.distribution.provider.promise;

import static org.eclipse.ot.rsa.distribution.provider.promise.PromiseHandler.getHandler;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.eclipse.ot.rsa.distribution.provider.promise.PromiseHandler.ProxyDeferred;
import org.eclipse.ot.rsa.distribution.provider.promise.PromiseHandler.ProxyPromise;

import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

/**
 * This is a bit odd class now. In the original distribution there was special
 * handling for the
 */
public class RSAPromiseFactory {

	/**
	 * Return if the given class is a Promise class
	 *
	 * @param osgi the type
	 * @return true if the Promise class
	 */
	public static boolean isPromise(Class<?> osgi) {
		if (osgi.isPrimitive() || osgi.isArray() || Number.class.isAssignableFrom(osgi) || osgi == String.class)
			return false;

		return PromiseHandler.isPromise(osgi);
	}

	/**
	 * Return a Promise based on a Future
	 * <p>
	 * This is a Netty Promise/Future -> OSGi Promise
	 *
	 * @param type the OSGi promise type
	 * @param workers the event executors
	 * @return a Netty Future
	 */
	public static Function<Future<?>, Object> nettyToOSGi(Class<?> type, Executor workers) {
		PromiseHandler handler = getHandler(type);
		ProxyDeferred osgi = handler.deferred();

		return netty -> {
			netty.addListener(f -> {
				Throwable t = f.cause();
				if (t != null) {
					osgi.fail(t);
				} else {
					osgi.resolve(f.getNow());
				}
			});
			return osgi.getPromise()
				.getActual();
		};
	}

	/**
	 * Return a function to turn an OSGi promise into a corresponding Netty
	 * Future. The Netty Future will be resolved according to the OSGi Promise.
	 * <p>
	 * This is OSGi Promise -> Future
	 *
	 * @return a function
	 */
	public static Function<Object, Future<Object>> osgiToNetty(Class<?> type) {
		PromiseHandler handler = getHandler(type);
		return o -> {
			Promise<Object> netty = ImmediateEventExecutor.INSTANCE.newPromise();
			ProxyPromise promise = handler.wrap(o);
			promise.onResolve(() -> {
				try {
					Throwable failure = promise.getFailure();
					if (failure != null) {
						netty.setFailure(failure);
					} else {
						netty.setSuccess(promise.getValue());
					}
				} catch (InterruptedException e) {
					netty.tryFailure(e);
				} catch (InvocationTargetException e) {
					netty.tryFailure(e);
				}
			});
			return netty;
		};
	}

	/**
	 * Return a function that provides a Netty Promise that implements the OSGi
	 * Promise or TODO not implementing OSGi, does this work?
	 * <p>
	 * This is a Netty Promise
	 *
	 * @param type the promise class
	 * @param timer the timer associated with a Netty Promise
	 * @return a Netty promise implementing OSGi
	 */
	public static Function<EventExecutor, Promise<Object>> nettyWithOSGi(Class<?> type, Timer timer) {
		return ee -> {
			return ee.newPromise();
		};
	}

}
