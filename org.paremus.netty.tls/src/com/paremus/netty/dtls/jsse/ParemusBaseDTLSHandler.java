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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import org.bouncycastle.tls.ContentType;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.netty.dtls.adapter.DtlsEngine;
import com.paremus.netty.dtls.adapter.DtlsEngineResult;
import com.paremus.netty.dtls.adapter.DtlsEngineResult.OperationRequired;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.concurrent.PromiseNotifier;

public abstract class ParemusBaseDTLSHandler extends ChannelDuplexHandler implements InternalDTLSHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ParemusBaseDTLSHandler.class);

    protected final DtlsEngine sslEngine;

    private final boolean embedded;

    protected volatile ChannelHandlerContext ctx;

    private final List<ByteBuf> retransmitBuffer = new ArrayList<>();

    private int retransmitTimeoutMillis = 100_0000;
    private int connectionTimeoutMillis = 10000_0000;

    private ScheduledFuture<?> retransmitTimer;

    private ScheduledFuture<?> timeoutTimer;

    private final Map<DatagramPacket, ChannelPromise> pendingWrites = new LinkedHashMap<>();

    protected final Promise<Channel> handshakePromise;

    protected final Promise<Void> closePromise;

    protected volatile InetSocketAddress remotePeer;

    protected boolean closed;

    private boolean doFlush;

    private final class LateBindingPromise<V> extends DefaultPromise<V> {

        @Override
        protected EventExecutor executor() {
            if (ctx == null) {
                throw new IllegalStateException();
            } else {
                return ctx.executor();
            }
        }
    }

    public ParemusBaseDTLSHandler(DtlsEngine engine) {
        this.sslEngine = engine;
        this.embedded = false;
        this.handshakePromise = new LateBindingPromise<>();
        this.closePromise = new LateBindingPromise<>();
    }

    ParemusBaseDTLSHandler(DtlsEngine engine, ChannelHandlerContext ctx, InetSocketAddress remotePeer) {
        this.sslEngine = engine;
        this.embedded = true;
        handshakePromise = ctx.executor().newPromise();
        closePromise = ctx.executor().newPromise();
        this.remotePeer = remotePeer == null ? (InetSocketAddress) ctx.channel().remoteAddress() : remotePeer;
    }

    @Override
    public Future<Channel> handshakeFuture() {
        return handshakePromise;
    }

    @Override
    public SocketAddress getRemotePeerAddress() {
        return remotePeer;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        SocketAddress remoteAddress = ctx.channel().remoteAddress();

        if (remotePeer != null) {
            if (!remotePeer.equals(remoteAddress)) {
                LOG.error("The channel {} is connected to {} but the DTLS session is already paired with {}",
                        ctx.channel(), remoteAddress, remotePeer);
                return;
            }
        } else {
            remotePeer = (InetSocketAddress) remoteAddress;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        close(ctx, true);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        LOG.debug("The connection to {} is being disconnected and so the DTLS session is being closed", remotePeer);
        Future<Void> close = close(ctx, true);
        if (embedded) {
            if (!promise.isVoid()) {
                close.addListener(new PromiseNotifier<>(promise));
            }
        } else {
            close.addListener(f -> ctx.disconnect(promise));
        }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        LOG.debug("The channel {} is being closed and so the DTLS session is being closed", ctx.channel());
        close(ctx, true).addListener(f -> ctx.close(promise));
    }

    public Future<Void> closeFuture() {
        return closePromise;
    }

    public Future<Void> close(ChannelHandlerContext ctx, boolean sendCloseData) {
        if (closed) {
            return closePromise;
        } else {
            closed = true;
        }

        sslEngine.closeOutbound();
        handshakePromise.tryFailure(new IOException("Channel closed"));
        if (sendCloseData) {
            processOperationRequired(ctx, sslEngine.getOperationRequired()).addListener(new PromiseNotifier<>(false, closePromise));
            if (doFlush) {
                ctx.flush();
                doFlush = false;
            }
        } else {
            closePromise.trySuccess(null);
        }

        retransmitBuffer.forEach(ReferenceCountUtil::safeRelease);
        retransmitBuffer.clear();

        if (retransmitTimer != null) {
            retransmitTimer.cancel(false);
            retransmitTimer = null;
        }

        if (timeoutTimer != null) {
            timeoutTimer.cancel(false);
            timeoutTimer = null;
        }

        return closePromise;
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
            ChannelPromise promise) throws Exception {

        InetSocketAddress remote = (InetSocketAddress) remoteAddress;

        boolean registerHandshake = false;
        if (remotePeer != null) {
            if (!remotePeer.equals(remote)) {
                LOG.error("The channel {} is being connected to {} but the DTLS session is already paired with {}",
                        ctx.channel(), remoteAddress, remotePeer);
                promise.setFailure(new IllegalArgumentException("This handler is configured to communicate with "
                        + remotePeer + " and so cannot be connected to " + remoteAddress));
                return;
            } else {
                registerHandshake = true;
            }
        } else {
            remotePeer = remote;
            registerHandshake = true;
        }

        if (registerHandshake) {
            promise.addListener(f -> {
                if (f.isSuccess()) {
                    beginHandShake(ctx);
                }
            });
        }
        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        if (remotePeer == null) {
            LOG.error("The DTLS handler for channel {} is not configured with or connected to a remote address",
                    ctx.channel());
            promise.tryFailure(new IllegalStateException("This channel is not yet connected"));
            return;
        }

        ByteBuf buf;
        if (msg instanceof ByteBuf) {
            buf = (ByteBuf) msg;
        } else if (msg instanceof DatagramPacket) {
            DatagramPacket dp = (DatagramPacket) msg;
            if (!dp.recipient().equals(remotePeer)) {
                LOG.error("A message is being sent using channel {} to {} but the DTLS session is paired with {}",
                        ctx.channel(), dp.recipient(), remotePeer);
                promise.tryFailure(new IllegalArgumentException(
                        "The recipient was incorrect, expected " + remotePeer + " but was " + dp.recipient()));
                ReferenceCountUtil.safeRelease(dp);
                return;
            }
            buf = dp.content();
        } else {
            return;
        }

        if (handshakePromise.isSuccess()) {
            generateDataToSend(ctx, buf, promise);
            if (doFlush) {
                ctx.flush();
                doFlush = false;
            }
        } else {
            LOG.error("The DTLS connection from {} to {} is not yet finished handshaking so no data can be sent",
                    ctx.channel().localAddress(), remotePeer);
            promise.tryFailure(new IllegalStateException("The DTLS connection is not yet finished handshaking"));
        }
    }

    private void generateDataToSend(ChannelHandlerContext ctx, ByteBuf appPlaintext, ChannelPromise promise) {

        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending {} bytes of plaintext to {}", appPlaintext.readableBytes(), remotePeer);
        }

        ByteBuf output = ctx.alloc().buffer(appPlaintext.readableBytes() + 64);
        try {
            boolean overflow = false;

            loop: while (appPlaintext.isReadable()) {
                DtlsEngineResult result;
                try {
                    result = sslEngine.generateDataToSend(appPlaintext, output);
                } catch (Exception e) {
                    LOG.error("Failed to send data to {}", remotePeer, e);
                    promise.setFailure(e);
                    if (embedded) {
                        close(ctx, true);
                    } else {
                        close(ctx, ctx.newPromise());
                    }
                    return;
                }

                switch (result.getOperationResult()) {
                case TOO_MUCH_OUTPUT:
                    if (overflow) {
                        LOG.error("Multiple successive buffer overflows have occurred in the connection to {}",
                                remotePeer);
                        if (embedded) {
                            close(ctx, true);
                        } else {
                            close(ctx, ctx.newPromise());
                        }
                        promise.tryFailure(new SSLException("Repeated overflows have occurred"));
                        break loop;
                    }

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Overflowed the output buffer, re-allocating");
                    }

                    output = resizedOutputBuffer(ctx, sslEngine.getMaxSendOutputBufferSize(), output);
                    continue loop;
                case INSUFFICIENT_INPUT:
                    LOG.error("An underflow occurred when sending data to {}", remotePeer);
                    if (embedded) {
                        close(ctx, true);
                    } else {
                        close(ctx, ctx.newPromise());
                    }
                    promise.tryFailure(new SSLException("An unexpected underflow has occurred"));
                    break loop;
                case ENGINE_CLOSED:
                    IllegalStateException ise = new IllegalStateException("The engine is now closed");
                    pendingWrites.values().forEach(cp -> cp.tryFailure(ise));
                    promise.tryFailure(ise);
                    break loop;
                case OK:
                    ctx.write(new DatagramPacket(output.readRetainedSlice(output.readableBytes()), remotePeer), promise);
                    break;
                default:
                    break;
                }

                processOperationRequired(ctx, result.getOperationRequired());
            }
        } finally {
            ReferenceCountUtil.release(output);
        }
    }

    @SuppressWarnings("deprecation")
    private ChannelFuture processOperationRequired(ChannelHandlerContext ctx, OperationRequired operationRequired) {
        
        PromiseCombiner combiner = null;
        ChannelFuture cf = null;
        
        handshake_loop: for (;;) {
            switch (operationRequired) {
            case NONE:
                if (!handshakePromise.isDone()) {
                    LOG.debug("Completing the initial handshake");
                    handshakePromise.trySuccess(ctx.channel());
                }
                break handshake_loop;
            case RUN_TASK:
                LOG.debug("Running tasks for the SSL engine");
                runTasks();
                operationRequired = sslEngine.getOperationRequired();
                continue handshake_loop;
            case AWAITING_DATA:
                // This will happen when we next receive data
                break handshake_loop;
            case DATA_TO_SEND:
                LOG.debug("Generating handshake data to send to {}", remotePeer);
                ChannelFuture tmp = handshakeWrap(ctx);
                
                if (cf == null) {
                    cf = tmp;
                } else {
                    if (combiner == null) {
                        combiner = new PromiseCombiner();
                        combiner.add(cf);
                    }
                    combiner.add(tmp);
                }
                // The handshakeWrap call will exhaust the sslEngine of wrap tasks, 
                // but we may have additional other tasks
                operationRequired = sslEngine.getOperationRequired();
                continue handshake_loop;
            case PENDING_RECEIVED_DATA:
                LOG.debug("Additional data from {} must be unwrapped", remotePeer);
                handleIncomingData(
                        ctx, new DatagramPacket(Unpooled.EMPTY_BUFFER,
                                (InetSocketAddress) ctx.channel().localAddress(), remotePeer),
                        Unpooled.EMPTY_BUFFER, true);
                operationRequired = sslEngine.getOperationRequired();
                continue handshake_loop;
            default:
                LOG.error("The OperationRequired status {} for the connection to {} is not understood", operationRequired, remotePeer);
                if (embedded) {
                    close(ctx, true);
                } else {
                    close(ctx, ctx.newPromise());
                }
            }
        }
    
        if (combiner != null) {
            ChannelPromise cp = ctx.newPromise();
            combiner.finish(cp);
            return cp;
        } else if (cf != null) {
            return cf;
        } else {
            return ctx.newSucceededFuture();
        }
    }

    private void scheduleRetransmission(ChannelHandlerContext ctx, int cycle) {
        retransmitTimer = ctx.executor().schedule(() -> {

            if (!retransmitBuffer.isEmpty()) {
                LOG.debug("Retransmitting unacknowledged handshake data - repeat {}", cycle + 1);
                retransmitBuffer
                        .forEach(b -> ctx.write(new DatagramPacket(b.retainedSlice(), remotePeer), ctx.voidPromise()));
                ctx.flush();
                scheduleRetransmission(ctx, cycle + 1);
            }
        }, retransmitTimeoutMillis << cycle, TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof DatagramPacket) {
            DatagramPacket dp = (DatagramPacket) msg;

            ByteBuf encrypted = dp.content();
            InetSocketAddress sender = dp.sender();

            if (!sender.equals(remotePeer)) {
                LOG.warn("The packet was received from {} but this DTLS connection is with {}. Discarding it", sender,
                        remotePeer);
                return;
            }

            try {
                if (!encrypted.isReadable()) {
                    LOG.warn("The packet received was empty. Discarding it", sender, remotePeer);
                } else {
                    handleIncomingData(ctx, dp, encrypted, false);
                    if (doFlush) {
                        ctx.flush();
                        doFlush = false;
                    }
                }
            } finally {
                ReferenceCountUtil.safeRelease(dp);
            }

        } else {
            LOG.error("The message received was not a DatagramPacket. It was a {}",
                    msg == null ? null : msg.getClass());
        }
    }

    private void handleIncomingData(ChannelHandlerContext ctx, DatagramPacket dp, ByteBuf encrypted, boolean unwrapAgain) {
        int maxReceiveOutputBufferSize = sslEngine.getMaxReceiveOutputBufferSize();
        ByteBuf decrypted = ctx.alloc()
                .buffer(unwrapAgain ? maxReceiveOutputBufferSize : encrypted.readableBytes());

        try {
            boolean overflow = false;

            outer_loop: while (unwrapAgain || encrypted.isReadable()) {

                int encryptedReaderIndex = encrypted.readerIndex();

                DtlsEngineResult result;

                try {
                    result = sslEngine.handleReceivedData(unwrapAgain ? Unpooled.EMPTY_BUFFER : 
                        encrypted, decrypted);
                } catch (Exception exception) {
                    LOG.error("Failed to receive data from {}", dp.sender(), exception);
                    if (embedded) {
                        close(ctx, true);
                    } else {
                        close(ctx, ctx.newPromise());
                    }
                    return;
                }

                boolean close = false;

                switch (result.getOperationResult()) {
                case TOO_MUCH_OUTPUT:
                    if (overflow) {
                        LOG.error(
                                "Multiple successive buffer overflows have occurred receiving data from {}. Ignoring the packet",
                                remotePeer);
                        return;
                    }
                    overflow = true;
                    
                    decrypted = resizedOutputBuffer(ctx, maxReceiveOutputBufferSize, decrypted);
                    continue outer_loop;
                case INSUFFICIENT_INPUT:
                    LOG.error("A buffer underflow has occurred receiving data from {}. Ignoring the packet",
                            remotePeer);
                    // Just abandon the packet
                    return;
                case ENGINE_CLOSED:
                    close = true;
                    // Fall through
                case OK:
                    if (!retransmitBuffer.isEmpty() && !unwrapAgain) {
                        LOG.debug("Attempting to remove values from the retransmission buffer");
                        clearRetransmitBuffer(encrypted.slice(encryptedReaderIndex,
                                encrypted.readerIndex()), retransmitBuffer);
                        if (retransmitBuffer.isEmpty() && retransmitTimer != null) {
                            retransmitTimer.cancel(false);
                            retransmitTimer = null;
                        }
                    }

                    if (decrypted.isReadable()) {
                        ctx.fireChannelRead(dp.replace(decrypted.readRetainedSlice(decrypted.readableBytes())));
                    }
                    break;
                default:
                    break;
                }

                overflow = false;

                if (close) {
                    if (embedded) {
                        close(ctx, true);
                    } else {
                        close(ctx, ctx.newPromise());
                    }
                    break outer_loop;
                }

                if(!unwrapAgain) {
                    processOperationRequired(ctx, result.getOperationRequired());
                } else {
                    break outer_loop;
                }
            }
        } finally {
            ReferenceCountUtil.release(decrypted);
        }
    }

    private ByteBuf resizedOutputBuffer(ChannelHandlerContext ctx, int requiredSize, ByteBuf output) {
        int expansion = requiredSize - output.writableBytes();
        if(expansion > 0) {
            int newCapacity = expansion + output.capacity();
            if(newCapacity > output.maxCapacity()) {
                output.release();
                output = ctx.alloc().buffer(requiredSize);
            } else {
                output.capacity(newCapacity);
            }
        }
       
        return output;
    }

    private void runTasks() {
        Runnable r;
        while ((r = sslEngine.getTaskToRun()) != null) {
            ImmediateEventExecutor.INSTANCE.execute(r);
        }
    }

    protected void beginHandShake(ChannelHandlerContext ctx) {
        if (!handshakePromise.isDone() && timeoutTimer == null) {
            if (sslEngine.isClient()) {
                if (remotePeer == null) {
                    remotePeer = (InetSocketAddress) ctx.channel().remoteAddress();
                    if (remotePeer == null) {
                        LOG.error("No remote peer has been defined for the DTLS");
                    }
                }

                LOG.debug("Beginning outgoing handshake with remote peer {}", remotePeer);
                try {
                    sslEngine.startHandshaking();

                    timeoutTimer = ctx.executor().schedule(() -> {
                        if (handshakePromise
                                .tryFailure(new TimeoutException("DTLS connection to " + remotePeer + " timed out"))) {
                            sslEngine.closeOutbound();
                            processOperationRequired(ctx, sslEngine.getOperationRequired());
                            handshakeWrap(ctx);
                        }
                    }, connectionTimeoutMillis, TimeUnit.MILLISECONDS);

                    handshakePromise.addListener(f -> {
                        timeoutTimer.cancel(false);
                        timeoutTimer = null;
                    });

                    processOperationRequired(ctx, sslEngine.getOperationRequired());

                    // Always flush after initiating the handshake;
                    ctx.flush();
                    doFlush = false;
                } catch (Exception e) {
                    LOG.error("Failed to start a handshake with {}", remotePeer);
                    handshakePromise.tryFailure(e);
                }
            } else {
                LOG.error("Unable to begin a handshake from a server SSLEngine");
                throw new IllegalStateException("Unable to begin a handshake from a server SSLEngine");
            }
        }
    }

    /**
     * We suppress the deprecation warning for using the old PromiseCombiner constructor
     * @param ctx
     * @return
     */
    @SuppressWarnings("deprecation")
    private ChannelFuture handshakeWrap(ChannelHandlerContext ctx) {
        // We should be able to go smaller than this, but the JDK impl likes a
        // buffer which can fit the maximum packet even though it only uses a small bit.
        int maxSendOutputBufferSize = sslEngine.getMaxSendOutputBufferSize();
        ByteBuf output = ctx.alloc().buffer(maxSendOutputBufferSize);

        PromiseCombiner combiner = null;
        ChannelFuture cf = null;

        try {
            boolean overflow = false;
            loop: for (;;) {
                DtlsEngineResult result;
                try {
                    result = sslEngine.generateDataToSend(Unpooled.EMPTY_BUFFER, output);
                } catch (Exception e) {
                    handshakePromise.tryFailure(e);
                    LOG.error("Failed to handshake with {}", remotePeer, e);
                    if (embedded) {
                        close(ctx, true);
                    } else {
                        close(ctx, ctx.newPromise());
                    }
                    break loop;
                }

                switch (result.getOperationResult()) {
                case TOO_MUCH_OUTPUT:
                    if (overflow) {
                        LOG.error(
                                "Multiple successive buffer overflows have occurred receiving data from {}. Ignoring the packet",
                                remotePeer);
                        if (embedded) {
                            close(ctx, true);
                        } else {
                            close(ctx, ctx.newPromise());
                        }
                        return ctx.newFailedFuture(
                                new SSLException("The handshake repeatedly failed with a Buffer Overflow"));
                    }
                    overflow = true;
                    output = resizedOutputBuffer(ctx, maxSendOutputBufferSize, output);
                    continue loop;
                case INSUFFICIENT_INPUT:
                    LOG.error("An underflow occurred when sending handshake data to {}", remotePeer);
                    if (embedded) {
                        close(ctx, true);
                    } else {
                        close(ctx, ctx.newPromise());
                    }
                    break loop;
                case ENGINE_CLOSED:
                case OK:
                    if (output.isReadable()) {
                        ByteBuf data = output.readRetainedSlice(output.readableBytes());
                        if (shouldRegisterForRetransmission(data)) {
                            retransmitBuffer.add(data.retainedSlice());
                            if (retransmitTimer == null) {
                                scheduleRetransmission(ctx, 0);
                            }
                        }
                        ChannelFuture tmp = ctx.write(new DatagramPacket(data, remotePeer));
                        // We have written some handshake data so a flush will be needed
                        doFlush = true;

                        if (cf == null) {
                            cf = tmp;
                        } else {
                            if (combiner == null) {
                                combiner = new PromiseCombiner();
                                combiner.add(cf);
                            }
                            combiner.add(tmp);
                        }
                    }
                    break;
                default:
                    break;
                }

                overflow = false;

                if(result.getOperationRequired() == OperationRequired.DATA_TO_SEND) {
                    continue loop;
                }
                break loop;
            }
        } finally {
            ReferenceCountUtil.safeRelease(output);
        }
        if (combiner != null) {
            ChannelPromise cp = ctx.newPromise();
            combiner.finish(cp);
            return cp;
        } else if (cf != null) {
            return cf;
        } else {
            return ctx.newSucceededFuture();
        }
    }

    protected abstract boolean shouldRegisterForRetransmission(ByteBuf msg);

    protected boolean isHandshakeMessage(ByteBuf buf) {
        return ContentType.handshake == buf.getUnsignedByte(buf.readerIndex());
    }

    protected short getHandshakeMessageType(ByteBuf buf) {

        if (!isHandshakeMessage(buf)) {
            throw new IllegalArgumentException("The message is not a handshake message");
        }

        int index = buf.readerIndex();

        ProtocolVersion version;
        try {
            version = getProtocolVersion(buf, index);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to determine the version of the handshake message", e);
        }
        if (version.isEqualOrEarlierVersionOf(ProtocolVersion.DTLSv12)) {
                // DTLS 1.0 and 1.2 are identical
                return buf.getUnsignedByte(index + 13);
        } else if (version.getPreviousVersion().equals(ProtocolVersion.DTLSv12)) {
            // DTLS 1.3 uses different behaviour including acks
            return buf.getUnsignedByte(index + 13);
        } else {
            throw new IllegalArgumentException("The message is not a supported version " + version.toString());
        }
    }

    protected ProtocolVersion getProtocolVersion(ByteBuf buf, int index) {
        try {
            return ProtocolVersion.get(buf.getUnsignedByte(index + 1), buf.getUnsignedByte(index + 2));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to determine the version of the handshake message", e);
        }
    }

    protected abstract void clearRetransmitBuffer(ByteBuf received, List<ByteBuf> currentlyRetransmitting);

    protected void removeMessages(List<ByteBuf> retransmit, int epoch, short... messageTypes) {
        retransmit.removeIf(b -> {
            int retransmitEpoch = b.getUnsignedShort(b.readerIndex() + 3);

            boolean toDiscard;
            if (messageTypes.length == 0) {
                toDiscard = retransmitEpoch <= epoch;
            } else {
                toDiscard = retransmitEpoch <= epoch && Arrays.contains(messageTypes, getHandshakeMessageType(b));
            }

            if (toDiscard) {
                ReferenceCountUtil.safeRelease(b);
            }

            return toDiscard;
        });
    }
}
