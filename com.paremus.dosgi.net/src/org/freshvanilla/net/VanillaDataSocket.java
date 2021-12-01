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
import io.netty.buffer.Unpooled;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.freshvanilla.utils.Callback;
import org.freshvanilla.utils.NamedThreadFactory;
import org.freshvanilla.utils.VanillaResource;
import org.slf4j.Logger;

public class VanillaDataSocket extends VanillaResource implements DataSocket {

    private static final int MIN_PACKET_SIZE = 256;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final long TIMEOUT_MS = 10 * 1000L;
    private static final long WARNING_PERIOD = 3000L - DataSockets.CHECK_PERIOD_MS / 2;

    private final Logger _log;
    private final InetSocketAddress _address;
    private final SocketChannel _channel;
    private final WireFormat _wireFormat;
    private final AtomicLong _microTimestamp = new AtomicLong(System.currentTimeMillis() * 1000L);
    private final Object _executorLock = new Object();
    private final ConcurrentMap<Long, Callback<?>> _callbackMap = new ConcurrentHashMap<Long, Callback<?>>();
    private final ByteBuffer _readBuffer;
    private final ByteBuffer _writeBuffer;
    private final Map<String, Object> _otherHeader;
    private ExecutorService _executor = null;

    // warning metrics
    private boolean _reading = false;
    private long _readTimeMillis = 0;
    private long _nextReadWarningMillis = 0;
    private boolean _writing = false;
    private long _writeTimeMillis = 0;
    private long _nextWriteWarningMillis = 0;

    @SuppressWarnings("unchecked")
    public VanillaDataSocket(String name,
                             InetSocketAddress address,
                             SocketChannel channel,
                             WireFormat wireFormat,
                             Map<String, Object> header,
                             int maximumMessageSize) throws ClassNotFoundException, IOException {
        super(name);
        _log = getLog();
        _address = address;
        _channel = channel;
        channel.configureBlocking(true);
        Socket socket = channel.socket();
        socket.setTcpNoDelay(true);
        socket.setSendBufferSize(BUFFER_SIZE);
        socket.setReceiveBufferSize(BUFFER_SIZE);

        try {
            // 0x10 = IPTOS_LOWDELAY
            socket.setTrafficClass(0x10);
        }
        catch (SocketException sex) {
            // no IP_TOS for you
        }

        _wireFormat = wireFormat;
        _readBuffer = allocateBuffer(maximumMessageSize);
        _writeBuffer = allocateBuffer(maximumMessageSize);
        getLog().debug(name + ": connecting to " + socket);
        DataSockets.registerDataSocket(this);

        wireFormat.writeObject(Unpooled.wrappedBuffer(writeBuffer()), header);
        flush();
        final ByteBuf rb = Unpooled.wrappedBuffer(read());
        _otherHeader = (Map<String, Object>)wireFormat.readObject(rb);
        getLog().debug(name + ": connected to " + socket + ' ' + _otherHeader);
    }

    public InetSocketAddress getAddress() {
        return _address;
    }

    private ByteBuffer allocateBuffer(int maximumMessageSize) {
        // we always use direct buffers.
        return ByteBuffer.allocateDirect(maximumMessageSize);
    }

    public Map<String, Object> getOtherHeader() {
        return _otherHeader;
    }

    public WireFormat wireFormat() {
        return _wireFormat;
    }

    public void addCallback(long sequenceNumber, Callback<?> callback) {
        _callbackMap.put(sequenceNumber, callback);
    }

    public Callback<?> removeCallback(long sequenceNumber) {
        return _callbackMap.remove(sequenceNumber);
    }

    public void setReader(final Callback<DataSocket> reader) {
        synchronized (_executorLock) {
            if (_executor != null) {
                return;
            }
            _executor = Executors.newCachedThreadPool(new NamedThreadFactory(getName() + "-reply-listener",
                Thread.MAX_PRIORITY, true));
            _executor.submit(new ReaderRunnable(reader));
        }
    }

    public ByteBuffer writeBuffer() {
        _writeBuffer.clear();
        // so we can write the length later.
        _writeBuffer.position(4);
        return _writeBuffer;
    }

    public ByteBuffer read() throws IOException {
        final ByteBuffer rb = _readBuffer;
        rb.rewind();
        rb.limit(MIN_PACKET_SIZE);

        readFully(rb);
        _reading = true;

        try {
            int len = rb.getInt(0);
            if (len > MIN_PACKET_SIZE) {
                rb.limit(len);
                readFully(rb);
            }
        }
        finally {
            _reading = false;
            _readTimeMillis = 0;
        }

        rb.rewind();
        // after the length.
        rb.position(4);
        return rb;
    }

