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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.freshvanilla.lang.ObjectBuilder;
import org.freshvanilla.utils.Factory;
import org.freshvanilla.utils.NamedThreadFactory;
import org.freshvanilla.utils.VanillaResource;

public class VanillaDataServerSocket extends VanillaResource implements Runnable {

    private final ServerSocketChannel _channel;
    private final Factory<DataSocket, DataSocketHandler> _factory;
    private final Map<String, Object> _header;
    private final ObjectBuilder<WireFormat> _wireFormatBuilder;
    private final int _maximumMessageSize;
    private final ExecutorService _executor;
    private final int port;

    public VanillaDataServerSocket(String name,
                                   Factory<DataSocket, DataSocketHandler> factory,
                                   Map<String, Object> header,
                                   int port,
                                   ObjectBuilder<WireFormat> wireFormatBuilder,
                                   int maximumMessageSize) throws IOException {
        super(name);
        _factory = factory;
        _header = header;
        _wireFormatBuilder = wireFormatBuilder;
        _maximumMessageSize = maximumMessageSize;

        _channel = ServerSocketChannel.open();
        _channel.configureBlocking(true);
        port = bindToPort(port);
        this.port = port;
        _executor = Executors.newCachedThreadPool(new NamedThreadFactory(name + "-server",
            Thread.MAX_PRIORITY, true));
        _executor.submit(this);
    }

    private int bindToPort(int port) throws IOException {
        boolean findAPort = port <= 0;

        while (true) {
            if (findAPort) {
                port = (int)(Math.random() * (63 * 1024) + 1024);
            }

            try {
                _channel.socket().bind(new InetSocketAddress(port));
                getLog().debug(getName() + ": Listening on port " + port);
                break;
            }
            catch (SocketException e) {
                if (!findAPort) throw e;
            }

            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        return port;
    }

    public int getPort() {
        return port;
    }

    public void run() {
        try {
            while (!isClosed()) {
                final SocketChannel socketChannel = _channel.accept();
                Runnable runnable = new RmiServerRunnable(socketChannel);
                _executor.submit(runnable);
            }
        }
        catch (IOException e) {
            if (!isClosed()) {
                getLog().error("Unexpected error for running server", e);
                close();
            }
        }
    }

    public void close() {
        super.close();
        try {
            _channel.close();
        }
        catch (IOException ignored) {
            // ignored.
        }
        _executor.shutdown();
    }

    public String getConnectionString() {
        final InetAddress address = _channel.socket().getInetAddress();
        return address.getCanonicalHostName() + ':' + _channel.socket().getLocalPort();
    }

    class RmiServerRunnable implements Runnable {
        private final SocketChannel socketChannel;

        RmiServerRunnable(SocketChannel socketChannel) {
            this.socketChannel = socketChannel;
        }

        public void run() {
            DataSocket ds = null;
            DataSocketHandler socketHandler = null;
            try {
                ds = new VanillaDataSocket(getName(), null, socketChannel, _wireFormatBuilder.create(), _header,
                    _maximumMessageSize);
                socketHandler = _factory.acquire(ds);
                socketHandler.onConnection();
                while (!ds.isClosed()) {
                    socketHandler.onMessage();
                }
                socketHandler.onDisconnection();
            }
            catch (Throwable e) {
                getLog().error("Unexpected error for running server", e);
                try {
                    if (socketHandler != null) {
                        socketHandler.onDisconnection();
                    }
                }
                catch (Exception ignored) {
                    // ignored
                }
                try {
                    if (ds != null) {
                        ds.close();
                    }
                }
                catch (Exception ignored) {
                    // ignored
                }
            }
        }
    }
}
