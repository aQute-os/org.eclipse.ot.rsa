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
import java.io.StreamCorruptedException;

import org.freshvanilla.lang.MetaField;

public interface WireFormat {

    public void flush(DataSocket ds, ByteBuf writeBuffer) throws IOException;

    public boolean readBoolean(ByteBuf readBuffer) throws StreamCorruptedException;

    public double readDouble(ByteBuf rb) throws StreamCorruptedException;

    public <Pojo, T> void readField(ByteBuf rb, MetaField<Pojo, T> field, Pojo pojo)
        throws ClassNotFoundException, IOException;

    public long readNum(ByteBuf readBuffer) throws StreamCorruptedException;

    public Object readObject(ByteBuf readBuffer) throws ClassNotFoundException, IOException;

    public String readString(ByteBuf readBuffer) throws ClassNotFoundException, IOException;

    public void writeBoolean(ByteBuf readBuffer, boolean flag);

    public <Pojo, T> void writeField(ByteBuf wb, MetaField<Pojo, T> field, Pojo pojo) throws IOException;

    public void writeNum(ByteBuf writeBuffer, long value);

    public void writeObject(ByteBuf writeBuffer, Object object) throws IOException;

    public void writeTag(ByteBuf writeBuffer, String tag);
    
    public void registerPojo(Object o);

	public int getPojoIndex();

	public void registerPojo(int idx, Object o);

}
