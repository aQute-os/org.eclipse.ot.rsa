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
package org.eclipse.ot.rsa.distribution.provider.client;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static org.osgi.framework.ServiceException.REMOTE;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.net.ssl.SSLEngine;

import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.eclipse.ot.rsa.distribution.provider.config.ProtocolScheme;
import org.eclipse.ot.rsa.distribution.provider.impl.ImportRegistrationImpl;
import org.eclipse.ot.rsa.distribution.provider.tcp.VersionCheckingLengthFieldBasedFrameDecoder;
import org.eclipse.ot.rsa.tls.netty.provider.tls.ParemusNettyTLS;
import org.osgi.framework.ServiceException;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;

public class ClientConnectionManager {

	private static final Logger LOG = LoggerFactory.getLogger(ClientConnectionManager.class);

	private final ConcurrentMap<InetSocketAddress, Channel> activeChannels = new ConcurrentHashMap<>();

	private final ConcurrentMap<Channel, Set<ImportRegistrationImpl>> channelsToServices = new ConcurrentHashMap<>();

	private final EventLoopGroup clientIo;

	private final ByteBufAllocator allocator;

	private final ParemusNettyTLS tls;
	private final Map<String, BiFunction<Consumer<Channel>, InetSocketAddress, ChannelFuture>> connectors;

	private final EventExecutorGroup clientWorkers;
	private final Timer timer;

	boolean closed;


	public ClientConnectionManager(TransportConfig config, ParemusNettyTLS tls, ByteBufAllocator allocator,
			EventLoopGroup clientIo, EventExecutorGroup clientWorkers, Timer timer) {
		this.tls = tls;
		this.allocator = allocator;
		this.clientWorkers = clientWorkers;
		this.timer = timer;

		this.clientIo = clientIo;

		String[] protocols = config.client_protocols();
		connectors = Arrays.stream(protocols)
				.map(ProtocolScheme::new)
				.filter(p -> {
					if(config.allow_insecure_transports() || p.getProtocol().isSecure()) {
						return true;
					}
					LOG.warn("The client transport {} is not permitted because it is insecure and insecure transports are not enabled.",
							p.getProtocol());
					return false;
				})
			.collect(toMap(p -> p.getProtocol().getUriScheme(), p -> createConnectionTo(config, p)));

		if(connectors.isEmpty() && protocols.length > 0) {
			LOG.error("There are no client transports available for this provider. Please check the configuration");
			throw new IllegalArgumentException("The transport configuration created no valid client transports");
		}
	}

