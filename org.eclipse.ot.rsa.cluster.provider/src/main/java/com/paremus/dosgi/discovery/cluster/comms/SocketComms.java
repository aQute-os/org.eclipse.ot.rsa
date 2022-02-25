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
package com.paremus.dosgi.discovery.cluster.comms;

import static com.paremus.dosgi.discovery.cluster.comms.MessageType.ACKNOWLEDGMENT;
import static com.paremus.dosgi.discovery.cluster.comms.MessageType.ANNOUNCEMENT;
import static com.paremus.dosgi.discovery.cluster.comms.MessageType.REMINDER;
import static com.paremus.dosgi.discovery.cluster.comms.MessageType.REQUEST_REANNOUNCEMENT;
import static com.paremus.dosgi.discovery.cluster.comms.MessageType.REVOCATION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.ot.rsa.cluster.api.ClusterNetworkInformation;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.discovery.cluster.impl.Config;
import com.paremus.dosgi.discovery.cluster.local.LocalDiscoveryListener;
import com.paremus.dosgi.discovery.cluster.remote.RemoteDiscoveryNotifier;
import com.paremus.netty.tls.MultiplexingDTLSHandler;
import com.paremus.netty.tls.ParemusNettyTLS;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseNotifier;

public class SocketComms {

	private static final Logger logger = LoggerFactory.getLogger(SocketComms.class);

	private final AtomicReference<Channel> channel = new AtomicReference<>();

	private final AtomicBoolean open = new AtomicBoolean(true);

	private final UUID localId;

	private final ConcurrentMap<SocketAddress, UUID> socketToId = new ConcurrentHashMap<>();

	private ScheduledFuture<?> resendMessages;

	private final Bootstrap bootstrap;

	private final EventExecutorGroup worker;

