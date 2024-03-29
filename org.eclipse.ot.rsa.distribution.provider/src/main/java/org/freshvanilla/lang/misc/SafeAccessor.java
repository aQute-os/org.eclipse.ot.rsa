package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public class SafeAccessor implements Accessor {

	@Override
	public <T> T newInstance(Class<T> clazz) throws InstantiationException {
		try {
			return clazz.getConstructor(new Class[0])
				.newInstance(new Object[0]);
		} catch (InstantiationException | IllegalArgumentException | InvocationTargetException | IllegalAccessException
			| NoSuchMethodException | SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new InstantiationException(e.getMessage());
		}
	}

	@Override
	public FieldAccessor<?> getFieldAccessor(Field field) {
		field.setAccessible(true);

		Class<?> type = field.getType();

		if (type == boolean.class) {
			return new SafeBooleanFieldAccessor(field);
		}

		if (type == byte.class) {
			return new SafeByteFieldAccessor(field);
		}

		if (type == char.class) {
			return new SafeCharFieldAccessor(field);
		}

		if (type == short.class) {
			return new SafeShortFieldAccessor(field);
		}

		if (type == int.class) {
			return new SafeIntFieldAccessor(field);
		}

		if (type == float.class) {
			return new SafeFloatFieldAccessor(field);
		}

		if (type == long.class) {
			return new SafeLongFieldAccessor(field);
		}

		if (type == double.class) {
			return new SafeDoubleFieldAccessor(field);
		}

		return new SafeObjectFieldAccessor(field);
	}

}
