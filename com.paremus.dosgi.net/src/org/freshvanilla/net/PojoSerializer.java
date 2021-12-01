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

public interface PojoSerializer {

    public <Pojo> boolean canSerialize(Pojo pojo);

    public <Pojo> void serialize(ByteBuf wb, WireFormat wf, Pojo pojo) throws IOException;

    public <Pojo> Pojo deserialize(ByteBuf rb, WireFormat wf) throws ClassNotFoundException, IOException;

}
