package org.eclipse.ot.rsa.singlethread.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.ot.rsa.logger.util.HLogger;

import aQute.lib.exceptions.Exceptions;
import aQute.lib.io.IO;

/**
 * The purpose of this class is to execute all calls, in sequence, in a single
 * thread. This has the advantage that all internal data manipulation is
 * unsynchronized. It makes it a lot easier to reason about a class when you
 * know all interactions are not interspersed.
 * <p>
 * To use this, you need to define all methods you want to call on an interface.
 * All methods in this interface must be void since they will be executed
 * asynchronously.
 * <p>
 * You call {@link #create(Class, Function, HLogger)} with this interface class
 * and pass a function to create the state instance. (This is a function so you
 * can get this object that manages the calls as an instance in the
 * constructor.) This static method returns a proper instance that implements
 * the interface. Any call on the interface is scheduled on a single thread
 * associated with this object.
 * <p>
 * Make sure you close this object when no longer needed.
 */
public class SingleThread implements AutoCloseable, InvocationHandler {

	final Object					impl;
	final ScheduledExecutorService	executor	= Executors.newSingleThreadScheduledExecutor();
	final HLogger					log;
	final AtomicBoolean				closed		= new AtomicBoolean(false);
	volatile Method					lastCall;

	SingleThread(Function<SingleThread, ?> create, HLogger log) {
		this.log = log;
		this.impl = create.apply(this);
	}

	@SuppressWarnings("unchecked")
	public static <T> T create(Class<T> clazz, Function<SingleThread, T> create, HLogger log) {

		assert log != null && clazz != null && create != null;
		assert clazz.isInterface() : "only works for interfaces";
		assert nonVoidMethods(clazz).isEmpty() : "only works for void methods " + nonVoidMethods(clazz);

		SingleThread st = new SingleThread(create, log);
		T proxy = (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[] {
			clazz, AutoCloseable.class
		}, st);

		return proxy;
	}

	@Override
	public void close() throws Exception {
		if (closed.getAndSet(true))
			return;

		executor.shutdownNow();
		if (impl instanceof AutoCloseable) {
			IO.close((AutoCloseable) impl);
		}
		boolean ok = executor.awaitTermination(10_000, TimeUnit.MILLISECONDS);
		if (!ok) {
			log.error("did not terminate single thread execution in 10secs, last call %s", lastCall);
		}
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (closed.get())
			return null;

		if (method.getDeclaringClass() == Object.class)
			return method.invoke(impl, args);

		if (method.getDeclaringClass() == AutoCloseable.class) {
			IO.close(this);
			return null;
		}

		executor.execute(() -> {
			lastCall = method;
			try {
				method.invoke(impl, args);

			} catch (Throwable t) {
				log.unexpected(Exceptions.unrollCause(t));
			} finally {
				lastCall = null;
			}
		});

		return null;
	}

	private static List<Method> nonVoidMethods(Class<?> clazz) {
		List<Method> nonVoidMethods = new ArrayList<>();
		for (Method m : clazz.getMethods()) {

			if (!m.getDeclaringClass()
				.isInterface())
				continue;

			Class<?> returnType = m.getReturnType();
			if (returnType != void.class) {
				nonVoidMethods.add(m);
			}
		}
		return nonVoidMethods;
	}

	public interface RunnableWithException {
		void run() throws Exception;
	}
	public void schedule(RunnableWithException r, long delay) {
		executor.schedule(() -> {
			try {
				r.run();
			} catch (Throwable t) {
				log.unexpected(t);
			}
		}, delay, TimeUnit.MILLISECONDS);
	}
}
