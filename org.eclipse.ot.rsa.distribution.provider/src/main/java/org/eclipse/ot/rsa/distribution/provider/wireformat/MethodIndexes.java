package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("rawtypes")
public class MethodIndexes {
	private static final Method[]	EMPTY	= new Method[0];
	final Map<String, Method>		index;
	final Method[]					mappings;

	public MethodIndexes(Class... types) {
		this.index = Stream.of(types)
			.map(Class::getMethods)
			.flatMap(Arrays::stream)
			.collect(Collectors.toMap(m -> toSignature(m.getName()), Function.identity(), (a, b) -> a, TreeMap::new));

		this.mappings = this.index.values()
			.toArray(EMPTY);
	}

	public MethodIndexes(Class<?> primary, Class<?>... aux) {
		this.index = Stream.concat(Stream.of(primary), Stream.of(aux))
			.map(Class::getMethods)
			.flatMap(Arrays::stream)
			.collect(Collectors.toMap(m -> toSignature(m.getName(), m.getParameterTypes()), Function.identity(),
				(a, b) -> a, TreeMap::new));

		this.mappings = this.index.values()
			.toArray(EMPTY);
	}

	public int getIndex(String name, Class<?>... parameters) {
		String key = toSignature(name, parameters);
		Method m = index.get(key);
		if (m != null)
			for (int i = 0; i < mappings.length; i++) {
				if (m == mappings[i])
					return i;
			}
		throw new IllegalArgumentException("No method " + key);
	}

	public Method[] getMappings() {
		return mappings;
	}

	/**
	 * Converts a java.lang.reflect.Method into a canonicalised String
	 *
	 * @param name the name of the method
	 * @param parameters the parameter types
	 * @return a signature string
	 */
	public static String toSignature(String name, Class... parameters) {
		StringBuilder sb = new StringBuilder(name);
		sb.append('[');
		String del = "";
		for (Class<?> clazz : parameters) {
			sb.append(del)
				.append(clazz.getName());
			del = ",";
		}
		return sb.append(']')
			.toString();
	}

	/**
	 * Converts a java.lang.reflect.Method into a canonicalised String
	 *
	 * @param m the method
	 * @return A string identifier for the method
	 */
	public static String toSignature(Method m) {
		return toSignature(m.getName(), m.getParameterTypes());
	}
}