	public SocketComms(UUID localId, NioEventLoopGroup eventLoop,
			ParemusNettyTLS ssl, LocalDiscoveryListener localDiscovery,
			RemoteDiscoveryNotifier remoteNotifier, EventExecutorGroup worker) {
		this.localId = localId;
		this.worker = worker;

		try {
			bootstrap = new Bootstrap().channel(NioDatagramChannel.class)
				.group(eventLoop)
				.handler(new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel ch) throws Exception {
						ChannelHandler dtlsHandler = ssl.getDTLSHandler();
						if(dtlsHandler != null) {
							ch.pipeline().addLast(dtlsHandler);
						}
						ch.pipeline().addLast(new DiscoveryHandler(localId, localDiscovery, remoteNotifier));
					}
				});
		} catch (Exception e) {
			throw new RuntimeException("Failed to set up the Discovery communications", e);
		}
	}

	public synchronized void bind(ClusterNetworkInformation info, Config config) {
		if(!open.get()) {
			throw new IllegalStateException("Communications for DOSGi discovery in cluster " + info + " are closed");
		}
		if(channel.get() != null) {
			return;
		}
		try {
			channel.set(bootstrap.bind(info.getBindAddress(), config.port()).sync().channel());
			resendMessages = worker.scheduleWithFixedDelay(this::resendNonAckedMessages, 0, 500, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			logger.error("Unable to start the discovery commmunications layer.", e);
		}
	}

	private void resendNonAckedMessages() {
		Channel c = channel.get();
		if(c != null) {
			c.pipeline().fireUserEventTriggered(new ResendUnacknowlegedNotifications());
		}
	}

	public synchronized Future<?> destroy() {
		Future<Void> toReturn;
		Channel c = channel.get();
		if(open.compareAndSet(true, false)) {
			if(c != null) {
				c.close();
			}
			if(!worker.isShuttingDown() && resendMessages != null && !resendMessages.isDone()) {
				resendMessages.cancel(true);
			}
		}

		if(c != null) {
			ChannelFuture closeFuture = c.closeFuture();
			if(closeFuture.isDone()) {
				toReturn = ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
			} else {
				Promise<Void> p = ImmediateEventExecutor.INSTANCE.newPromise();
				closeFuture.addListener(new PromiseNotifier<>(p));
				toReturn = p;
			}
		} else {
			toReturn = ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
		}
		return toReturn;
	}

	public void publishEndpoint(EndpointDescription ed, Integer state, UUID remoteNodeId, InetSocketAddress address) {

		Channel c = channel.get();
		if(c == null) {
			logger.debug("Endpoint publication will not occur as this comms stack is not yet bound");
			return;
		}

		ByteBuf buf = c.alloc().buffer(65535);

		try {
			buf.writeByte(2);
			buf.writeByte(ANNOUNCEMENT.code());
			EndpointSerializer.serialize(ed, buf);
			buf.writeInt(state);
		} catch (Exception e) {
			logger.error("Unable to announce an endpoint with properties " + ed.getProperties(), e);
		}

		if(buf.readableBytes() > 65535) {
			logger.error("The serialized endpoint with properties {} is too large to send ({} bytes).", ed.getProperties(), buf.readableBytes());
			return;
		}
		String endpointId = ed.getId();

		DatagramPacket message = new DatagramPacket(buf, address);
		c.writeAndFlush(new PendingAck(message, state, remoteNodeId, endpointId), c.voidPromise());
		if(logger.isDebugEnabled()) {
			logger.debug("Outgoing endpoint publication id: {}, state {} from {} to {}",
					new Object[] {endpointId, state, localId, remoteNodeId});
		}
	}

	public void revokeEndpoint(String endpointId, Integer state, UUID remoteNodeId, InetSocketAddress address) {

		Channel c = channel.get();
		if(c == null) {
			logger.debug("Endpoint revocation will not occur as this comms stack is not yet bound");
			return;
		}

		int size = 1 + 1 + 4 + 2 + ByteBufUtil.utf8MaxBytes(endpointId);

		ByteBuf buf = c.alloc().buffer(size);
		try {
			buf.writeByte(2);
			buf.writeByte(REVOCATION.code());
			int writerIndex = buf.writerIndex();
			buf.writerIndex(writerIndex + 2);
			buf.setShort(writerIndex, ByteBufUtil.writeUtf8(buf, endpointId));
			buf.writeInt(state);

			DatagramPacket message = new DatagramPacket(buf, address);
			c.writeAndFlush(new PendingAck(message, state, remoteNodeId, endpointId), c.voidPromise());
			if(logger.isDebugEnabled()) {
				logger.debug("Outgoing endpoint revocation id: {}, state {} from {} to {}",
						new Object[] {endpointId, state, localId, remoteNodeId});
			}
		} catch (Exception e) {
			logger.error("Unable to revoke endpoint " + endpointId, e);
		}
	}

	public boolean isBound() {
		return channel.get() != null;
	}

	public int getUdpPort() {
		Channel c = channel.get();
		if(c == null) {
			logger.debug("Endpoint publication will not occur as this comms stack is not yet bound");
			return -1;
		}
		return ((InetSocketAddress)c.localAddress()).getPort();
	}

	public void newDiscoveryEndpoint(UUID remoteId, SocketAddress address) {
		socketToId.put(address, remoteId);
	}

	public void stopCalling(UUID id, SocketAddress socketAddress) {
		Channel c = channel.get();
		if(c == null) {
			return;
		}

		c.pipeline().fireUserEventTriggered(new DepartingMemberEvent(id));
		socketToId.remove(socketAddress);

		MultiplexingDTLSHandler handler = c.pipeline().get(MultiplexingDTLSHandler.class);
		if(handler != null) {
			handler.disconnect(socketAddress);
		}
	}

	public void sendReminder(Collection<String> published, int counter, UUID remoteNodeId,
			InetSocketAddress address) {
		Channel c = channel.get();
		if(c == null) {
			logger.debug("Endpoint reminder will not be sent as this comms stack is not yet bound");
			return;
		}

		int size = published.stream()
			.mapToInt(ByteBufUtil::utf8MaxBytes)
			.map(i -> i + 2)
			.sum() + 1 + 1 + 16 + 4 + 2 ;

		ByteBuf buf = c.alloc().buffer(size);
		try {
			buf.writeByte(2);
			buf.writeByte(REMINDER.code());
			buf.writeLong(localId.getMostSignificantBits());
			buf.writeLong(localId.getLeastSignificantBits());
			buf.writeInt(counter);

			buf.writeShort(published.size());

			for(String s : published) {
				int writerIndex = buf.writerIndex();
				buf.writerIndex(writerIndex + 2);
				buf.setShort(writerIndex, ByteBufUtil.writeUtf8(buf, s));
			}
		} catch (Exception e) {
			logger.error("Unable to build the endpoint reminder announcement", e);
			return;
		}

		DatagramPacket message = new DatagramPacket(buf, address);
		c.writeAndFlush(new PendingAck(message, counter, remoteNodeId, remoteNodeId.toString()), c.voidPromise());

		if(logger.isDebugEnabled()) {
			logger.debug("Reminder from {} to {}", new Object[] {localId, remoteNodeId});
		}
	}

	private static class DepartingMemberEvent {
		private final UUID departing;

		public DepartingMemberEvent(UUID departing) {
			this.departing = departing;
		}
	}

	private static class ResendUnacknowlegedNotifications {}

	private static class PendingAck {
		private final DatagramPacket message;
		private final Integer state;
		private final UUID targetId;
		private final String endpointId;
		private long lastSentTime = NANOSECONDS.toMillis(System.nanoTime());

		public PendingAck(DatagramPacket message, Integer state, UUID targetId, String endpointId) {
			this.message = message;
			this.state = state;
			this.targetId = targetId;
			this.endpointId = endpointId;
		}

		synchronized boolean shouldResend(long now) {
			if(now - lastSentTime > 1000) {
				if(logger.isDebugEnabled()) {
					logger.debug("No acknowledgement received, resending a discovery message for endpoint {} to {} on {}",
							new Object[] {endpointId, targetId, message.sender()});
				}
				lastSentTime = now;
				return true;
			}
			return false;
		}
	}

	public static class DiscoveryHandler extends ChannelDuplexHandler {

		private final UUID localId;

		private final LocalDiscoveryListener local;

		private final RemoteDiscoveryNotifier listener;

		private final UUID reannouncementId = UUID.randomUUID();

		private final ConcurrentMap<UUID, Map<String, PendingAck>> pendingAcknowledgments = new ConcurrentHashMap<>();

		public DiscoveryHandler(UUID localId, LocalDiscoveryListener local, RemoteDiscoveryNotifier listener) {
			this.localId = localId;
			this.local = local;
			this.listener = listener;
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {

			PendingAck ack = (PendingAck) msg;

			DatagramPacket dp = ack.message.retainedDuplicate();

			pendingAcknowledgments.compute(ack.targetId, (k,v) -> {
				Map<String, PendingAck> computed = (v == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(v);
				computed.compute(ack.endpointId, (k2, v2) -> {
						if(v2 == null) {
							return ack;
						} else if (ack.state > v2.state) {
							v2.message.release();
							return ack;
						} else {
							ack.message.release();
							return v2;
						}
					});
				return computed;
			});

			ctx.write(dp, promise);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			DatagramPacket dp = (DatagramPacket) msg;
			try {
				ByteBuf data = dp.content();
				InetSocketAddress socketAddress = dp.sender();

				short version = data.readUnsignedByte();
				if(version != 2) {
					logger.warn("Received a discovery message with an invalid version {}", version);
					return;
				}

				MessageType messageType = MessageType.valueOf(data.readUnsignedByte());
				switch(messageType) {
					case ANNOUNCEMENT:
						announcement(ctx, socketAddress, data);
						break;
					case REVOCATION:
						revocation(ctx, socketAddress, data);
						break;
					case ACKNOWLEDGMENT:
						acknowledgement(ctx, socketAddress, data);
						break;
					case REMINDER:
						reminder(ctx, socketAddress, data);
						break;
					case REQUEST_REANNOUNCEMENT:
						reannouncementRequest(ctx, socketAddress, data);
						break;
					default:
						throw new UnsupportedEncodingException("The discovery message type " + messageType + " is not known");
				}

			} finally {
				dp.release();
			}
		}

		private void announcement(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf input) {
			EndpointDescription ed = EndpointSerializer.deserializeEndpoint(input);
			int state = input.readInt();

			if(logger.isDebugEnabled()) {
				logger.debug("Received endpoint announcement {} in {} from {}",
						new Object[]{ed.getId(), localId, ed.getFrameworkUUID()});
			}

			acknowledge(ctx, ed.getId(), state, sender);
			listener.announcementEvent(ed, state);
		}


		private void revocation(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf input) {
			String endpointId = input.readCharSequence(input.readUnsignedShort(), UTF_8).toString();
			int state = input.readInt();

			if(logger.isDebugEnabled()) {
				logger.debug("Received endpoint revocation {} state {} in {}",
						new Object[]{endpointId, state, localId});
			}

			acknowledge(ctx, endpointId, state, sender);
			listener.revocationEvent(endpointId, state);
		}

		private void acknowledgement(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf input) {
			UUID remote = new UUID(input.readLong(), input.readLong());
			String endpointId = input.readCharSequence(input.readUnsignedShort(), UTF_8).toString();
			Integer stateBeingAcked = input.readInt();

			if(logger.isDebugEnabled()) {
				logger.debug("Received acknowledgement announcement {} in {} from {}",
						new Object[]{ endpointId, localId, remote});
			}

			pendingAcknowledgments.computeIfPresent(remote, (k, v) -> {
					ConcurrentMap<String, PendingAck> toReturn = v.entrySet().stream()
						.filter(e -> !endpointId.equals(e.getKey()) || !stateBeingAcked.equals(e.getValue().state))
						.collect(Collectors.toConcurrentMap(Entry::getKey, Entry::getValue));
					return toReturn.isEmpty() ? null : toReturn;
				});
		}

		private void reminder(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf input) {
			UUID remote = new UUID(input.readLong(), input.readLong());
			int counter = input.readInt();

			if(logger.isDebugEnabled()) {
				logger.debug("Received reminder announcement in {} from {}",
						new Object[]{localId, remote});
			}

			acknowledge(ctx, localId.toString(), counter, sender);

			Map<String, Integer> known = listener.getEndpointsFor(remote);
			int size = input.readUnsignedShort();
			Collection<String> unknownIds = new ArrayList<>();

			for(int i = 0; i < size; i ++) {
				String endpointId = input.readCharSequence(input.readUnsignedShort(), UTF_8).toString();
				if(known.remove(endpointId) == null) {
					unknownIds.add(endpointId);
				}
			}
			if(!unknownIds.isEmpty()) {
				requestReAnnounce(ctx, unknownIds, counter, remote, sender);
			}
			known.forEach((id, state) -> listener.revocationEvent(id, state));
		}

		private void reannouncementRequest(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf input) {
			UUID ack = new UUID(input.readLong(), input.readLong());
			UUID remote = new UUID(input.readLong(), input.readLong());
			int counter = input.readInt();

			if(logger.isDebugEnabled()) {
				logger.debug("Received acknowledgement for announcement {} with state {} from {}",
						new Object[]{ack, counter, remote});
			}

			acknowledge(ctx, ack.toString(), counter, sender);

			int size = input.readUnsignedShort();

			for(int i = 0; i < size; i ++) {
				String endpointId = input.readCharSequence(input.readUnsignedShort(), UTF_8).toString();
				local.republish(endpointId, remote);
			}
		}

		private void acknowledge(ChannelHandlerContext ctx, String endpointId, int state,
				InetSocketAddress socketAddress) {

			ByteBuf buf = ctx.alloc().buffer(ByteBufUtil.utf8MaxBytes(endpointId) + 2 + 1 + 1 + 16 + 4);
			try {
				buf.writeByte(2);
				buf.writeByte(ACKNOWLEDGMENT.code());
				buf.writeLong(localId.getMostSignificantBits());
				buf.writeLong(localId.getLeastSignificantBits());

				int writerIndex = buf.writerIndex();
				buf.writerIndex(writerIndex + 2);
				buf.setShort(writerIndex, ByteBufUtil.writeUtf8(buf, endpointId));

				buf.writeInt(state);

			} catch (Exception e) {
				logger.error("Unable to acknowledge receipt of an endpoint announcement", e);
				return;
			}

			if(logger.isDebugEnabled()) {
				logger.debug("Acknowledging message sent to {} for endpoint {} at {}",
						new Object[] {localId, endpointId, state});
			}
			// DO NOT send as a pending ack!
			ctx.writeAndFlush(new DatagramPacket(buf, socketAddress), ctx.voidPromise());
		}

		private void requestReAnnounce(ChannelHandlerContext ctx, Collection<String> unknownIds,
				Integer counter, UUID remote, InetSocketAddress sender) {

			if(logger.isDebugEnabled()) {
				logger.debug("Requesting reannouncement of the endpoints {} from the node {} at {}",
						new Object[] {unknownIds, remote, sender});
			}

			ByteBuf buf = ctx.alloc().buffer(1 + 1 + 16 + 16 + 4 + 2 + unknownIds.stream()
				.mapToInt(ByteBufUtil::utf8MaxBytes)
				.map(i -> i + 2)
				.sum());

			try {
				buf.writeByte(2);
				buf.writeByte(REQUEST_REANNOUNCEMENT.code());
				buf.writeLong(reannouncementId.getMostSignificantBits());
				buf.writeLong(reannouncementId.getLeastSignificantBits());
				buf.writeLong(localId.getMostSignificantBits());
				buf.writeLong(localId.getLeastSignificantBits());
				buf.writeInt(counter);

				buf.writeShort(unknownIds.size());

				for(String s : unknownIds) {
					int writerIndex = buf.writerIndex();
					buf.writerIndex(writerIndex + 2);
					buf.setShort(writerIndex, ByteBufUtil.writeUtf8(buf, s));
				}
			} catch (Exception e) {
				logger.error("Unable to build the endpoint reannouncement request", e);
				return;
			}

			PendingAck msg = new PendingAck(new DatagramPacket(buf, sender), counter, remote, reannouncementId.toString());
			write(ctx, msg, ctx.voidPromise());
			ctx.flush();

			if(logger.isDebugEnabled()) {
				logger.debug("Requested reannouncement id {} from {} at {}",
						new Object[] {reannouncementId, remote, sender});
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if(evt instanceof ResendUnacknowlegedNotifications) {
				long now = NANOSECONDS.toMillis(System.nanoTime());
				pendingAcknowledgments.values().stream()
					.forEach(v -> v.values().stream()
							.filter(p -> p.shouldResend(now))
							.forEach(p -> ctx.writeAndFlush(p.message.retainedDuplicate(), ctx.voidPromise())));

			} else if (evt instanceof DepartingMemberEvent) {
				DepartingMemberEvent dme = (DepartingMemberEvent) evt;

				Map<String, PendingAck> acks = pendingAcknowledgments.remove(dme.departing);
				if(acks != null) {
					acks.values().forEach(p -> p.message.release());
				}
			}
			super.userEventTriggered(ctx, evt);
		}

	}
}
