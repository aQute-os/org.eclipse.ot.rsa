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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.freshvanilla.lang.MetaClasses;
import org.freshvanilla.utils.Factory;
import org.freshvanilla.utils.VanillaResource;

public class CachedDataSocketFactory extends VanillaResource implements Factory<String, DataSocket> {

    private final ConcurrentMap<String, DataSockets> _dataSocketsMap = new ConcurrentHashMap<String, DataSockets>();
    private final Factory<String, DataSocket> _dataSocketBuilder;
    private int _maximumConnections = 4;

    public CachedDataSocketFactory(String name, String connectionString, MetaClasses metaClasses) {
        this(name, connectionString, Long.MAX_VALUE, metaClasses);
    }

    public CachedDataSocketFactory(String name,
                                   String connectionString,
                                   long timeoutMillis,
                                   MetaClasses metaClasses) {
        this(name, new DataSocketFactory(name, connectionString, timeoutMillis, metaClasses));
    }

    public CachedDataSocketFactory(String name, Factory<String, DataSocket> dataSocketBuilder) {
        super(name);
        _dataSocketBuilder = dataSocketBuilder;
    }

    public int getMaximumConnections() {
        return _maximumConnections;
    }

    public void setMaximumConnections(int maximumConnections) {
        _maximumConnections = maximumConnections;
    }

    public DataSocket acquire(String description) throws InterruptedException {
        checkedClosed();
        DataSockets dataSockets = _dataSocketsMap.get(description);
        if (dataSockets == null) {
            _dataSocketsMap.putIfAbsent(description, new DataSockets(_maximumConnections));
            dataSockets = _dataSocketsMap.get(description);
        }
        DataSocket ds = acquire0(dataSockets, description);
        synchronized (dataSockets.used) {
            dataSockets.used.add(ds);
        }
        return ds;
    }

    private DataSocket acquire0(DataSockets dataSockets, String description) throws InterruptedException {
        // is there one free?
        DataSocket ds = dataSockets.free.poll();
        if (ds != null) {
            return ds;
        }

        // otherwise we might have to make one.
        if (!dataSockets.used.isEmpty()) {
            Thread.yield();
            // see if it was freed.
            ds = dataSockets.free.poll();
            if (ds != null) {
                return ds;
            }
        }

        // should not go over the maximum.
        int count = 1;
        while (dataSockets.used.size() >= _maximumConnections) {
            Thread.sleep(1);
            // see if it was freed.
            ds = dataSockets.free.poll();
            if (ds != null) {
                if (count >= 1) {
                    getLog().debug(getName() + ": got a connection after " + count);
                }
                return ds;
            }
            count++;
        }

        // there is a race condition where this could appear less than the actual number.
        if (dataSockets.free.size() + dataSockets.used.size() >= _maximumConnections) {
            return dataSockets.free.take();
        }

        try {
            return _dataSocketBuilder.acquire(description);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void recycle(DataSocket dataSocket) {
        if (dataSocket == null) {
            return;
        }

        DataSockets dataSockets = _dataSocketsMap.get(dataSocket.getName());
        if (dataSockets == null) {
            getLog().warn(getName() + ": unexpected recycled object " + dataSocket);
            dataSocket.close();
            return;
        }

        synchronized (dataSockets.used) {
            dataSockets.used.remove(dataSocket);
        }

        if (isClosed()) {
            dataSocket.close();
        }
        else if (!dataSocket.isClosed()) {
            try {
                if (dataSockets.free.offer(dataSocket, 2, TimeUnit.MILLISECONDS)) {
                    dataSocket = null;
                }
                else {
                    getLog().debug(getName() + ": closing as over maximum connections " + dataSocket);
                }
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            finally {
                if (dataSocket != null) {
                    dataSocket.close();
                }
            }
        }
    }

    public void close() {
        super.close();

        for (DataSockets dataSockets : _dataSocketsMap.values()) {
            for (DataSocket socket : dataSockets.free) {
                socket.close();
            }

            synchronized (dataSockets.used) {
                for (DataSocket socket : dataSockets.used) {
                    socket.close();
                }
            }
        }

        _dataSocketsMap.clear();
    }

    static class DataSockets {
        final BlockingQueue<DataSocket> free;
        final Set<DataSocket> used;

        DataSockets(int maximumConnections) {
            free = new ArrayBlockingQueue<DataSocket>(maximumConnections + 1);
            used = new HashSet<DataSocket>(maximumConnections);
        }
    }
}
