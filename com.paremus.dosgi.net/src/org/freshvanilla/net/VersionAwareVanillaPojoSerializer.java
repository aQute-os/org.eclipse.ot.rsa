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

import java.io.IOException;

import org.freshvanilla.lang.MetaClass;
import org.freshvanilla.lang.MetaClasses;
import org.osgi.framework.Version;

import io.netty.buffer.ByteBuf;

public class VersionAwareVanillaPojoSerializer extends VanillaPojoSerializer {

    public VersionAwareVanillaPojoSerializer(MetaClasses metaclasses) {
        super(metaclasses);
    }

	public <Pojo> Pojo deserialize(ByteBuf rb, WireFormat wf) throws ClassNotFoundException, IOException {
    	String classWithParameters = (String)wf.readObject(rb);
        MetaClass<Pojo> clazz = _metaClasses.acquireMetaClass(classWithParameters);

        Pojo pojo;
        if (clazz == null) {
            throw new ClassNotFoundException(classWithParameters);
        } else if (clazz.getType() == Version.class) {
        	int idx = wf.getPojoIndex();
        	@SuppressWarnings("unchecked")
			Pojo tmp = (Pojo) new Version((int)wf.readNum(rb), (int)wf.readNum(rb), 
        			(int)wf.readNum(rb), (String)wf.readObject(rb));
        	wf.registerPojo(idx, tmp);
        	pojo = tmp;
        } else {
        	pojo = deserialize(rb, wf, clazz);
        }
        return pojo;
    }

}
