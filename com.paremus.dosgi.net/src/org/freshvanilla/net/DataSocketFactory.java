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
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.freshvanilla.lang.MetaClasses;
import org.freshvanilla.lang.ObjectBuilder;
import org.freshvanilla.lang.misc.AccessUtils;
import org.freshvanilla.utils.Factory;
import org.freshvanilla.utils.VanillaResource;

public class DataSocketFactory extends VanillaResource implements Factory<String, DataSocket> {

    public static final int DEFAULT_MAXIMUM_MESSAGE_SIZE = 1024 * 1024;

    private final InetSocketAddress[] _addresses;
    private final ObjectBuilder<WireFormat> _wireFormatBuilder;
    private final Map<String, Object> _header = new LinkedHashMap<String, Object>();
    private final long _timeoutMillis;

    private int _lastAddress = 0;
    private int _maximumMessageSize = DEFAULT_MAXIMUM_MESSAGE_SIZE;

    public DataSocketFactory(String name, String connectionString, long timeoutMS, MetaClasses metaClasses) {
        super(name);
        _addresses = parseConnectionString(connectionString);
        _timeoutMillis = timeoutMS;
        _wireFormatBuilder = new BinaryWireFormat.Builder(name, metaClasses, AccessUtils.isSafe() ?
        		new VersionAwareVanillaPojoSerializer(metaClasses) : 
        		new VanillaPojoSerializer(metaClasses));
    }

    public Map<String, Object> getHeader() {
        return _header;
    }

    public int getMaximumMessageSize() {
        return _maximumMessageSize;
    }

    public void setMaximumMessageSize(int maximumMessageSize) {
        _maximumMessageSize = maximumMessageSize;
    }

    private static InetSocketAddress[] parseConnectionString(String connectionString) {
        String[] parts = connectionString.split(",");
        InetSocketAddress[] addresses = new InetSocketAddress[parts.length];

        for (int i = 0; i < parts.length; i++) {
        	int idx = parts[i].lastIndexOf(':');
        	
            if (idx == -1) {
                int port = Integer.parseInt(parts[i]);
                addresses[i] = new InetSocketAddress(port);
            }
            else {
                String hostname = parts[i].substring(0, idx);
                int port = Integer.parseInt(parts[i].substring(idx + 1));
                if (hostname.length() == 0 || "localhost".equals(hostname)) {
                    addresses[i] = new InetSocketAddress(port);
                }
                else {
                    addresses[i] = new InetSocketAddress(hostname, port);
                }
            }
        }

        return addresses;
    }

    public DataSocket acquire(String name) throws Exception {
        WireFormat wireFormat = _wireFormatBuilder.create();
        Map<String, Object> header = new LinkedHashMap<String, Object>(_header);
        long timeoutMillis = _timeoutMillis < Long.MAX_VALUE
                        ? System.currentTimeMillis() + _timeoutMillis
                        : Long.MAX_VALUE;
        int count = 1;

        IOException lastException;

        do {
            try {
                final InetSocketAddress remote = _addresses[_lastAddress];
                SocketChannel channel = SocketChannel.open(remote);
                return new VanillaDataSocket(name, remote, channel, wireFormat, header, _maximumMessageSize);
            }
            catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }

                if (_lastAddress + 1 >= _addresses.length) {
                    _lastAddress = 0;
                }
                else {
                    _lastAddress++;
                }

                lastException = e;
            }

            if (count == _addresses.length) {
                getLog().debug(name + ": unable to connect to any of " + Arrays.asList(_addresses));
                Thread.sleep(2500);
                count = 0;
            }
            else {
                count++;
            }
        }
        while (System.currentTimeMillis() < timeoutMillis);

        throw lastException;
    }

    public void recycle(DataSocket dataSocket) {
        dataSocket.close();
    }

    protected void finalize() throws Throwable {
        try {
            close();
        }
        finally {
            super.finalize();
        }
    }

}
