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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.Gossip;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.PromiseCombiner;

public abstract class AbstractTCPReplicator extends ChannelInboundHandlerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(AbstractTCPReplicator.class);
	
	protected final UUID localId;
	
	protected final Gossip gossip;

	protected final Collection<Snapshot> snapshotHeaders;

	private final ChannelPromise syncCompletionPromise;
	
	public AbstractTCPReplicator(Channel channel, UUID localId, Gossip gossip, Collection<Snapshot> snapshotHeaders) {
		
		syncCompletionPromise = channel.newPromise();
		
		syncCompletionPromise.addListener(f -> channel.close());
		
		this.localId = localId;
		this.gossip = gossip;
		this.snapshotHeaders = snapshotHeaders;
	}

	public ChannelFuture getSyncCompletionFuture() {
		return syncCompletionPromise;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		syncCompletionPromise.tryFailure(cause);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		syncCompletionPromise.tryFailure(new IllegalStateException("Handler removed before completion"));
	}

	protected ByteBuf getHeader(ChannelHandlerContext ctx, long exchangeId) {
		ByteBuf buffer = ctx.alloc().buffer(29);
		
		buffer.writeByte(2);
		buffer.writeLong(exchangeId);
		buffer.writeLong(localId.getMostSignificantBits());
		buffer.writeLong(localId.getLeastSignificantBits());
		buffer.writeInt(snapshotHeaders.size());
		return buffer;
	}

	protected ChannelPromise writeSnapshots(ChannelHandlerContext ctx, Collection<Snapshot> snapshots) {
		
		@SuppressWarnings("deprecation")
		PromiseCombiner pc = new PromiseCombiner();
		for(Snapshot s : snapshots) {
			ByteBuf buffer = ctx.alloc().buffer(s.guessSize() + 2);
			int i = buffer.writerIndex();
			buffer.writerIndex(i + 2);

			s.writeOut(buffer);
			
			buffer.setShort(i, buffer.readableBytes());
			pc.add(ctx.write(buffer));
		}
		ctx.flush();
		ChannelPromise toReturn = ctx.newPromise();
		pc.finish(toReturn);
		return toReturn;
	}

	protected static enum REPLICATION_STATE {HEADER, SNAPSHOTS_LITE, SNAPSHOTS_FULL}
	
	private REPLICATION_STATE state = REPLICATION_STATE.HEADER;
	
	private ByteBuf unread;
	
	private int snapshotsExpected;
	
	private Map<UUID, Snapshot> receivedSnapshots = new HashMap<>();

	private ChannelFuture outputShutdown;
	
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		
		if(unread == null) {
			unread = (ByteBuf) msg;
		} else {
			unread = Unpooled.wrappedBuffer(unread, (ByteBuf) msg);
		}
		
		processRead(ctx);
		
		if(unread.readableBytes() == 0) {
			unread.release();
			unread = null;
		} else {
			unread.discardSomeReadBytes();
		}
	}
	
	private void processRead(ChannelHandlerContext ctx) {
		switch(state) {
			case HEADER: 
				handleHeader(ctx);
				break;
			case SNAPSHOTS_LITE:
				handleLiteSnapshots(ctx);
				break;
			case SNAPSHOTS_FULL:
				handleFullSnapshots(ctx);
				break;
		}
	}
	
	private void handleHeader(ChannelHandlerContext ctx) {
		
		if(unread.getByte(unread.readerIndex()) != 2) {
			logger.warn("Received an invalid gossip synchronization exchange from {}", ctx.channel().remoteAddress());
			ctx.close();
			unread.release();
			unread = null;
			return;
		}
		
		if(unread.readableBytes() < 29) {
			return;
		}
		// Skip the version byte that we have already validated
		unread.skipBytes(1);
		int snapshotsToReceive = validateIncomingExchangeStart(ctx, unread);
		
		if(snapshotsToReceive < 0) {
			logger.warn("Unable to synchronize cluster data with {}", ctx.channel().remoteAddress());
			syncCompletionPromise.tryFailure(new IllegalArgumentException("Failed to validate the exchange header"));
			ctx.close();
			return;
		} else {
			snapshotsExpected = snapshotsToReceive;
			state = REPLICATION_STATE.SNAPSHOTS_LITE;
			processRead(ctx);
		}
	}
	
	protected int validateIncomingExchangeStart(ChannelHandlerContext ctx, ByteBuf input) {
		long incomingExchangeId = input.readLong();
		UUID incomingId = new UUID(input.readLong(), input.readLong());
		int snapshotLength = input.readInt();
		
		return validateExchangeHeader(ctx, incomingExchangeId, incomingId, snapshotLength);
	}

	protected abstract int validateExchangeHeader(ChannelHandlerContext ctx, long incomingExchangeId, UUID incomingId,
			int incomingSnapshotLength);

	private void handleLiteSnapshots(ChannelHandlerContext ctx) {
		while(snapshotsExpected > 0) {
			if(readSnapshot()) {
				unread.discardSomeReadBytes();
			} else {
				return;
			}
		}
		
		Collection<Snapshot> fullSnapshotsToSend = getFullSnapshotsToSend(snapshotHeaders, receivedSnapshots);
		ctx.write(Unpooled.copyInt(fullSnapshotsToSend.size()));
		writeSnapshots(ctx, fullSnapshotsToSend)
			.addListener(f -> outputShutdown = ((SocketChannel)ctx.channel()).shutdownOutput());
		
		state = REPLICATION_STATE.SNAPSHOTS_FULL;
		snapshotsExpected = -1;
		processRead(ctx);
	}

	private void handleFullSnapshots(ChannelHandlerContext ctx) {
		if(snapshotsExpected < 0) {
			if(unread.readableBytes() < 4) {
				return;
			} else {
				snapshotsExpected = unread.readInt();
			}
		}
		
		while(snapshotsExpected > 0) {
			if(!readSnapshot()) {
				return;
			}
		}
		
		@SuppressWarnings("deprecation")
		PromiseCombiner pc = new PromiseCombiner();
		pc.add(outputShutdown);
		pc.add(((SocketChannel)ctx.channel()).shutdownInput());
		
		pc.finish(syncCompletionPromise);
	}

	private boolean readSnapshot() {
		if(unread.readableBytes() < 2) {
			return false;
		}
		int length = unread.getUnsignedShort(unread.readerIndex());
		if(unread.readableBytes() < length) {
			return false;
		} else {
			ByteBuf snapshot = unread.readSlice(length);
			snapshotsExpected--;
			try {
				snapshot.skipBytes(2);
				Snapshot s = new Snapshot(snapshot);
				receivedSnapshots.put(s.getId(), s);
				gossip.merge(s);
			} catch (Exception e) {
				logger.warn("Failed to merge a snapshot update", e);
			}
			return true;
		}
	}
	
	private Collection<Snapshot> getFullSnapshotsToSend(Collection<Snapshot> headers, 
			Map<UUID, Snapshot> remoteSnapshots) {
		return headers.stream()
						.filter(snapshot -> {
							Snapshot received = remoteSnapshots.get(snapshot.getId());
							return received == null 
									|| shouldSendFullShapshot(snapshot, received);
						})
						.map(snapshot -> gossip.getInfoFor(snapshot.getId()).toSnapshot())
						.collect(Collectors.toSet());
	}
	
	private boolean shouldSendFullShapshot(Snapshot localHeader, Snapshot remoteHeader) {
		int delta = localHeader.getStateSequenceNumber() - remoteHeader.getStateSequenceNumber();
		
		if(delta > 0) {
			return true;
		} else if (delta == 0 &&
				(localHeader.getSnapshotTimestamp() - remoteHeader.getSnapshotTimestamp()) < 0) {
			MemberInfo info = gossip.getInfoFor(localHeader.getId());
			if(info != null) {
				info.update(remoteHeader);
			}
		}
		
		return false;
	}
}
