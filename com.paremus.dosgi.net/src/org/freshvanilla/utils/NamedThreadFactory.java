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

package org.freshvanilla.utils;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class NamedThreadFactory implements ThreadFactory {
    private final String _name;
    private final AtomicInteger _counter = new AtomicInteger();
    private final int _priority;
    private final boolean _daemon;

    public NamedThreadFactory(String name, int priority, boolean daemon) {
        _name = name;
        _priority = priority;
        _daemon = daemon;
    }

    public Thread newThread(Runnable r) {
        String name = _name;
        int id = _counter.incrementAndGet();
        if (id > 1) name += ':' + id;
        Thread t = new Thread(r, name);
        t.setPriority(_priority);
        t.setDaemon(_daemon);
        return t;
    }
}
