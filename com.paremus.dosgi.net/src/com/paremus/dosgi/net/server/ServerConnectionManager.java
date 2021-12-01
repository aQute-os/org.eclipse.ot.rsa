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
package com.paremus.dosgi.net.server;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.config.Config;
import com.paremus.dosgi.net.config.ProtocolScheme;
import com.paremus.dosgi.net.tcp.LengthFieldPopulator;
import com.paremus.dosgi.net.tcp.VersionCheckingLengthFieldBasedFrameDecoder;
import com.paremus.net.encode.EncodingSchemeFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.FastThreadLocalThread;

public class ServerConnectionManager {

	private static final Logger LOG = LoggerFactory.getLogger(ServerConnectionManager.class);
	
	private final EventLoopGroup serverIo;
	private final AtomicInteger ioThreadId = new AtomicInteger(1);
	
	private final ByteBufAllocator allocator;

	private final EncodingSchemeFactory esf;
	private final List<RemotingProviderImpl> configuredTransports;

	public ServerConnectionManager(Config config, EncodingSchemeFactory esf, ByteBufAllocator allocator) {
		this.esf = esf;
		this.allocator = allocator;
		serverIo = new NioEventLoopGroup(config.server_io_threads(), r -> {
				Thread thread = new FastThreadLocalThread(r, 
						"Paremus RSA distribution server IO: " + ioThreadId.getAndIncrement());
				thread.setDaemon(true);
				return thread;
			});
				
		InetAddress defaultBindAddress;
		try {
			defaultBindAddress = InetAddress.getByName(config.server_bind_address());
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("The default bind address " + 
					config.server_bind_address() + " is not valid.");
		} 
		
		configuredTransports = config.server_protocols().stream()
			.filter(p -> {
				if(config.allow_insecure_transports() || p.getProtocol().isSecure()) {
					return true;
				}
				LOG.warn("The server transport {} is not permitted because it is insecure and insecure transports are not enabled.",
						p.getProtocol());
				return false;
			})
			.map(p -> createProviderFor(p, defaultBindAddress))
			.filter(rp -> rp != null)
			.collect(toList());
		
		if(configuredTransports.isEmpty()) {
			LOG.error("There are no server transports available for this provider. Please check the configuration {}", 
					config.server_protocols());
		}
	}

	private RemotingProviderImpl createProviderFor(ProtocolScheme p, InetAddress defaultBindAddress) {
		
		ServerBootstrap b = new ServerBootstrap();
		
		b.group(serverIo)
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
						sslContext = SslContextBuilder.forServer(sslKeyManagerFactory)
							.trustManager(sslTrustManagerFactory)
							.ciphers(asList(ciphers.split(",")))
							.build();
					} catch (Exception e) {
						throw new RuntimeException("Unable to create the SSL Engine", e);
					}
					SSLEngine engine = sslContext.newEngine(allocator);
					engine.setWantClientAuth(useClientAuth);
					engine.setNeedClientAuth(useClientAuth);
					engine.setEnabledProtocols(protocols.split(","));
					ch.pipeline().addLast(new SslHandler(engine));
							
				});
			case TCP :
				b.channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 128)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.childOption(ChannelOption.TCP_NODELAY, p.getOption("nodelay", Boolean.class));
				
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
		
		ServerRequestHandler srh = new ServerRequestHandler(p);
		ChannelGroup group = new DefaultChannelGroup(serverIo.next());
		
		Consumer<Channel> fullPipeline = c.andThen(ch -> ch.pipeline().addLast(srh));
		b.childHandler(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) throws Exception {
				fullPipeline.accept(ch);
				group.add(ch);
			}
		});
		
		try {
			Channel server = b.bind(p.getBindAddress() == null ? defaultBindAddress : p.getBindAddress(), 
					p.getPort()).sync().channel();
			group.add(server);
			return new RemotingProviderImpl(p.getProtocol().isSecure(), p.getProtocol().getUriScheme(), srh, 
					server, group);
		} catch (InterruptedException ie) {
			LOG.warn("Interruped while configuring the transport {} with configuration {}", 
					p.getProtocol(), p.getConfigurationString());
			throw new RuntimeException(ie);
		}
	}

	public List<? extends RemotingProvider> getConfiguredProviders() {
		return configuredTransports;
	}

	public void close() {
		configuredTransports.stream()
			.forEach(RemotingProviderImpl::close);
		try {
			serverIo.shutdownGracefully(250, 1000, MILLISECONDS).await(2000);
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
	}
}
