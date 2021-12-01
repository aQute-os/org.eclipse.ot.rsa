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

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.freshvanilla.utils.NamedThreadFactory;

public class DataSockets {

    private static final AtomicReference<ScheduledExecutorService> MANAGER = new AtomicReference<ScheduledExecutorService>();
    private static final Map<DataSocket, String> DATA_SOCKETS = new ConcurrentHashMap<DataSocket, String>();

    static final long CHECK_PERIOD_MS = 1000;

    private DataSockets() {
        // forbidden
    }

    public static void registerDataSocket(DataSocket ds) {
        synchronized (MANAGER) {
            ScheduledExecutorService service = MANAGER.get();
            if (service == null || service.isShutdown()) {
                ThreadFactory tf = new NamedThreadFactory("data-socket-manager", Thread.NORM_PRIORITY, true);
                service = Executors.newSingleThreadScheduledExecutor(tf);
                MANAGER.set(service);

                Runnable checker = new Runnable() {
                    public void run() {
                        long now = System.currentTimeMillis();
                        for (Entry<DataSocket, String> e : DATA_SOCKETS.entrySet()) {
                            DataSocket ds = e.getKey();
                            if (ds != null) {
                                ds.timedCheck(now);
                                if (ds.isClosed()) {
                                    DATA_SOCKETS.remove(ds);
                                }
                            }
                        }
                    }
                };

                service.scheduleAtFixedRate(checker, CHECK_PERIOD_MS, CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
            }
        }

        DATA_SOCKETS.put(ds, "");
    }

    public static void unregisterDataSocket(DataSocket ds) {
        DATA_SOCKETS.remove(ds);
        synchronized (MANAGER) {
            if (DATA_SOCKETS.isEmpty()) {
                reset();
            }
        }
    }

    public static void reset() {
        ScheduledExecutorService service = MANAGER.getAndSet(null);
        if (service != null) {
            service.shutdownNow();
        }

        for (DataSocket dataSocket : DATA_SOCKETS.keySet()) {
            dataSocket.close();
        }

        DATA_SOCKETS.clear();
    }

    public static void appendStackTrace(DataSocket ds, Throwable t) {
        try {
            Field stackTraceField = Throwable.class.getDeclaredField("stackTrace");
            stackTraceField.setAccessible(true);

            List<StackTraceElement> original = Arrays.asList(t.getStackTrace());
            int pos;

            for (pos = original.size() - 1; pos > 0; pos--) {
                if ("invoke0".equals(original.get(pos).getMethodName())) {
                    break;
                }
            }

            if (pos <= 0) {
                pos = original.size() - 6;
            }

            // protect against missing/mangled remote stack traces
            if (pos < 0) {
                pos = 0;
            }

            List<StackTraceElement> extended = new ArrayList<StackTraceElement>(original.subList(0, pos));

            InetSocketAddress address = ds.getAddress();

            if (address != null) {
                String hostName = address.getHostName();
                if ("0.0.0.0".equals(hostName)) {
                    hostName = "localhost";
                }

                extended.add(new StackTraceElement("~ call to server ~", "call", hostName, address.getPort()));
            }

            List<StackTraceElement> here = Arrays.asList(new Throwable().getStackTrace());
            extended.addAll(here.subList(3, here.size()));
            stackTraceField.set(t, extended.toArray(new StackTraceElement[extended.size()]));
        }
        catch (NoSuchFieldException nsf) {
            throw new AssertionError(nsf);
        }
        catch (IllegalAccessException iae) {
            throw new AssertionError(iae);
        }
    }

}
