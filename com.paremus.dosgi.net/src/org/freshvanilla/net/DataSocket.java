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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;

import org.freshvanilla.utils.Callback;
import org.freshvanilla.utils.SimpleResource;

public interface DataSocket extends SimpleResource {
    public InetSocketAddress getAddress();

    public WireFormat wireFormat();

    public ByteBuffer writeBuffer();

    public long microTimestamp();

    public ByteBuffer read() throws IOException;

    public void flush() throws IOException;

    public void addCallback(long sequenceNumber, Callback<?> callback);

    public Callback<?> removeCallback(long sequenceNumber);

    public void setReader(Callback<DataSocket> reader);

    public Map<String, Object> getOtherHeader();

    public void timedCheck(long timeMS);
}
