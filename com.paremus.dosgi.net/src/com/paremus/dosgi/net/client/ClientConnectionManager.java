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
package com.paremus.dosgi.net.client;

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toMap;
import static org.osgi.framework.ServiceException.REMOTE;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import org.osgi.framework.ServiceException;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.config.Config;
import com.paremus.dosgi.net.config.ProtocolScheme;
import com.paremus.dosgi.net.proxy.MethodCallHandlerFactory;
import com.paremus.dosgi.net.serialize.SerializerFactory;
import com.paremus.dosgi.net.tcp.LengthFieldPopulator;
import com.paremus.dosgi.net.tcp.VersionCheckingLengthFieldBasedFrameDecoder;
import com.paremus.net.encode.EncodingSchemeFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;

public class ClientConnectionManager {

	private static final Logger LOG = LoggerFactory.getLogger(ClientConnectionManager.class);
	
	private final AtomicInteger ioThreadId = new AtomicInteger(1);
	private final AtomicInteger workerThreadId = new AtomicInteger(1);
	
	final ConcurrentMap<InetSocketAddress, Channel> activeChannels = new ConcurrentHashMap<>();

	final ConcurrentMap<UUID, MethodCallHandlerFactoryImpl> activeHandlers = new ConcurrentHashMap<>();

	private final ConcurrentMap<Channel, Set<MethodCallHandlerFactoryImpl>> channelsToHandlers = new ConcurrentHashMap<>();
	
	private final EventLoopGroup clientIo;
	private final EventExecutorGroup clientWorkers;
	
	private final ByteBufAllocator allocator;

	private final EncodingSchemeFactory esf;
	private final Map<String, Function<Consumer<Channel>, Bootstrap>> configuredTransports;

	private final Timer timer;

