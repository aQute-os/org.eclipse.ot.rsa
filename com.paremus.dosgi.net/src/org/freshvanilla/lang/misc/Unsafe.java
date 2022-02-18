/*
 Copyright 2008-2011 the original author or authors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class Unsafe implements Accessor {
	static final sun.misc.Unsafe unsafe;

    static {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (sun.misc.Unsafe)field.get(null);
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /* (non-Javadoc)
	 * @see org.freshvanilla.lang.misc.Accessor#newInstance(java.lang.Class)
	 */
	@Override
	public <T> T newInstance(Class<T> clazz) throws InstantiationException {
        return (T)unsafe.allocateInstance(clazz);
    }

    /* (non-Javadoc)
	 * @see org.freshvanilla.lang.misc.Accessor#getFieldAccessor(java.lang.reflect.Field)
	 */
    @Override
	public FieldAccessor<?> getFieldAccessor(Field field) {
        Class<?> type = field.getType();
        final long offset = unsafe.objectFieldOffset(field);

        if (type == boolean.class) {
            return new UnsafeBooleanFieldAccessor(offset);
        }

        if (type == byte.class) {
            return new UnsafeByteFieldAccessor(offset);
        }

        if (type == char.class) {
            return new UnsafeCharFieldAccessor(offset);
        }

        if (type == short.class) {
            return new UnsafeShortFieldAccessor(offset);
        }

        if (type == int.class) {
            return new UnsafeIntFieldAccessor(offset);
        }

        if (type == float.class) {
            return new UnsafeFloatFieldAccessor(offset);
        }

        if (type == long.class) {
            return new UnsafeLongFieldAccessor(offset);
        }

        if (type == double.class) {
            return new UnsafeDoubleFieldAccessor(offset);
        }

        return new UnsafeObjectFieldAccessor(offset);
    }
}
