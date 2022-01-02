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

package org.freshvanilla.net;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.NotSerializableException;

import org.freshvanilla.lang.MetaClass;
import org.freshvanilla.lang.MetaClasses;
import org.freshvanilla.lang.MetaField;

public class VanillaPojoSerializer implements PojoSerializer {

    protected final MetaClasses _metaClasses;

    public VanillaPojoSerializer(MetaClasses metaclasses) {
        super();
        _metaClasses = metaclasses;
    }

    public <Pojo> boolean canSerialize(Pojo pojo) {
        Class<?> clazz = pojo.getClass();
        if(clazz.isArray() || clazz.isEnum() || clazz.isPrimitive()) {
        	return false;
        }
		final String className = clazz.getName();
        return !className.startsWith("java") && !className.startsWith("com.sun.");
    }

    @SuppressWarnings("unchecked")
    public <Pojo> void serialize(ByteBuf wb, WireFormat wf, Pojo pojo) throws IOException {
        MetaClass<Pojo> clazz = _metaClasses.acquireMetaClass((Class<Pojo>)pojo.getClass());
        wf.writeTag(wb, clazz.nameWithParameters());

        for (MetaField<Pojo, ?> field : clazz.fields()) {
            wf.writeField(wb, field, pojo);
        }
    }

    public <Pojo> Pojo deserialize(ByteBuf rb, WireFormat wf) throws ClassNotFoundException, IOException {
        String classWithParameters = (String)wf.readObject(rb);
        MetaClass<Pojo> clazz = _metaClasses.acquireMetaClass(classWithParameters);

        if (clazz == null) {
            throw new ClassNotFoundException(classWithParameters);
        }

        return deserialize(rb, wf, clazz);
    }

	protected <Pojo> Pojo deserialize(ByteBuf rb, WireFormat wf, MetaClass<Pojo> clazz)
			throws NotSerializableException, ClassNotFoundException, IOException {
		Pojo pojo;

        try {
            pojo = clazz.newInstance();
            wf.registerPojo(pojo);
        }
        catch (InstantiationException e) {
            throw new NotSerializableException("Exception attempting to create " + clazz + ' ' + e);
        }

        for (MetaField<Pojo, ?> field : clazz.fields()) {
            wf.readField(rb, field, pojo);
        }

        return pojo;
	}

}
