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
package com.paremus.netty.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;

import com.paremus.netty.tls.MultiplexingDTLSHandler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;

public abstract class AbstractMultiplexingDTLSTest extends AbstractDTLSTest {

    @Test
    public void testECCertificate() throws Exception {
        setupEC();
        sayHelloABC();
    }

    @Test
    public void testRSACertificate() throws Exception {
        setupRSA();
        sayHelloABC();
    }

    private void sayHelloABC() throws InterruptedException {

        udpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addFirst(getMultiplexingHandler(kmf, tmf, parameters));
            }
        });

        Channel alice = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel bob = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel carol = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();

        BlockingQueue<String> aliceMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> carolMessages = new LinkedBlockingQueue<>();

        alice.pipeline().addLast(new CapturingHandler(aliceMessages));
        bob.pipeline().addLast(new CapturingHandler(bobMessages));
        carol.pipeline().addLast(new CapturingHandler(carolMessages));

        ByteBuf buffer = Unpooled.buffer();
        buffer.writeCharSequence("Hello from Alice", UTF_8);
        assertTrue(alice.writeAndFlush(new DatagramPacket(buffer, (InetSocketAddress) bob.localAddress())).await(2000));

        buffer = Unpooled.buffer();
        buffer.writeCharSequence("Hello from Bob", UTF_8);
        assertTrue(bob.writeAndFlush(new DatagramPacket(buffer, (InetSocketAddress) carol.localAddress())).await(2000));

        buffer = Unpooled.buffer();
        buffer.writeCharSequence("Hello from Carol", UTF_8);
        assertTrue(
                carol.writeAndFlush(new DatagramPacket(buffer, (InetSocketAddress) alice.localAddress())).await(2000));

        assertEquals("Hello from Alice", bobMessages.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("Hello from Bob", carolMessages.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("Hello from Carol", aliceMessages.poll(100, TimeUnit.MILLISECONDS));

        assertTrue(alice.close().await(1000));
        assertTrue(bob.close().await(1000));
        assertTrue(carol.close().await(1000));
    }

    @Test
    public void testClientConnectionRace() throws Exception {

        setupEC();

        CountDownLatch latch = new CountDownLatch(2);

        udpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        latch.countDown();
                        if (!latch.await(1, TimeUnit.SECONDS)) {
                            throw new TimeoutException("Did not countdown fast enough");
                        }
                        super.write(ctx, msg, promise);
                    }
                }, getMultiplexingHandler(kmf, tmf, parameters));
            }
        });

        Channel alice = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel bob = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();

        BlockingQueue<String> aliceMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobMessages = new LinkedBlockingQueue<>();

        alice.pipeline().addLast(new CapturingHandler(aliceMessages));
        bob.pipeline().addLast(new CapturingHandler(bobMessages));

        ByteBuf buffer = Unpooled.buffer();
        buffer.writeCharSequence("Hello from Alice", UTF_8);
        ChannelFuture aliceWrite = alice
                .writeAndFlush(new DatagramPacket(buffer, (InetSocketAddress) bob.localAddress()));

        buffer = Unpooled.buffer();
        buffer.writeCharSequence("Hello from Bob", UTF_8);
        ChannelFuture bobWrite = bob
                .writeAndFlush(new DatagramPacket(buffer, (InetSocketAddress) alice.localAddress()));

        assertTrue(aliceWrite.await(1000));
        assertTrue(bobWrite.await(1000));

        assertEquals("Hello from Alice", bobMessages.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("Hello from Bob", aliceMessages.poll(100, TimeUnit.MILLISECONDS));

        alice.close().sync();
        bob.close().sync();
    }

    @Test
    public void testClientInitiatedDisconnect() throws Exception {

        setupEC();

        udpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addFirst(getMultiplexingHandler(kmf, tmf, parameters));
            }
        });

        Channel alice = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel bob = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel carol = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();

        MultiplexingDTLSHandler aliceHandler = alice.pipeline().get(MultiplexingDTLSHandler.class);
        MultiplexingDTLSHandler bobHandler = bob.pipeline().get(MultiplexingDTLSHandler.class);
        MultiplexingDTLSHandler carolHandler = carol.pipeline().get(MultiplexingDTLSHandler.class);

        // Initiate the connection from Alice so that she will be the client
        assertNotNull(aliceHandler.handshake(bob.localAddress()).get(1, TimeUnit.SECONDS));
        assertNotNull(aliceHandler.handshake(carol.localAddress()).get(1, TimeUnit.SECONDS));

        // Bob should have a complete and working handshake
        assertNotNull(bobHandler.handshakeFuture(alice.localAddress()));
        assertTrue(bobHandler.handshakeFuture(alice.localAddress()).isSuccess());

        // Carol should have a complete and working handshake
        assertNotNull(carolHandler.handshakeFuture(alice.localAddress()));
        assertTrue(carolHandler.handshakeFuture(alice.localAddress()).isSuccess());

        // Do some data sending
        BlockingQueue<String> aliceMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> carolMessages = new LinkedBlockingQueue<>();

        alice.pipeline().addLast(new CapturingHandler(aliceMessages));
        bob.pipeline().addLast(new CapturingHandler(bobMessages));
        carol.pipeline().addLast(new CapturingHandler(carolMessages));

        ByteBuf buffer = Unpooled.buffer();
        buffer.writeCharSequence("Hello from Alice", UTF_8);

        assertTrue(alice.writeAndFlush(new DatagramPacket(buffer.retainedSlice(), (InetSocketAddress) bob.localAddress())).await(2000));
        assertTrue(alice.writeAndFlush(new DatagramPacket(buffer.retainedSlice(), (InetSocketAddress) carol.localAddress())).await(2000));

        assertEquals("Hello from Alice", bobMessages.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("Hello from Alice", carolMessages.poll(100, TimeUnit.MILLISECONDS));

        // Disconnect Alice from Bob
        aliceHandler.disconnect(bob.localAddress()).get(1, TimeUnit.SECONDS);

        // We need to give some extra time to process the close messages that get sent
        Thread.sleep(100);

        // Bob and Alice should now be disconnected
        assertNull(aliceHandler.handshakeFuture(bob.localAddress()));
        assertNull(bobHandler.handshakeFuture(alice.localAddress()));

        // Carol should still be connected
        assertTrue(aliceHandler.handshakeFuture(carol.localAddress()).isSuccess());
        assertTrue(carolHandler.handshakeFuture(alice.localAddress()).isSuccess());

        assertTrue(alice.close().await(1000));
        assertTrue(bob.close().await(1000));
        assertTrue(carol.close().await(1000));

    }

    @Test
    public void testSevertInitiatedDisconnect() throws Exception {

        setupEC();

        udpBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addFirst(getMultiplexingHandler(kmf, tmf, parameters));
            }
        });

        Channel alice = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel bob = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel carol = udpBootstrap.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();

        MultiplexingDTLSHandler aliceHandler = alice.pipeline().get(MultiplexingDTLSHandler.class);
        MultiplexingDTLSHandler bobHandler = bob.pipeline().get(MultiplexingDTLSHandler.class);
        MultiplexingDTLSHandler carolHandler = carol.pipeline().get(MultiplexingDTLSHandler.class);

        // Initiate the connection from Alice so that she will be the client
        assertNotNull(aliceHandler.handshake(bob.localAddress()).get(1, TimeUnit.SECONDS));
        assertNotNull(aliceHandler.handshake(carol.localAddress()).get(1, TimeUnit.SECONDS));

        // Bob should have a complete and working handshake
        assertNotNull(bobHandler.handshakeFuture(alice.localAddress()));
        assertTrue(bobHandler.handshakeFuture(alice.localAddress()).isSuccess());

        // Carol should have a complete and working handshake
        assertNotNull(carolHandler.handshakeFuture(alice.localAddress()));
        assertTrue(carolHandler.handshakeFuture(alice.localAddress()).isSuccess());

        // Do some data sending
        BlockingQueue<String> aliceMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> carolMessages = new LinkedBlockingQueue<>();

        alice.pipeline().addLast(new CapturingHandler(aliceMessages));
        bob.pipeline().addLast(new CapturingHandler(bobMessages));
        carol.pipeline().addLast(new CapturingHandler(carolMessages));

        ByteBuf buffer = Unpooled.buffer();
        buffer.writeCharSequence("Hello from Alice", UTF_8);

        assertTrue(alice.writeAndFlush(new DatagramPacket(buffer.retainedSlice(), (InetSocketAddress) bob.localAddress())).await(2000));
        assertTrue(alice.writeAndFlush(new DatagramPacket(buffer.retainedSlice(), (InetSocketAddress) carol.localAddress())).await(2000));

        assertEquals("Hello from Alice", bobMessages.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("Hello from Alice", carolMessages.poll(100, TimeUnit.MILLISECONDS));

        // Disconnect Bob from Alice
        bobHandler.disconnect(alice.localAddress()).get(1, TimeUnit.SECONDS);

        // We need to give some extra time to process the close messages that get sent
        Thread.sleep(100);

        // Bob and Alice should now be disconnected
        assertNull(aliceHandler.handshakeFuture(bob.localAddress()));
        assertNull(bobHandler.handshakeFuture(alice.localAddress()));

        // Carol should still be connected
        assertTrue(aliceHandler.handshakeFuture(carol.localAddress()).isSuccess());
        assertTrue(carolHandler.handshakeFuture(alice.localAddress()).isSuccess());

        assertTrue(alice.close().await(1000));
        assertTrue(bob.close().await(1000));
        assertTrue(carol.close().await(1000));

    }

    protected abstract ChannelHandler getMultiplexingHandler(KeyManagerFactory kmf, TrustManagerFactory tmf,
            SSLParameters parameters) throws Exception;
}
