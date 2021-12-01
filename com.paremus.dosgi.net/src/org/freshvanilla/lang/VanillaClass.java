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

package org.freshvanilla.lang;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.freshvanilla.lang.misc.AccessUtils;

public class VanillaClass<T> implements MetaClass<T> {

    private final Class<T> _clazz;
    private final String _nameWithParameters;
    private final MetaField<T, ?>[] _fields;
    private final boolean _definesEquals;
    private final Class<?> _componentType;

    public VanillaClass(Class<T> clazz) {
        _clazz = clazz;

        _fields = getFieldsForSerialization(clazz);

        StringBuilder sb = new StringBuilder(64);
        sb.append(clazz.getName());
        for (MetaField<T, ?> field : _fields) {
            sb.append(',').append(field.getName());
        }

        _nameWithParameters = sb.toString();
        _definesEquals = clazz.getName().startsWith("java") || clazz.getName().startsWith("com.sun.");
        _componentType = clazz.getComponentType();
    }

    @SuppressWarnings("unchecked")
    private static <T> MetaField<T, ?>[] getFieldsForSerialization(Class<?> clazz) {
        Map<String, MetaField<T, ?>> fieldMap = new LinkedHashMap<String, MetaField<T, ?>>();
        getFieldsForSerialization0(fieldMap, clazz);
        return fieldMap.values().toArray(new MetaField[fieldMap.size()]);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void getFieldsForSerialization0(Map<String, MetaField<T, ?>> fieldMap, Class<?> clazz) {
        if (clazz == null || clazz == Object.class) return;
        getFieldsForSerialization0(fieldMap, clazz.getSuperclass());
        for (Field field : clazz.getDeclaredFields()) {
            if ((field.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) != 0) continue;
            field.setAccessible(true);
            fieldMap.put(field.getName(), new VanillaField(field));
        }
    }

    public Class<T> getType() {
        return _clazz;
    }

    public String nameWithParameters() {
        return _nameWithParameters;
    }

    public MetaField<T, ?>[] fields() {
        return _fields;
    }

    public T newInstance() throws InstantiationException {
        return AccessUtils.newInstance(_clazz);
    }

    public boolean definesEquals() {
        return _definesEquals;
    }

    public Class<?> getComponentType() {
        return _componentType;
    }
}