    private void readFully(ByteBuffer rb) throws IOException {
        channelRead(rb);

        if (rb.remaining() <= 0) {
            return;
        }

        do {
            channelRead(rb);

            if (rb.remaining() <= 0) {
                return;
            }

            Thread.yield();
        }
        while (true);
    }

    private void channelRead(ByteBuffer rb) throws IOException {
        int len = -1;

        try {
            len = _channel.read(rb);
        }
        catch (IOException e) {
            final String eStr = e.toString();
            if (!eStr.equals("java.io.IOException: An established connection was aborted by the software in your host machine")
                && !eStr.equals("java.nio.channels.AsynchronousCloseException")) {
                throw e;
            }
        }

        if (len < 0) {
            throw new EOFException(
                "An established connection was aborted by the software in your host machine");
        }
    }

    protected void writeFully(ByteBuffer wb) throws IOException {
        writeChannel(wb);

        if (wb.remaining() <= 0) {
            return;
        }

        long start = System.currentTimeMillis();
        long next = start + 50;
        int retries = 0;
        while (wb.remaining() > 0) {
            long time = System.currentTimeMillis() - start;
            if (time > next) {
                Thread.yield();
                retries++;
                next += 50 * retries;
            }
            writeChannel(wb);
        }
    }

    private void writeChannel(ByteBuffer wb) throws IOException {
        int len = -1;

        try {
            len = _channel.write(wb);

        }
        catch (IOException e) {
            Class<? extends IOException> eClass = e.getClass();
            if (eClass != IOException.class) {
                throw e;
            }
        }

        if (len < 0) {
            throw new EOFException();
        }
    }

    public void flush() throws IOException {
        final ByteBuffer wb = _writeBuffer;
        int len = wb.position();
        wb.flip();
        wb.putInt(0, len);

        if (len < MIN_PACKET_SIZE) {
            wb.limit(len = MIN_PACKET_SIZE);
        }

        _writing = true;

        try {
            writeFully(wb);
        }
        finally {
            _writing = false;
            _writeTimeMillis = 0;
        }
    }

    public long microTimestamp() {
        return _microTimestamp.getAndIncrement();
    }

    public void close() {
        super.close();
        DataSockets.unregisterDataSocket(this);

        try {
            _channel.close();
        }
        catch (IOException ignored) {
            // ignored.
        }

        if (_executor != null) {
            _executor.shutdownNow();
        }

        for (Callback<?> callback : _callbackMap.values()) {
            callback.onException(new IllegalStateException(getName() + " is closed!"));
        }

        _executor = null;
        _callbackMap.clear();
    }

    class ReaderRunnable implements Runnable {
        private final Callback<DataSocket> _reader;

        ReaderRunnable(Callback<DataSocket> reader) {
            _reader = reader;
        }

        public void run() {
            while (!isClosed()) {
                try {
                    _reader.onCallback(VanillaDataSocket.this);
                }
                catch (Exception e) {
                    if (isClosed()) {
                        return;
                    }

                    _reader.onException(e);
                    if (e instanceof IOException) {
                        close();
                    }
                }
            }
        }
    }

    public void timedCheck(long timeMillis) {
        if (_reading) {
            if (_readTimeMillis == 0) {
                _readTimeMillis = timeMillis;
                _nextReadWarningMillis = timeMillis + WARNING_PERIOD;
            }
            else if (timeMillis >= _nextReadWarningMillis) {
                final long totalWriteTimeMillis = timeMillis - _readTimeMillis;
                if (totalWriteTimeMillis > TIMEOUT_MS) {
                    if (_log.isDebugEnabled()) {
                        _log.debug(getName() + ": closing reading connection after " + totalWriteTimeMillis
                                   + " ms");
                    }
                    close();
                }
                else {
                    if (_log.isDebugEnabled()) {
                        _log.debug(getName() + ": waiting for long running read " + totalWriteTimeMillis
                                   + " ms");
                    }
                    _nextReadWarningMillis = timeMillis + WARNING_PERIOD;
                }
            }
        }

        if (_writing) {
            if (_writeTimeMillis == 0) {
                _writeTimeMillis = timeMillis;
                _nextWriteWarningMillis = timeMillis + WARNING_PERIOD;
            }
            else if (timeMillis >= _nextWriteWarningMillis) {
                final long totalWriteTimeMillis = timeMillis - _writeTimeMillis;
                if (totalWriteTimeMillis > TIMEOUT_MS) {
                    if (_log.isDebugEnabled()) {
                        _log.debug(getName() + ": closing writing connection after " + totalWriteTimeMillis
                                   + " ms");
                    }
                    close();
                }
                else {
                    if (_log.isDebugEnabled()) {
                        _log.debug(getName() + ": waiting for long running write " + totalWriteTimeMillis
                                   + " ms");
                    }
                    _nextWriteWarningMillis = timeMillis + WARNING_PERIOD;
                }
            }
        }
    }

}
