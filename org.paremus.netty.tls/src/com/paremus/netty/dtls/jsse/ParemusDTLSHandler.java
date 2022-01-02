/**
 * Copyright (c) 2012 - 2021 Paremus Ltd., Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors:
 * 		Paremus Ltd. - initial API and implementation
 *      Data In Motion
 */
package com.paremus.netty.dtls.jsse;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.AlertLevel;
import org.bouncycastle.tls.ContentType;
import org.bouncycastle.tls.HandshakeType;
import org.bouncycastle.tls.RecordFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.netty.dtls.adapter.DtlsEngine;
import com.paremus.netty.tls.MultiplexingDTLSHandler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.concurrent.PromiseNotifier;
import io.netty.util.concurrent.ScheduledFuture;

public class ParemusDTLSHandler extends ChannelDuplexHandler implements MultiplexingDTLSHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ParemusDTLSHandler.class);

    private static class ConnectionDetails {
        final boolean isClient;
        final Map<DatagramPacket, ChannelPromise> pendingWrites = new LinkedHashMap<>();
        final InternalDTLSHandler handler;

        public ConnectionDetails(boolean isClient, InternalDTLSHandler handler) {
            this.isClient = isClient;
            this.handler = handler;
        }
    }

    private final ConcurrentMap<SocketAddress, ConnectionDetails> connections = new ConcurrentHashMap<>();

    private final ConcurrentMap<SocketAddress, Instant> lastApplicationData = new ConcurrentHashMap<>();

    private final Supplier<DtlsEngine> engineSupplier;

    private ScheduledFuture<?> timeout;

    private boolean closing;

    private ChannelHandlerContext ctx;

    public ParemusDTLSHandler(Supplier<DtlsEngine> engineSupplier) {
        this.engineSupplier = engineSupplier;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        timeout = ctx.executor().scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.HOURS);
    }

    private void cleanup() {
        Instant now = Instant.now();
        List<SocketAddress> toClose = lastApplicationData.entrySet().stream()
                .filter(e -> e.getValue().plus(1, ChronoUnit.MINUTES).isBefore(now)).map(Entry::getKey)
                .collect(Collectors.toList());

        if (!toClose.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Disconnecting idle DTLS sessions for remote endpoints {}", toClose);
            }

            lastApplicationData.keySet().removeAll(toClose);

            toClose.stream().forEach(this::disconnect);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        ctx.close();
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {

        closing = true;

        if (LOG.isDebugEnabled()) {
            LOG.debug("Closing DTLS sessions for all remote endpoints.");
        }

        ChannelPromise pendingCloses = ctx.newPromise();

        @SuppressWarnings("deprecation")
        PromiseCombiner pc = new PromiseCombiner();

        try {
            connections.keySet().stream().map(this::disconnect).forEach(pc::add);
        } finally {
            connections.clear();
            lastApplicationData.clear();
            pc.finish(pendingCloses);
        }

        pendingCloses.addListener(f -> super.close(ctx, promise));

        timeout.cancel(false);
        timeout = null;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof DatagramPacket) {
            // this is the unencryped data written by the app
            DatagramPacket dp = (DatagramPacket) msg;

            InetSocketAddress recipient = dp.recipient();
            lastApplicationData.put(recipient, Instant.now());

            ConnectionDetails connection = connections.get(recipient);
            if (connection == null) {

                DtlsEngine tlsEngine = engineSupplier.get();
                tlsEngine.setClient(true);

                connection = new ConnectionDetails(true,
                        new ParemusClientDTLSHandler(tlsEngine, ctx, dp.recipient()));
                connections.put(recipient, connection);

                final ConnectionDetails pending = connection;

                connection.handler.handshakeFuture().addListener(f -> onConnect(ctx, pending, f));
                connection.handler.closeFuture().addListener(f -> removeOnClose(recipient, pending));
                
                connection.handler.channelActive(ctx);
            }

            Future<?> handshake = connection.handler.handshakeFuture();

            if (handshake.isSuccess()) {
                connection.handler.write(ctx, dp, promise);
            } else if (handshake.isDone()) {
                promise.tryFailure(handshake.cause());
            } else {
                connection.pendingWrites.put(dp, promise);
            }
        } else {
            promise.tryFailure(new IllegalArgumentException("The sent message was not a DatagramPacket " + msg));
        }
    }

    private void onConnect(ChannelHandlerContext ctx, final ConnectionDetails pending, Future<?> f) {
        if (f.isSuccess()) {
            sendPending(ctx, pending);
        } else {
            pending.pendingWrites.values().stream().filter(p -> !p.isVoid()).forEach(p -> p.tryFailure(f.cause()));
        }
    }

    private void sendPending(ChannelHandlerContext ctx, ConnectionDetails connection) {
        if (!connection.pendingWrites.isEmpty()) {
            for (Entry<DatagramPacket, ChannelPromise> e : connection.pendingWrites.entrySet()) {
                try {
                    connection.handler.write(ctx, e.getKey(), e.getValue());
                } catch (Exception e1) {
                    e.getValue().tryFailure(e1);
                }
            }
            connection.pendingWrites.clear();
            try {
                connection.handler.flush(ctx);
            } catch (Exception e1) {
                LOG.warn("An error occurred when flushing the pending write records for connection {} to {}", ctx.channel(), connection.handler.getRemotePeerAddress(), e1);
            }
        }
    }

    /** This is not a public constant for some reason! */
    private static final int DTLS_RECORD_HEADER_LENGTH = 13;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            DatagramPacket dp = (DatagramPacket) msg;

            InetSocketAddress sender = dp.sender();
            ByteBuf buf = dp.content();

            ConnectionDetails connection = connections.get(sender);
            if (connection == null) {

                if (closing) {
                    dp.release();
                    return;
                }
                
                if(shouldDiscard(ctx, sender, buf)) {
                    ReferenceCountUtil.safeRelease(dp);
                    return;
                }

                DtlsEngine engine = engineSupplier.get();
                engine.setClient(false);
                connection = new ConnectionDetails(false,
                        new ParemusServerDTLSHandler(engine, ctx, sender));
                connections.put(sender, connection);

                final ConnectionDetails pending = connection;
                connection.handler.handshakeFuture().addListener(f -> onConnect(ctx, pending, f));
                connection.handler.closeFuture().addListener(f -> removeOnClose(sender, pending));

            } else if (connection.isClient) {
                // We need to resolve what happens if two clients say hello at the same time

                short messageType = buf.getUnsignedByte(buf.readerIndex() + RecordFormat.TYPE_OFFSET);

                if (messageType == ContentType.handshake) {
                    // This is an incoming handshake message - we need to look deeper
                    short handshakeType = buf.getUnsignedByte(buf.readerIndex() + DTLS_RECORD_HEADER_LENGTH);
                    if (handshakeType == HandshakeType.client_hello) {
                        LOG.debug(
                                "A race between connections with endpoint {} has been detected. Attempting to resolve it",
                                sender);
                        connection = cleanUpClientRace(ctx, dp, sender, connection);
                    }
                }
            }

            if (connection != null) {
                connection.handler.channelRead(ctx, dp);
            } else {
                ReferenceCountUtil.safeRelease(msg);
            }
        } else {
            LOG.warn("The Multiplexing DTLS handler can only process DatagramPacket messages, not {}", 
                    msg == null ? null : msg.getClass());
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    private boolean removeOnClose(SocketAddress socketAddress, final ConnectionDetails pending) {
        return connections.remove(socketAddress, pending);
    }

    private boolean shouldDiscard(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
        int index = buf.readerIndex();
        short messageType = buf.getUnsignedByte(index);
        switch(messageType) {
            case ContentType.alert:
                // Just log and consume the alert
                int length = buf.getUnsignedShort(index + DTLS_RECORD_HEADER_LENGTH - 2);
                if(length >= 2) {
                    short level;
                    short desc;
                    if(length == 2) {
                        level = buf.getUnsignedByte(index + DTLS_RECORD_HEADER_LENGTH);
                        desc =  buf.getUnsignedByte(index + DTLS_RECORD_HEADER_LENGTH + 1);
                        LOG.debug("Received a {} level alert with message {} from {} when there was no DTLS connection", 
                                AlertLevel.getText(level), AlertDescription.getText(desc), sender);
                    } else {
                        LOG.debug("Received an encrypted alert message from {} but there was no DTLS connection. Treating it as an internal warning alert",
                                sender);
                        level = AlertLevel.warning;
                        desc = AlertDescription.internal_error;
                    }
                    
                    
                    // If it's a warning that's not a close notify then we send a close notify to
                    // make them go away
                    if(level == AlertLevel.warning && desc != AlertDescription.close_notify) {
                        ByteBuf response = Unpooled.buffer(15);
                        response.writeByte(ContentType.alert)
                            // DTLS version
                            .writeShort(buf.getUnsignedShort(index + 1))
                            // Increase epoch by 1
                            .writeShort(buf.getUnsignedShort(index + 3) + 1)
                            .writeMedium(0)
                            .writeMedium(1)
                            .writeShort(2)
                            .writeByte(AlertLevel.warning)
                            .writeByte(AlertDescription.close_notify);
                        
                        ctx.writeAndFlush(new DatagramPacket(response, sender), ctx.voidPromise());
                    }
                } else {
                    LOG.warn("Received an unknown alert record from {} with length {}. It will be ignored", sender, length);
                }
                return true;
            case ContentType.handshake:
                // If it's not a client-side hello message then send a cancelled message

                if(HandshakeType.client_hello == buf.getUnsignedByte(index + DTLS_RECORD_HEADER_LENGTH)) {
                    return false;
                }
                // Fall through
            default: 
                ByteBuf response = Unpooled.buffer(30);
                response.writeByte(ContentType.alert)
                    // DTLS version
                    .writeShort(buf.getUnsignedShort(index + 1))
                    // Increase epoch by 1
                    .writeShort(buf.getUnsignedShort(index + 3) + 1)
                    .writeMedium(0)
                    .writeMedium(1)
                    .writeShort(2)
                    .writeByte(AlertLevel.warning)
                    .writeByte(AlertDescription.user_canceled)
                    .writeByte(ContentType.alert)
                    // DTLS version
                    .writeShort(buf.getUnsignedShort(index + 1))
                    // Increase epoch by 1
                    .writeShort(buf.getUnsignedShort(index + 3) + 1)
                    .writeMedium(0)
                    .writeMedium(1)
                    .writeShort(2)
                    .writeByte(AlertLevel.warning)
                    .writeByte(AlertDescription.close_notify);
                
                ctx.writeAndFlush(new DatagramPacket(response, sender), ctx.voidPromise());
                return true;
        }
    }

    private ConnectionDetails cleanUpClientRace(ChannelHandlerContext ctx, DatagramPacket dp, InetSocketAddress sender,
            ConnectionDetails connection) {
        // This is an incoming client hello, but we are a client with an
        // outgoing connection - determine a winner for the race...
        InetSocketAddress localAddress = dp.recipient();

        boolean closeThisClient;
        boolean openServer;

        if (localAddress.getPort() != sender.getPort()) {
            closeThisClient = (localAddress.getPort() - sender.getPort()) < 0;
            openServer = closeThisClient;
        } else if (localAddress.getAddress().isAnyLocalAddress()) {
            // There's nothing we can do in this situation except hope to back off
            // so that we don't get another clash
            LOG.warn("Unable to resolve the DTLS connection race with {}. Closing the connection", sender);
            closeThisClient = true;
            openServer = false;
        } else {

            int i = compareAddresses(localAddress.getAddress().getAddress(), sender.getAddress().getAddress());

            if (i == 0) {
                // Ok, this was sent by us to us. Left is right and down is up.
                // Just close everything and log the catastrophe
                closeThisClient = true;
                openServer = false;

            } else {
                closeThisClient = i < 0;
                openServer = closeThisClient;
            }
        }

        Map<DatagramPacket, ChannelPromise> existingPendingWrites = null;
        if (closeThisClient) {
            // Hand over the pending writes to the server
            if (openServer) {
                existingPendingWrites = new LinkedHashMap<>(connection.pendingWrites);
                connection.pendingWrites.clear();
            }
            // Stop sending client data.
            removeOnClose(sender, connection);
            try {
                connection.handler.close(ctx, false);
            } catch (Exception e) {}
        }

        // We always null out the connection as we don't want to add the client hello
        // to the "winning" client or the "losing" client
        connection = null;

        if (openServer) {
            DtlsEngine engine = engineSupplier.get();
            engine.setClient(false);
            connection = new ConnectionDetails(false, new ParemusServerDTLSHandler(engine, ctx, sender));
            connection.pendingWrites.putAll(existingPendingWrites);
            connections.put(sender, connection);
            final ConnectionDetails pending = connection;
            connection.handler.handshakeFuture().addListener(f -> onConnect(ctx, pending, f));
            connection.handler.closeFuture().addListener(f -> removeOnClose(sender, pending));
        }
        return connection;
    }

    private int compareAddresses(byte[] o1, byte[] o2) {
        int length = Math.min(o1.length, o2.length);

        for (int i = 0; i < length; i++) {
            int val = (0xFF & o1[i]) - (0xFF & o2[i]);
            if (val != 0) {
                return val;
            }
        }

        return o1.length - o2.length;
    }

    @Override
    public Future<Channel> handshakeFuture(SocketAddress socketAddress) {
        ConnectionDetails connectionDetails = connections.get(socketAddress);

        Future<Channel> promise = null;
        if (connectionDetails != null) {
            promise = connectionDetails.handler.handshakeFuture();
        }
        return promise;
    }

    @Override
    public Future<Channel> handshake(SocketAddress socketAddress) {
        ChannelHandlerContext ctx = this.ctx;

        if (ctx == null) {
            throw new IllegalStateException("This handler has not been added to a Channel");
        }

        if (!(socketAddress instanceof InetSocketAddress)) {
            return ctx.executor().newFailedFuture(new IllegalArgumentException("Not an InetSocketAddress " + socketAddress));
        }

        EventLoop loop = ctx.channel().eventLoop();
        if (loop.inEventLoop()) {
            return doHandshake(ctx, socketAddress).handler.handshakeFuture();
        } else {
            Promise<Channel> cp = ctx.executor().newPromise();
            loop.execute(() -> doHandshake(ctx, socketAddress).handler.handshakeFuture()
                    .addListener(new PromiseNotifier<>(cp)));
            return cp;
        }
    }

    private ConnectionDetails doHandshake(ChannelHandlerContext ctx, SocketAddress socketAddress) {

        ConnectionDetails details = connections.get(socketAddress);

        if (details != null) {
            return details;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending a DTLS handshake to {}", socketAddress);
        }

        lastApplicationData.put(socketAddress, Instant.now());

        DtlsEngine engine = engineSupplier.get();
        engine.setClient(true);

        details = new ConnectionDetails(true,
                new ParemusClientDTLSHandler(engine, ctx, (InetSocketAddress) socketAddress));
        connections.put(socketAddress, details);

        final ConnectionDetails finalDetails = details;
        details.handler.handshakeFuture().addListener(f -> onConnect(ctx, finalDetails, f));
        details.handler.closeFuture().addListener(f -> removeOnClose(socketAddress, finalDetails));
        
        try {
            details.handler.channelActive(ctx);
        } catch (Exception e) {
            LOG.warn("Failed to activate the connection to {}", socketAddress);
            e.printStackTrace();
        }

        if (LOG.isDebugEnabled()) {
            details.handler.handshakeFuture().addListener(f -> {
                if (connections.get(socketAddress) == finalDetails) {
                    if (f.isSuccess()) {
                        LOG.debug("Successful outgoing handshake with {}", socketAddress);
                    } else {
                        LOG.debug("Failed outgoing handshake with {}", socketAddress);
                    }
                }
            });
        }

        return details;
    }

    @Override
    public Future<Void> disconnect(SocketAddress socketAddress) {
        ChannelHandlerContext ctx = this.ctx;

        if (ctx == null) {
            throw new IllegalStateException("This handler has not been added to a Channel");
        }

        EventLoop loop = ctx.channel().eventLoop();
        if (loop.inEventLoop()) {
            return doDisconnect(ctx, socketAddress);
        } else {
            Promise<Void> cp = ctx.executor().newPromise();
            loop.execute(() -> doDisconnect(ctx, socketAddress).addListener(new PromiseNotifier<>(cp)));
            return cp;
        }
    }

    private ChannelFuture doDisconnect(ChannelHandlerContext ctx, SocketAddress socketAddress) {
        ConnectionDetails details = connections.remove(socketAddress);

        if (details == null) {
            return ctx.newSucceededFuture();
        } else {
            ChannelPromise promise = ctx.newPromise();

            try {
                details.handler.disconnect(ctx, promise);
            } catch (Exception e) {
                promise.tryFailure(e);
            } finally {
            }
            return promise;
        }
    }

    @Override
    public Collection<? extends SocketAddress> activeAndPending() {
        return new HashSet<>(connections.keySet());
    }

}
