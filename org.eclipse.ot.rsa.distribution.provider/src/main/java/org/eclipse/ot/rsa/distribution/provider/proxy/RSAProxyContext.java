package org.eclipse.ot.rsa.distribution.provider.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.ot.rsa.distribution.provider.wireformat.MethodIndexes;
import org.osgi.framework.ServiceException;

/**
 * For each bundle that gets this service, we create a proxy. When the service
 * is returned, we close this object
 */
@SuppressWarnings({
	"rawtypes", "unchecked"
})
public class RSAProxyContext implements AutoCloseable {
	final static Map<Method, Action>	defaultMethods	= new HashMap<>();
	final static Method					OBJECT_TOSTRING;

	static {
		try {
			OBJECT_TOSTRING = Object.class.getDeclaredMethod("toString");
			defaultMethods.put(Object.class.getDeclaredMethod("equals", Object.class), (p, args) -> p == args[0]);
			defaultMethods.put(Object.class.getDeclaredMethod("hashCode"), (p, args) -> System.identityHashCode(p));
		} catch (Exception e) {
			throw new Error("Cannot initialize fields from object");
		}
	}

	final RSAImportedContext	imported;
	final Object				proxy;
	final Map<String, Class>	substitutions	= new HashMap<>();
	final Class[]				interfaces;
	final Class					proxyClass;
	final ClassLoader[]			loaders;
	final LoadClass				loader;
	final Map<Method, Action>	actions			= new HashMap<>();

	interface Action {
		Object call(Object proxy, Object[] args);
	}

	class ProxyLoader extends ClassLoader {
		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			Class clazz = substitutions.get(name);
			if (clazz != null)
				return clazz;

			for (ClassLoader l : loaders) {
				try {
					return l.loadClass(name);
				} catch (ClassNotFoundException cnfe) {}
			}

			throw new ClassNotFoundException(name);
		}
	}

	RSAProxyContext(RSAImportedContext imported, LoadClass loader) throws ClassNotFoundException {
		this.imported = imported;
		this.loader = loader;
		this.interfaces = getInterfaces(imported, loader);
		if (interfaces.length == 0)
			throw new ClassNotFoundException("any of: " + imported.interfaces);

		this.loaders = getUniqueLoaders(this.interfaces);
		ProxyLoader proxyLoader = new ProxyLoader();
		this.proxyClass = Proxy.getProxyClass(proxyLoader, this.interfaces);
		InvocationHandler ih = this::invoke;

		actions.putAll(defaultMethods);
		remote(imported, proxyClass, actions, interfaces);
		actions.put(OBJECT_TOSTRING, this::toString);

		this.proxy = Proxy.newProxyInstance(proxyLoader, this.interfaces, this::invoke);

	}

	private String toString(Object proxy, Object[] args) {
		StringBuilder sb = new StringBuilder(80);
		sb.append("ActualTypeName");

		String del = "";
			for (Class<?> iface : interfaces) {
			sb.append(del)
				.append(iface.getName());
			}

		sb.append('@');
		sb.append(Integer.toHexString(System.identityHashCode(proxy)));
		return sb.toString();
	}

	private static void remote(RSAImportedContext imported, Class proxyClass, Map<Method, Action> actions,
		Class[] interfaces) {
		for (Class interf : interfaces) {
			for (Method m : interf.getMethods()) {
				String signature = MethodIndexes.toSignature(m);
				Integer index = imported.signatureToIndex.get(signature);
				if (index == null) {
					// TODO debug log
					continue;
				}
				Action action = imported.getAction(m, index);
				actions.put(m, action);

				try {
					Method direct = proxyClass.getDeclaredMethod(m.getName(), m.getParameterTypes());
					actions.put(direct, action);
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	private Object invoke(Object proxy, Method m, Object[] args) throws Exception {
		try {
		return actions.getOrDefault(m, this::unknown)
			.call(proxy, args);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			if (isChecked(m, e)) {
				throw e;
			}
			throw new ServiceException("unchecked exception from RSA", ServiceException.REMOTE, e);
		}
	}

	private static boolean isChecked(Method m, Exception e) {
		Class type = e.getClass();
		for (Class t : m.getExceptionTypes()) {
			if (t.isAssignableFrom(type))
				return true;
		}
		return false;
	}

	private Object unknown(Object proxy, Object[] args) {
		return null;
	}

	public Object proxy() {
		return proxy;
	}

	@Override
	public void close() {

	}

	private static Class[] getInterfaces(RSAImportedContext imported, LoadClass loader) {
		return imported.interfaces.stream()
			.map(name -> load(loader, name))
			.filter(Objects::nonNull)
			.sorted((a, b) -> a.getName()
				.compareTo(b.getName()))
			.toArray(Class[]::new);
	}

	private static Class<?> load(LoadClass loader, String name) {
		// TODO Auto-generated method stub
		return null;
	}

	private static ClassLoader[] getUniqueLoaders(Class... interfaces) {
		return Stream.of(interfaces)
			.map(Class::getClassLoader)
			.distinct()
			.toArray(ClassLoader[]::new);
	}

}
