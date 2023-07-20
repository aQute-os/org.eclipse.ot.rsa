package org.eclipse.ot.rsa.distribution.provider.promise;

import java.lang.reflect.InvocationTargetException;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import org.eclipse.ot.rsa.distribution.util.ClassSpace;
import org.eclipse.ot.rsa.distribution.util.ClassSpace.ActualTypeName;
import org.eclipse.ot.rsa.distribution.util.ClassSpace.Proxied;
import org.eclipse.ot.rsa.distribution.util.Utils;

class PromiseHandler {
	final static PromiseHandler dummy = new PromiseHandler();

	@ActualTypeName("org.osgi.util.promise.Promise")
	interface ProxyPromise extends Proxied {
		void onResolve(Runnable r);

		Throwable getFailure() throws InterruptedException;

		Object getValue() throws InvocationTargetException, InterruptedException;

		boolean isDone();
	}

	@ActualTypeName("org.osgi.util.promise.Deferred")
	interface ProxyDeferred extends Proxied {
		void resolve(Object value);

		void fail(Throwable throwable);

		ProxyPromise getPromise();
	}

	@ActualTypeName("org.osgi.util.promise.PromiseFactory")
	interface ProxyPromiseFactory extends Proxied {
		ProxyDeferred deferred();
	}

	final static WeakHashMap<ClassLoader, PromiseHandler>	handlers	= new WeakHashMap<>();
	final Class<?>											promiseType;
	final ProxyPromiseFactory								factory;
	final ClassSpace										classSpace;

	PromiseHandler(ClassSpace classSpace, Class<?> actual) {
		try {
			this.classSpace = classSpace;
			this.promiseType = actual;
			Executor dummy = null;
			this.factory = this.classSpace.newInstance(ProxyPromiseFactory.class, dummy);
		} catch (Throwable e) {
			throw Utils.duck(e);
		}
	}

	public PromiseHandler() {
		promiseType = String.class;
		classSpace = new ClassSpace(PromiseHandler.class.getClassLoader());
		factory = null;
	}

	ProxyDeferred deferred() {
		assert factory != null : "class loader had no Promise so we could never get here";
		return factory.deferred();
	}

	ProxyPromise wrap(Object o) {
		assert factory != null : "class loader had no Promise so we could never get here";
		assert promiseType.isInstance(o);
		return classSpace.proxy(ProxyPromise.class, o);
	}

	static PromiseHandler getHandler(Class<?> type) {
		synchronized (handlers) {
			return handlers.computeIfAbsent(type.getClassLoader(), PromiseHandler::create);
		}
	}

	public static boolean isPromise(Class<?> osgi) {
		PromiseHandler h = getHandler(osgi);
		return h.promiseType.isAssignableFrom(osgi);
	}

	private static PromiseHandler create(ClassLoader l) {
		if (l != null)
			try {
				ClassSpace classSpace = new ClassSpace(l);
				Class<?> promiseType = classSpace.loadActual(ProxyPromise.class);
				if (promiseType != null) {
					return new PromiseHandler(classSpace, promiseType);
				}
			} catch (ClassNotFoundException e) {}
		return dummy;
	}

}