	public ClientConnectionManager(Config config, EncodingSchemeFactory esf, ByteBufAllocator allocator) {
		this.esf = esf;
		this.allocator = allocator;
		clientIo = new NioEventLoopGroup(config.client_io_threads(), r -> {
			Thread thread = new FastThreadLocalThread(r, 
					"Paremus RSA distribution client IO: " + ioThreadId.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}); 
			
		clientWorkers = new DefaultEventExecutorGroup(config.client_worker_threads(), r -> {
					Thread thread = new FastThreadLocalThread(r, 
							"Paremus RSA distribution client Worker: " + workerThreadId.getAndIncrement());
					thread.setDaemon(true);
					return thread;
				});
		
		timer = new HashedWheelTimer(r -> {
			Thread thread = new FastThreadLocalThread(r, 
					"Paremus RSA distribution client timeout worker");
			thread.setDaemon(true);
			return thread;
		}, 100, MILLISECONDS, 16384);
		
		configuredTransports = config.client_protocols().stream()
				.filter(p -> {
					if(config.allow_insecure_transports() || p.getProtocol().isSecure()) {
						return true;
					}
					LOG.warn("The client transport {} is not permitted because it is insecure and insecure transports are not enabled.",
							p.getProtocol());
					return false;
				})
			.collect(toMap(p -> p.getProtocol().getUriScheme(), p -> createBootstrapConfigFor(p)));
		
	}

	private Function<Consumer<Channel>, Bootstrap> createBootstrapConfigFor(ProtocolScheme p) {
		
		if(p.getPort() != 0) {
			LOG.warn("The client protocol configuration {} for transport {} contains a port assignment. Clients do not listen for connections and so this property will be ignored",
						p.getConfigurationString(), p.getProtocol());
		}
		
		return customizer -> {
			Bootstrap b = new Bootstrap();
			b.group(clientIo)
				.option(ChannelOption.ALLOCATOR, allocator)
				.option(ChannelOption.SO_SNDBUF, p.getSendBufferSize())
				.option(ChannelOption.SO_RCVBUF, p.getReceiveBufferSize());
				
			Consumer<Channel> c = ch -> {};
			boolean clientAuth = false;
			switch(p.getProtocol()) {
				case TCP_CLIENT_AUTH :
					clientAuth = true;
				case TCP_TLS :
					boolean useClientAuth = clientAuth;
					KeyManagerFactory sslKeyManagerFactory = esf.getSSLKeyManagerFactory();
					TrustManagerFactory sslTrustManagerFactory = esf.getSSLTrustManagerFactory();
					if(sslTrustManagerFactory == null || (useClientAuth && sslKeyManagerFactory == null)) {
						LOG.error("The secure transport {} cannot be configured as the necessary certificate configuration is unavailable. Please check the configuration of the com.paremus.net.encode provider.",
								p.getProtocol());
						return null;
					}
					
					c = c.andThen(ch -> {
						String ciphers = p.getOption("ciphers", String.class);
						ciphers = ciphers == null ? "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256" : ciphers;
						String protocols = p.getOption("protocols", String.class);
						protocols = protocols == null ? "TLSv1.2" : ciphers;
						
						SslContext sslContext;
						try {
							SslContextBuilder builder = SslContextBuilder.forClient();
							if(useClientAuth) builder.keyManager(sslKeyManagerFactory);
							sslContext = builder
								.trustManager(sslTrustManagerFactory)
								.ciphers(asList(ciphers.split(",")))
								.build();
						} catch (Exception e) {
							LOG.error("There was an error creating a secure transport.", e);
							throw new RuntimeException("Unable to create the SSL Engine", e);
						}
						SSLEngine engine = sslContext.newEngine(allocator);
						engine.setWantClientAuth(useClientAuth);
						engine.setNeedClientAuth(useClientAuth);
						engine.setEnabledProtocols(protocols.split(","));
						
						SslHandler sslHandler = new SslHandler(engine);

						Integer handshakeTimeout = p.getOption("handshake.timeout", Integer.class);
						if(handshakeTimeout != null) {
							if(handshakeTimeout < 1 || handshakeTimeout > 10000) {
								LOG.warn("The connection timeout {} for {} is not supported. The value must be greater than 0 and less than 10000 It will be set to 3000");
								handshakeTimeout = 8000;
							}
							sslHandler.setHandshakeTimeoutMillis(handshakeTimeout);
						}
						Integer closeNotifyTimeout = p.getOption("close.notify.timeout", Integer.class);
						if(closeNotifyTimeout != null) {
							if(closeNotifyTimeout < 1 || closeNotifyTimeout > 10000) {
								LOG.warn("The connection timeout {} for {} is not supported. The value must be greater than 0 and less than 10000 It will be set to 3000");
								closeNotifyTimeout = 3000;
							}
							sslHandler.setCloseNotifyTimeoutMillis(closeNotifyTimeout);
						}
						
						ch.pipeline().addLast(sslHandler);
					});
					
				case TCP :
					Integer connectionTimeout = p.getOption("connect.timeout", Integer.class);
					if(connectionTimeout == null) {
						connectionTimeout = 3000;
					} else if(connectionTimeout < 1 || connectionTimeout > 10000) {
						LOG.warn("The connection timeout {} for {} is not supported. The value must be greater than 0 and less than 10000 It will be set to 3000");
						connectionTimeout = 3000;
					}
					b.channel(NioSocketChannel.class)
							.option(ChannelOption.SO_KEEPALIVE, true)
							.option(ChannelOption.TCP_NODELAY, p.getOption("nodelay", Boolean.class))
							.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeout);
					c = c.andThen(ch -> {
				        	// Outgoing
				        	ch.pipeline().addLast(new LengthFieldPopulator());
				        	//Incoming
				        	ch.pipeline().addLast(new VersionCheckingLengthFieldBasedFrameDecoder(1 << 24, 1, 3));
						});
					break;
				default : 
					throw new IllegalArgumentException("No support for protocol " + p.getProtocol());
			}
			
			Consumer<Channel> fullPipeline = c.andThen(customizer);
			b.handler(new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel ch) throws Exception {
					fullPipeline.accept(ch);
				}
			});
			return b;
		};
	}

	public MethodCallHandlerFactory getFactoryFor(URI uri, EndpointDescription endpointDescription, 
			SerializerFactory serializerFactory, Map<Integer, String> methodMappings) {
		
		UUID serviceId =  UUID.fromString(endpointDescription.getId());
		InetSocketAddress remoteAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
		
		// We must not use computeIfAbsent as this holds a table lock in the activeChannels Map
		// This can block the close listener of another channel (doing a remove), which prevents
		// that thread from completing the connect in getChannelFor -> DEADLOCK!
		Channel channel = activeChannels.get(remoteAddress);
		
		if(channel == null) {
			Channel newChannel = ofNullable(configuredTransports.get(uri.getScheme()))
				.map(b -> getChannelFor(b,remoteAddress))
				.orElse(null);
			if(newChannel != null) {
				channel = activeChannels.putIfAbsent(remoteAddress, newChannel);
				if(channel == null) {
					channel = newChannel;
				} else {
					newChannel.close();
				}
			}
		}
	
		if(channel == null) {
			LOG.warn("Unable to create a client connection for the service {} with endpoint {}", 
					serviceId, endpointDescription);
			return null;
		}
		
		Channel toUse = channel;
		MethodCallHandlerFactoryImpl impl = activeHandlers.computeIfAbsent(serviceId, 
				k -> new MethodCallHandlerFactoryImpl(toUse, allocator, k, serializerFactory, 
						methodMappings, this, timer));
		
		channelsToHandlers.merge(channel, Collections.singleton(impl), (k,v) -> {
			Set<MethodCallHandlerFactoryImpl> related = new HashSet<>(v);
			related.add(impl);
			return related;
		});
		
		channel.closeFuture().addListener(x -> {
			Throwable failure = x.cause();
			clientWorkers.execute(() -> {
				String message = "The connection to the remote node " + toUse.remoteAddress() + " was lost";
				impl.failAll(failure == null ? new ServiceException(message, REMOTE) : 
					new ServiceException(message, REMOTE, failure));
			});
		});
		
		return impl;
	}

	private Channel getChannelFor(Function<Consumer<Channel>, Bootstrap> f, InetSocketAddress remoteAddress) {
		ChannelFuture future = null;
		try {
			future = f.apply(ch -> {
			        	ch.pipeline().addLast(clientWorkers, new ClientResponseHandler());
			        }).connect(remoteAddress);
			future.await();
			
			if(future.isSuccess()) {
				Channel channel = future.channel();
				
				channel.closeFuture().addListener(x -> {
						activeChannels.remove(channel.remoteAddress(), channel);
						channelsToHandlers.remove(channel);
					});
				
				ChannelHandler first = channel.pipeline().first();
				
				if(first instanceof SslHandler) {
					Future<Channel> handshake = ((SslHandler)first).handshakeFuture().await();
					if(!handshake.isSuccess()) {
						LOG.warn("Unable to complete the SSL Handshake with remote node " + remoteAddress, 
								handshake.cause());
						channel.close();
						return null;
					}
				}
				
				return channel;
			} else {
				LOG.error("Unable to connect to the remote address " + remoteAddress, 
						 future.cause());
				return null;
			}
			    
		} catch (InterruptedException e) {
			LOG.error("Unable to connect to the remote address" + remoteAddress, 
					e);
			if(future != null) {
				future.channel().close();
			}
			return null;
		}
	}
	
	@Sharable
	private class ClientResponseHandler extends ChannelInboundHandlerAdapter {

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object o) throws Exception {
			ByteBuf buf = (ByteBuf)o;
			try {
				byte command = buf.readByte();
				UUID serviceId = new UUID(buf.readLong(), buf.readLong());
				MethodCallHandlerFactoryImpl handler = activeHandlers.get(serviceId);
				if(handler != null) {
					handler.response(buf.readInt(), command, buf);
				}
			} finally {
				buf.release();
			}
		}
	}

	void notifyClosing(UUID serviceId, MethodCallHandlerFactoryImpl mcfhi) {
		activeHandlers.remove(serviceId, mcfhi);
		Channel channel = mcfhi.getChannel();
		Set<MethodCallHandlerFactoryImpl> remaining = channelsToHandlers.computeIfPresent(channel, (k,v) -> {
			Set<MethodCallHandlerFactoryImpl> newSet = new HashSet<>(v);
			newSet.remove(mcfhi);
			return newSet.isEmpty() ? null : newSet;
		});
		if(remaining == null) {
			activeChannels.remove(channel.remoteAddress());
			channel.close();
		}
	}

	public void close() {
		activeChannels.values().stream()
			.forEach(Channel::close);
		Future<?> ioFuture = clientIo.shutdownGracefully(250, 1000, MILLISECONDS);
		Future<?> workerFuture = clientWorkers.shutdownGracefully(250, 1000, MILLISECONDS);
		
		try {
			ioFuture.await(2000);
			workerFuture.await(2000);
		} catch (InterruptedException ie) {
		}
	}
}