	@SuppressWarnings("deprecation")
	private BiFunction<Consumer<Channel>, InetSocketAddress, ChannelFuture> createConnectionTo(TransportConfig config, ProtocolScheme p) {

		return (customizer, remoteAddress) -> {
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

					if(!tls.hasTrust() || (useClientAuth && !tls.hasCertificate())) {
						LOG.error("The secure transport {} cannot be configured as the necessary certificate configuration is unavailable. Please check the configuration of the TLS provider.",
								p.getProtocol());
						return null;
					}

					c = c.andThen(ch -> {

						SslHandler clientHandler = tls.getTLSClientHandler();

						SSLEngine engine = clientHandler.engine();

						String ciphers = p.getOption("ciphers", String.class);
						if(ciphers != null) {
							engine.setEnabledCipherSuites(ciphers.split(","));
						}

						String protocols = p.getOption("protocols", String.class);
						if(protocols != null) {
							engine.setEnabledProtocols(protocols.split(","));
						}

						engine.setWantClientAuth(useClientAuth);
						engine.setNeedClientAuth(useClientAuth);

						Integer handshakeTimeout = p.getOption("handshake.timeout", Integer.class);
						if(handshakeTimeout != null) {
							if(handshakeTimeout < 1 || handshakeTimeout > 10000) {
								LOG.warn("The connection timeout {} for {} is not supported. The value must be greater than 0 and less than 10000 It will be set to 3000");
								handshakeTimeout = 8000;
							}
							clientHandler.setHandshakeTimeoutMillis(handshakeTimeout);
						}
						Integer closeNotifyTimeout = p.getOption("close.notify.timeout", Integer.class);
						if(closeNotifyTimeout != null) {
							if(closeNotifyTimeout < 1 || closeNotifyTimeout > 10000) {
								LOG.warn("The connection timeout {} for {} is not supported. The value must be greater than 0 and less than 10000 It will be set to 3000");
								closeNotifyTimeout = 3000;
							}
							clientHandler.setCloseNotifyTimeoutMillis(closeNotifyTimeout);
						}

						ch.pipeline().addLast(clientHandler);
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
				        	//Incoming
				        	ch.pipeline().addLast(ImmediateEventExecutor.INSTANCE, new VersionCheckingLengthFieldBasedFrameDecoder());
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
			InetSocketAddress bindAddress = p.getBindAddress() == null ? new InetSocketAddress(config.server_bind_address(), 0) : p.getBindAddress();
			return b.connect(remoteAddress, bindAddress);
		};
	}

	public Channel getChannelFor(URI uri, EndpointDescription endpointDescription) {

		UUID serviceId =  UUID.fromString(endpointDescription.getId());
		InetSocketAddress remoteAddress = new InetSocketAddress(uri.getHost(), uri.getPort());

		// We must not use computeIfAbsent as this holds a table lock in the activeChannels Map
		// This can block the close listener of another channel (doing a remove), which prevents
		// that thread from completing the connect in getChannelFor -> DEADLOCK!
		boolean isOpen;
		Channel channel;
		synchronized (this) {
			isOpen = !closed;
			channel = activeChannels.get(remoteAddress);
		}

		if(channel == null && isOpen) {
			Channel newChannel = ofNullable(connectors.get(uri.getScheme()))
				.map(b -> getChannelFor(b,remoteAddress))
				.orElse(null);
			if(newChannel != null) {
				synchronized (this) {
					isOpen = !closed;
					if(isOpen) {
						channel = activeChannels.putIfAbsent(remoteAddress, newChannel);
					}
				}
				if(channel == null && isOpen) {
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

		channel.closeFuture().addListener(x -> {
			Throwable failure = x.cause();
			clientWorkers.execute(() -> {
				String message = "The connection to the remote node " + toUse.remoteAddress() + " was lost";
				failAll(toUse, failure == null ? new ServiceException(message, REMOTE) :
					new ServiceException(message, REMOTE, failure));
			});
		});

		return toUse;
	}

	private Channel getChannelFor(BiFunction<Consumer<Channel>, InetSocketAddress, ChannelFuture> f, InetSocketAddress remoteAddress) {
		ChannelFuture future = null;
		try {
			future = f.apply(ch -> {
				ClientResponseHandler clientResponseHandler = new ClientResponseHandler(this, timer);
						ch.pipeline().addLast(ImmediateEventExecutor.INSTANCE, clientResponseHandler);
						ch.pipeline().addLast(ImmediateEventExecutor.INSTANCE, new ClientRequestSerializer(clientResponseHandler));
			        }, remoteAddress);
			future.await();

			if(future.isSuccess()) {
				Channel channel = future.channel();

				channel.closeFuture().addListener(x -> {
						activeChannels.remove(remoteAddress, channel);
						ofNullable(channelsToServices.remove(channel))
							.ifPresent(s -> s.stream().forEach(ir -> {
									Throwable failure = x.cause();
									String message = "The connection to the remote node " + remoteAddress + " was lost";
									ir.asyncFail(failure == null ? new ServiceException(message, REMOTE) :
														new ServiceException(message, REMOTE, failure));
								}));
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

	private void failAll(Channel channel, Throwable t) {
		synchronized (this) {
			if(closed) return;
		}

		ofNullable(channelsToServices.get(channel))
			.ifPresent(s -> s.stream()
					.forEach(ir -> ir.asyncFail(t)));
	}

	public void addImportRegistration(ImportRegistrationImpl ir) {
		String failure = null;
		Channel channel = ir.getChannel();
		if(channel == null) {
			failure = "The import has no associated channel";
		} else {
			synchronized (this) {
				if (!channel.isOpen()) {
					failure = "The import's channel is already closed";
				} else if (closed) {
					failure = "The handler for the import has been asynchronously closed";
				} else {
					channelsToServices.compute(channel, (k,v) -> {
						Set<ImportRegistrationImpl> newSet = v == null ? new HashSet<>() : new HashSet<>(v);
						newSet.add(ir);
						return newSet;
					});
				}
			}
		}
		if(failure != null) {
			throw new ServiceException(failure, ServiceException.REMOTE);
		}
	}

	public void notifyClosing(ImportRegistrationImpl ir) {
		Channel channel = ir.getChannel();

		if(channel != null) {
			boolean closeChannel = false;
			synchronized (this) {
				Set<ImportRegistrationImpl> remaining = channelsToServices.computeIfPresent(channel, (k,v) -> {
					Set<ImportRegistrationImpl> newSet = new HashSet<>(v);
					newSet.remove(ir);
					return newSet.isEmpty() ? null : newSet;
				});
				if(remaining == null) {
					SocketAddress remoteAddress = channel.remoteAddress();
					// This will be null if the channel is already closed, if so there is nothing to do
					if(remoteAddress != null) {
						activeChannels.remove(remoteAddress);
						closeChannel = true;
					}
				}
			}
			if(closeChannel) {
				channel.close();
			}
		}

	}

	public void close() {
		synchronized (this) {
			if(closed) return;
			closed = true;
		}

		Throwable closing = new ServiceException("The RSA client is closing.", ServiceException.REMOTE);
		channelsToServices.values().stream()
			.flatMap(Set::stream)
			.forEach(ir -> ir.asyncFail(closing));

		activeChannels.values().stream()
			.forEach(Channel::close);
	}

	public void notifyFailedService(Channel channel, UUID serviceId, ServiceException se) {
		synchronized (this) {
			if(closed) return;
		}
		clientWorkers.execute(() ->
			ofNullable(channelsToServices.get(channel))
				.map(Set::stream)
				.flatMap(s -> s.filter(ir -> serviceId.equals(ir.getId())).findFirst())
				.ifPresent(ir -> ir.asyncFail(se)));
	}
}
