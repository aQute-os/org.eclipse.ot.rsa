package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

interface Accessor {

	<T> T newInstance(Class<T> clazz) throws InstantiationException;

	FieldAccessor<?> getFieldAccessor(Field field);

}