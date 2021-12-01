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

import java.util.LinkedHashMap;
import java.util.Map;

public class VirtualClass<T> implements MetaClass<T> {
    private final MetaClass<T> _metaClass;
    private final String _nameWithParameters;
    private final MetaField<T, ?>[] _fields;

    @SuppressWarnings("unchecked")
    public VirtualClass(MetaClass<T> metaClass, String nameWithParameters, String[] nameWithParametersSplit) {
        _metaClass = metaClass;
        _nameWithParameters = nameWithParameters;

        Map<String, MetaField<T, ?>> fieldsByName = new LinkedHashMap<String, MetaField<T, ?>>();
        for (MetaField<T, ?> field : metaClass.fields()) {
            fieldsByName.put(field.getName(), field);
        }

        _fields = new MetaField[nameWithParametersSplit.length - 1];

        for (int i = 0; i < _fields.length; i++) {
            MetaField<T, ?> field = fieldsByName.get(nameWithParametersSplit[i + 1]);
            if (field == null) {
                final String name = nameWithParametersSplit[i + 1];
                field = new VanillaField<T, Void>(name, null, Void.class);
            }
            _fields[i] = field;
        }
    }

    public Class<T> getType() {
        return _metaClass.getType();
    }

    public String nameWithParameters() {
        return _nameWithParameters;
    }

    public MetaField<T, ?>[] fields() {
        return _fields;
    }

    public T newInstance() throws InstantiationException {
        return _metaClass.newInstance();
    }

    public boolean definesEquals() {
        return _metaClass.definesEquals();
    }

    public Class<?> getComponentType() {
        return null;
    }

}
