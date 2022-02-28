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
package org.eclipse.ot.rsa.cluster.gossip.v1.messages;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.InetSocketAddress;
import java.util.Objects;

import org.eclipse.ot.rsa.cluster.gossip.GossipMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;


public abstract class AbstractGossipMessage implements GossipMessage {

	private final String clusterName;

	private Snapshot update;

	public AbstractGossipMessage(String clusterName, Snapshot snapshot) {
		Objects.nonNull(snapshot);
		this.clusterName = clusterName;
		this.update = snapshot;
	}

	public AbstractGossipMessage(final ByteBuf input) {
		try {
			clusterName = input.readCharSequence(input.readUnsignedShort(), UTF_8).toString();
			update = new Snapshot(input);
		} catch (Exception e) {
			throw new RuntimeException("Failed to read message", e);
		}
	}

	@Override
	public void writeOut(ByteBuf output) {
		try {
			writeUTF8(output, clusterName);
			update.writeOut(output);
		} catch (Exception e) {
			throw new RuntimeException("Failed to write message", e);
		}
	}

	public String getClusterName() {
		return clusterName;
	}

	public Snapshot getUpdate(InetSocketAddress sentFrom) {
		return new Snapshot(update, sentFrom);
	}

	@Override
	public abstract MessageType getType();

	static void writeUTF8(ByteBuf buf, CharSequence charSequence) {
		int writerIndex = buf.writerIndex();
		buf.writerIndex(writerIndex + 2);
		int written = buf.writeCharSequence(charSequence, UTF_8);
		buf.setShort(writerIndex, written);
	}

	@Override
	public int estimateSize() {
		return ByteBufUtil.utf8MaxBytes(clusterName) + update.guessSize();
	}
}
