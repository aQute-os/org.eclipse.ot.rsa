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

package org.freshvanilla.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Classes {

    private static final Map<Class<?>, Class<?>> WRAPPER_TYPES = new HashMap<Class<?>, Class<?>>(31);

    static {
        WRAPPER_TYPES.put(boolean.class, Boolean.class);
        WRAPPER_TYPES.put(byte.class, Byte.class);
        WRAPPER_TYPES.put(char.class, Character.class);
        WRAPPER_TYPES.put(short.class, Short.class);
        WRAPPER_TYPES.put(int.class, Integer.class);
        WRAPPER_TYPES.put(long.class, Long.class);
        WRAPPER_TYPES.put(float.class, Float.class);
        WRAPPER_TYPES.put(double.class, Double.class);
        WRAPPER_TYPES.put(void.class, Void.class);
    }

    private Classes() {
        // not used
    }

    public static ClassLoader getClassLoader(Class<?> cls) {
        ClassLoader cl = cls.getClassLoader();
        return (cl != null ? cl : ClassLoader.getSystemClassLoader());
    }

    @SuppressWarnings("unchecked")
    public static <T> T parseAs(Object o, Class<T> clazz) {
        if (o == null) {
            return null;
        }

        Class<?> clazz2 = asWrapper(clazz);
        final Class<?> oClass = o.getClass();
        if (clazz2 == oClass || clazz2.isAssignableFrom(oClass)) {
            return (T)o;
        }

        // convert number
        if (Number.class.isAssignableFrom(clazz2)) {
            if (o instanceof Number) {
                Number n = (Number)o;
                if (clazz2 == Byte.class) return (T)(Byte)n.byteValue();
                if (clazz2 == Short.class) return (T)(Short)n.shortValue();
                if (clazz2 == Integer.class) return (T)(Integer)n.intValue();
                if (clazz2 == Long.class) return (T)(Long)n.longValue();
                if (clazz2 == Float.class) return (T)(Float)n.floatValue();
                if (clazz2 == Double.class) return (T)(Double)n.doubleValue();
            }

            String asString = o.toString();
            if (clazz2 == Byte.class) return (T)(Byte)Byte.parseByte(asString);
            if (clazz2 == Short.class) return (T)(Short)Short.parseShort(asString);
            if (clazz2 == Integer.class) return (T)(Integer)Integer.parseInt(asString);
            if (clazz2 == Long.class) return (T)(Long)Long.parseLong(asString);
            if (clazz2 == Float.class) return (T)(Float)Float.parseFloat(asString);
            if (clazz2 == Double.class) return (T)(Double)Double.parseDouble(asString);
            if (clazz2 == BigInteger.class) return (T)new BigInteger(asString);
            if (clazz2 == BigDecimal.class) return (T)new BigDecimal(asString);
        }

        if (clazz == String.class) {
            return (T)o.toString();
        }

        throw new ClassCastException("Unable to convert types from " + oClass + " to " + clazz2);
    }

    private static Class<?> asWrapper(Class<?> clazz) {
        Class<?> ret = WRAPPER_TYPES.get(clazz);
        return ret == null ? clazz : ret;
    }

    public static List<MetaMethod<?>> getMemberMethods(Class<?> clazz) {
        List<MetaMethod<?>> methodList = new ArrayList<MetaMethod<?>>();
        for (Method method : clazz.getMethods()) {
            if (isPublicNonStatic(method)) {
                method.setAccessible(true);
                methodList.add(new MetaMethod<Object>(method.getName(), method.getParameterTypes(), method));
            }
        }
        return methodList;
    }

    private static boolean isPublicNonStatic(Member method) {
        return (method.getModifiers() & (Modifier.STATIC | Modifier.PUBLIC)) == Modifier.PUBLIC;
    }

    public static class MetaMethod<T> {
        public final String methodName;
        public final Class<?>[] parameterTypes;
        public final Method method;

        public MetaMethod(MetaMethod<T> method) {
            this(method.methodName, method.parameterTypes, method.method);
        }

        public MetaMethod(String methodName, Class<?>[] parameterTypes, Method method) {
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.method = method;
        }

        public Object invoke(T object, Object... args) throws InvocationTargetException {
            try {
                return method.invoke(object, args);
            }
            catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }

        public <A extends Annotation> A getAnnotation(Class<A> aClass) {
            return method.getAnnotation(aClass);
        }
    }

}
