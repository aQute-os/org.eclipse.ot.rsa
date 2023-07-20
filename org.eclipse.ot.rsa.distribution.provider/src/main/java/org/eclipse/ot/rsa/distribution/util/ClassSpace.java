package org.eclipse.ot.rsa.distribution.util;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Solves the problem of living in multiple class spaces. In the beginning the
 * OSGi promises happened in multiple versions. Using the promise class by the
 * RSA constrained the version for anybody using it. So this class allows you
 * create an interface in your own class space that is used as a 'proxy' around
 * the actual type in another class space. Your interface must have the same
 * signatures except for other 'proxied' types.
 * <p>
 * Types in arguments that are proxied, are replaced by their underlying value.
 * Return types that are declared as proxied types are converted to a proxy.
 */

public class ClassSpace {
	final static Lookup lookup = MethodHandles.lookup();

	/**
	 * Specifies the actual type name in the user's class space
	 */
	@Retention(RetentionPolicy.RUNTIME)
	public @interface ActualTypeName {
		/**
		 * A fully qualified name that can be given to
		 * {@link ClassLoader#loadClass(String)}.
		 *
		 * @return the fqn
		 */
		String value();
	}

	/**
	 * All proxied interfaces MUST extend this interface.
	 */
	public interface Proxied {

		/**
		 * Will return the underlying instance in user space.
		 */
		Object getActual();

	}

	final ClassLoader classLoader;

