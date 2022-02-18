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
package com.paremus.gossip.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.osgi.service.cm.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipComms;
import com.paremus.gossip.GossipMessage;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.netty.Config;
import com.paremus.gossip.v1.messages.MessageType;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.netty.tls.ParemusNettyTLS;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;

public class NettyComms implements GossipComms {

	private static final Logger logger = LoggerFactory.getLogger(NettyComms.class);
	
	private final UUID id;
	private final ParemusNettyTLS ssl;

	private final Gossip gossip;
	
	private final InetAddress bindAddress;

	private final EventLoopGroup eventLoop;
	private final DatagramChannel udpChannel;
	@SuppressWarnings("unused")
	private final ServerSocketChannel tcpServerChannel;
	private final Bootstrap tcpClientChannel;
	
	private final AtomicBoolean open = new AtomicBoolean(true);
	private final AtomicLong exchangeIdGenerator = new AtomicLong();

	private final int networkMTU;


	public NettyComms(String cluster, UUID id, Config config, ParemusNettyTLS ssl, Gossip gossip) 
			throws IOException, ConfigurationException, InterruptedException {
		this.id = id;
		this.ssl = ssl;
		this.gossip = gossip;
		
		this.bindAddress = InetAddress.getByName(config.bind_address());
		int discoveredMTU = -1;
		if(bindAddress.isAnyLocalAddress()) {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while(interfaces.hasMoreElements()) {
				NetworkInterface ni = interfaces.nextElement();
				if(discoveredMTU < 0 || discoveredMTU > ni.getMTU()) {
					discoveredMTU = ni.getMTU();
				}
			}
		} else {
			NetworkInterface networkInterface = NetworkInterface.getByInetAddress(bindAddress);
			if(networkInterface != null) {
				discoveredMTU = networkInterface.getMTU();
			}
		}
		
		networkMTU = discoveredMTU <= 0 ? 1500 : discoveredMTU;
		logger.info("The discovered MTU for the gossip cluster {} is {}. If gossip messages regularly exceed this size then packet loss may become an issue.", cluster, networkMTU);
		
		this.eventLoop = new NioEventLoopGroup(1, r -> {
			Thread t = new FastThreadLocalThread(r, "Gossip IO Worker - " + cluster);
			t.setDaemon(true);
			return t;
		});
		
		udpChannel = (DatagramChannel) new Bootstrap().channel(NioDatagramChannel.class)
			.group(eventLoop)
			.handler(new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel ch) throws Exception {
					ChannelHandler dtlsHandler = ssl.getDTLSHandler();
					if(dtlsHandler != null) {
						ch.pipeline().addLast(dtlsHandler);
					}
					ch.pipeline().addLast(new GossipHandler(gossip));
				}
			})
			.bind(bindAddress, config.udp_port()).sync().channel();

		tcpClientChannel = new Bootstrap().channel(NioSocketChannel.class)
				.group(eventLoop)
				.handler(new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel ch) throws Exception {
						SslHandler sslHandler = ssl.getTLSClientHandler();
						if(sslHandler != null) {
							ch.pipeline().addLast(sslHandler);
						}
					}
				});
		
		tcpServerChannel = (ServerSocketChannel) new ServerBootstrap().channel(NioServerSocketChannel.class)
				.group(eventLoop)
				.childHandler(new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel ch) throws Exception {
						SslHandler sslHandler = ssl.getTLSServerHandler();
						if(sslHandler != null) {
							ch.pipeline().addLast(sslHandler);
						}
						ch.pipeline().addLast(new IncomingTCPReplicator(ch, id, gossip));
					}
				})
				.bind(bindAddress, config.tcp_port()).sync().channel();
		
		
		if(logger.isDebugEnabled()) {
			logger.debug("Gossip communications for {} in cluster {} reserving UDP port {} and TCP port {}",
					new Object[] {id, cluster, config.udp_port(), config.tcp_port()});
		}
		
	}
	
	public List<Future<?>> destroy() {
		if ( open.getAndSet(false)==false)
			return Collections.emptyList();
		List<Future<?>> l= new ArrayList<>();
		l.add(udpChannel.close());
		l.add(tcpServerChannel.close());
		l.add(eventLoop.shutdownGracefully(200, 500, TimeUnit.MILLISECONDS));
		return l;
	}
	
	private Instant lastReportedLargeMessage;
	
	/* (non-Javadoc)
	 * @see com.paremus.gossip.net.GossipComms#publish(byte[], java.util.Collection)
	 */
	@Override
	public void publish(GossipMessage message, Collection<InetSocketAddress> participants) {
		if(!open.get() || participants.isEmpty()) {
			return;
		}
		
		ByteBuf buf = udpChannel.alloc().ioBuffer(message.estimateSize() + 3);
        try {
        	buf.writeByte(2);
        	buf.writeByte(1);
			buf.writeByte(message.getType().ordinal());
			message.writeOut(buf);

			if(logger.isInfoEnabled()) {
				int size = buf.readableBytes();
				if(size > networkMTU) {
					Instant now = Instant.now();
					boolean log;
					synchronized (this) {
						if(lastReportedLargeMessage == null ||
								now.isAfter(lastReportedLargeMessage.plus(5, ChronoUnit.MINUTES))) {
							lastReportedLargeMessage = now;
							log = true;
						} else {
							log = false;
						}
					}
					if(log) {
						logger.info("A large gossip message ({} bytes) is being sent, this often indicates that a message is being forwarded too many times. This message will be suppressed for the next 5 minutes", size);
					}
				}
			}
			
			participants.stream().forEach(p -> safeSend(p, buf));
        } catch (Exception e) {
			logger.error("Unable to send a gossipmessage", e);
		} finally {
        	buf.release();
        }
	}
	
	private void safeSend(InetSocketAddress p, ByteBuf data) {
		ChannelPromise writePromise;
		if(logger.isDebugEnabled()) {
			writePromise = udpChannel.newPromise();
			writePromise.addListener(f -> {
				if(!f.isSuccess()) {
					logger.debug("Unable to send a message to {}", p);
				}
			});
		} else {
			writePromise = udpChannel.voidPromise();
		}
		
		udpChannel.writeAndFlush(new DatagramPacket(data.retainedDuplicate(), p), writePromise);
	}
	
	/* (non-Javadoc)
	 * @see com.paremus.gossip.net.GossipComms#replicate(com.paremus.gossip.cluster.impl.MemberInfo, java.util.Collection)
	 */
	@Override
	public Future<Void> replicate(MemberInfo member,
			Collection<Snapshot> snapshots) {
		if(!open.get()) {
			IllegalStateException failure = new IllegalStateException("Communications have been shut down");
			logger.error("Unable to synchronize members", failure);
			return GlobalEventExecutor.INSTANCE.newFailedFuture(failure);
		}
		
		ChannelFuture connect = tcpClientChannel.connect(member.getTcpAddress());
		
		SslHandler sslHandler = connect.channel().pipeline().get(SslHandler.class);
		
		OutgoingTCPReplicator replicator = new OutgoingTCPReplicator(connect.channel(), id, 
				gossip, member.getId(), exchangeIdGenerator.get(), snapshots, 
				sslHandler == null ? connect : sslHandler.handshakeFuture());
		
		connect.channel().pipeline().addLast(replicator);

		return replicator.getSyncCompletionFuture();
	}

	public InetAddress getBindAddress() {
		return bindAddress;
	}

	@Override
	public boolean preventIndirectDiscovery() {
		return ssl.getTLSServerHandler() != null;
	}
	
	public static class GossipHandler extends ChannelInboundHandlerAdapter {

		private final Gossip gossip;
		
		public GossipHandler(Gossip gossip) {
			this.gossip = gossip;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object data) throws Exception {
			DatagramPacket dp = (DatagramPacket) data;
			try {
				ByteBuf content = dp.content();
				
				InetSocketAddress sender = dp.sender();
				if(logger.isTraceEnabled()) {
					logger.trace("Received Gossip from {}", sender);
				}
				
				byte header = content.readByte();
				if(header != 2) {
					logger.warn("Received an invalid gossip message from {}", sender);
				} else {
					
					int version = content.readUnsignedByte();
					if(version != 1) {
						logger.error("The version {} from {} is not supported.", version, sender);
						return;
					}

					MessageType messageType;
					int type = content.readUnsignedByte();
					try {
						messageType = MessageType.values()[type];
					} catch (ArrayIndexOutOfBoundsException aioobe) {
						logger.error("The type {} from {} is not supported.", type, sender);
						return;
					}
					
					gossip.handleMessage(sender, messageType.fromBuffer(content));
				}
			} finally {
				dp.release();
			}
		}
	}
}