	/**
	 * A class space is always on a single class loader. This class loader is
	 * normally distinct from the loader of this class. It allows us to
	 * manipulate objects in that class loader via an interface in our own class
	 * space without ever touching a type in that class loader.
	 *
	 * @param classLoader the class loader
	 */
	public ClassSpace(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	public ClassSpace(Class<?> class1) {
		this(class1.getClassLoader());
	}

	/**
	 * Create a new instance of the proxy type. The actually created instance is
	 * of the type specified in the {@link ActualTypeName} annotation.
	 *
	 * @param <T> the proxy type
	 * @param proxyType the type of the proxy
	 * @return a proxy that can be used to call methods on the actual instance
	 */
	public <T extends Proxied> T newInstance(Class<T> proxyType, Object... args) {
		try {
			assert proxyType.isInterface();
			Class<?> actualType = loadActual(proxyType);
			Constructor<?> bestConstructor = getBestConstructor(actualType, args);
			if (bestConstructor == null)
				throw new IllegalArgumentException(
					"No constructor " + proxyType + " with the given arguments " + Arrays.toString(args));

			Object actual = bestConstructor.newInstance(args);
			return proxy(proxyType, actual);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	/**
	 * Create a proxy on an actual instance. The instance must extend/implement
	 * the proxy type's named type in this class space.
	 *
	 * @param <T> the proxy type
	 * @param proxyType the proxy type
	 * @param actual the actual instance
	 * @return a proxy
	 */
	@SuppressWarnings("unchecked")
	public <T extends Proxied> T proxy(Class<T> proxyType, Object actual) {
		try {

			assert proxyType.isInterface();
			assert loadActual(proxyType).isAssignableFrom(actual.getClass());

			Class<?> actualType = loadActual(proxyType);
			Map<Method, MethodHandle> mapping = getMapping(proxyType, actualType, actual);

			return (T) Proxy.newProxyInstance(ClassSpace.class.getClassLoader(), new Class<?>[] {
				proxyType
			}, (p, m, a) -> {
				MethodHandle mh = mapping.get(m);
				if (mh == null) {
					throw new UnsupportedOperationException("the method " + m + " has no mapping to " + actualType);
				}
				if (a != null) {
					for (int i = 0; i < a.length; i++) {
						if (a[i] instanceof Proxied) {
							a[i] = ((Proxied) a[i]).getActual();
						}
					}
					return mh.invokeWithArguments(a);
				} else
					return mh.invokeWithArguments();
			});
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	/**
	 * Return the actual type name in this class space for the given proxy
	 * class. A Proxied class MUST have the named annotation, this will blow up
	 * if it is called with a Proxied class that has no such annotation.
	 *
	 * @param <T>
	 * @param proxyClass the proxy class
	 * @return the name.
	 */
	public <T extends Proxied> String getActualTypeName(Class<T> proxyClass) {
		ActualTypeName atn = proxyClass.getAnnotation(ActualTypeName.class);
		assert atn != null : "a Proxied class MUST have a ActualTypeName annotation!";
		return atn.value();
	}

	private <T extends Proxied> Map<Method, MethodHandle> getMapping(Class<T> proxyType, Class<?> actualType,
		Object actualInstance)
		throws ClassNotFoundException, IllegalAccessException, NoSuchMethodException, SecurityException {
		assert proxyType.isInterface();
		Map<Method, MethodHandle> mapping = new HashMap<>();
		Method getActual = Proxied.class.getMethod("getActual");
		Method equals = Object.class.getMethod("equals", Object.class);
		Method hashCode = Object.class.getMethod("hashCode");
		mapping.put(equals, lookup.unreflect(equals)
			.bindTo(actualInstance));
		mapping.put(hashCode, lookup.unreflect(hashCode)
			.bindTo(actualInstance));
		mapping.put(getActual, MethodHandles.constant(Object.class, actualInstance));
		for (Method m : proxyType.getMethods()) {
			if (m.getDeclaringClass() == Proxied.class)
				continue;

			Class<?>[] parameterTypes = m.getParameterTypes();
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterTypes[i] = replace(parameterTypes[i]);
			}

			Method other = actualType.getMethod(m.getName(), parameterTypes);
			MethodHandle mh = lookup.unreflect(other)
				.bindTo(actualInstance);

			if (Proxied.class.isAssignableFrom(m.getReturnType())) {
				@SuppressWarnings("unchecked")
				Class<? extends Proxied> returnType = (Class<? extends Proxied>) m.getReturnType();
				Function<Object, Object> f = instance -> {
					return proxy(returnType, instance);
				};
				MethodHandle mhTransform = lookup
					.findVirtual(Function.class, "apply", MethodType.methodType(Object.class, Object.class))
					.bindTo(f);

				mhTransform = mhTransform.asType(MethodType.methodType(m.getReturnType(), other.getReturnType()));
				mh = MethodHandles.filterReturnValue(mh, mhTransform);
			}
			mapping.put(m, mh);
		}
		return mapping;
	}

	private Class<?> replace(Class<?> class1) throws ClassNotFoundException {
		if (Proxied.class.isAssignableFrom(class1)) {
			@SuppressWarnings("unchecked")
			Class<? extends Proxied> t = (Class<? extends Proxied>) class1;
			return loadActual(t);
		} else
			return class1;
	}

	public <T extends Proxied> Class<?> loadActual(Class<T> class1) throws ClassNotFoundException {
		String fqn = getActualTypeName(class1);
		return classLoader.loadClass(fqn);
	}

	@SuppressWarnings("unchecked")
	private <T> Constructor<T> getBestConstructor(Class<T> type, Object... args) {
		nextConstructor: for (Constructor<?> constructor : type.getConstructors()) {
			Class<?>[] parameterTypes = constructor.getParameterTypes();

			if (parameterTypes.length == args.length) {

				for (int i = 0; i < parameterTypes.length; i++) {
					if (args[i] == null)
						continue;
					if (!wrapper(parameterTypes[i]).isInstance(args[i])) {
						continue nextConstructor;
					}
				}
				return (Constructor<T>) constructor;
			}
		}
		return null;
	}

	private static Class<?> wrapper(Class<?> clazz) {
		if (clazz == int.class) {
			return Integer.class;
		} else if (clazz == long.class) {
			return Long.class;
		} else if (clazz == double.class) {
			return Double.class;
		} else if (clazz == float.class) {
			return Float.class;
		} else if (clazz == boolean.class) {
			return Boolean.class;
		} else if (clazz == char.class) {
			return Character.class;
		} else if (clazz == byte.class) {
			return Byte.class;
		} else if (clazz == short.class) {
			return Short.class;
		} else {
			return clazz;
		}
	}
}
