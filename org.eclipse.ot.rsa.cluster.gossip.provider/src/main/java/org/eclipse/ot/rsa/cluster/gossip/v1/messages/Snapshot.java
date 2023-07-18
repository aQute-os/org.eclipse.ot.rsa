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
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

public class Snapshot {

	private static final Logger			logger	= LoggerFactory.getLogger(Snapshot.class);

	/**
	 * The id of this node
	 */
	private final UUID					id;

	/**
	 * The address of this node
	 */
	private final InetSocketAddress		address;

	/**
	 * The TCP port of this node
	 */
	private final int					tcpPort;

	/**
	 * The current state counter for the node represented by this snapshot
	 */
	private final short					stateSequenceNumber;

	/**
	 * The low three bytes of the system clock when this snapshot was taken
	 */
	private final int					snapshotTimestamp;

	/**
	 * What is the type of the message from this node?
	 */
	private final SnapshotType			snapshotType;

	/**
	 * What is the payload - a zero length array for a heartbeat message.
	 */
	private final Map<String, byte[]>	data;

	private final int					hopsToLive;

	public Snapshot(UUID id, int tcpPort, short stateSequenceNumber, SnapshotType type, Map<String, byte[]> data,
		int hopsToLive) {
		this.id = id;
		this.address = null;
		this.tcpPort = tcpPort;
		this.stateSequenceNumber = stateSequenceNumber;
		this.snapshotTimestamp = (int) ((0xFFFFFF & NANOSECONDS.toMillis(System.nanoTime())) << 8);
		this.snapshotType = type;
		this.data = data == null ? emptyMap() : data;
		this.hopsToLive = Math.max(0, Math.min(255, hopsToLive));
	}

	public Snapshot(Snapshot s, InetSocketAddress socketAddress) {
		this.id = s.id;
		this.address = socketAddress;
		this.tcpPort = s.tcpPort;
		this.stateSequenceNumber = s.stateSequenceNumber;
		this.snapshotTimestamp = s.snapshotTimestamp;
		this.snapshotType = s.snapshotType;
		this.data = s.data;
		this.hopsToLive = Math.max(0, Math.min(255, s.hopsToLive - 1));
	}

	public Snapshot(Snapshot s) {
		this(s, s.address);
	}

	public Snapshot(final ByteBuf input) {
		try {
			id = new UUID(input.readLong(), input.readLong());
			snapshotType = SnapshotType.values()[input.readUnsignedByte()];
			stateSequenceNumber = input.readShort();
			snapshotTimestamp = input.readUnsignedMedium() << 8;

			if (snapshotType != SnapshotType.HEADER) {
				InetAddress inetAddress = IP_TYPE.fromDataInput(input);
				address = inetAddress == null ? null : new InetSocketAddress(inetAddress, input.readUnsignedShort());
				tcpPort = input.readUnsignedShort();
				data = new HashMap<>();
				if (snapshotType == SnapshotType.PAYLOAD_UPDATE) {
					int size = input.readUnsignedShort();
					for (int i = 0; i < size; i++) {
						String key = input.readCharSequence(input.readUnsignedShort(), UTF_8)
							.toString();
						byte[] value = new byte[input.readUnsignedShort()];
						input.readBytes(value);
						data.put(key, value);
					}
				}
				this.hopsToLive = input.readUnsignedByte();
			} else {
				address = null;
				tcpPort = -1;
				data = null;
				hopsToLive = -1;
			}

		} catch (IOException ioe) {
			logger.error("Failed to read snapshot", ioe);
			throw new RuntimeException("Failed to read snapshot", ioe);
		}
	}

	public Snapshot(UUID id, InetSocketAddress address, int tcpPort, short stateSequenceNumber, int snapshotTime,
		SnapshotType type, Map<String, byte[]> data, int hopsToLive) {
		this.id = id;
		this.address = address;
		this.tcpPort = tcpPort;
		this.stateSequenceNumber = stateSequenceNumber;
		this.snapshotTimestamp = snapshotTime;
		this.snapshotType = type;
		this.data = data == null ? Collections.emptyMap() : data;
		this.hopsToLive = hopsToLive;
	}

	public void writeOut(ByteBuf output) {
		try {
			output.writeLong(id.getMostSignificantBits());
			output.writeLong(id.getLeastSignificantBits());

			output.writeByte(snapshotType.ordinal());

			output.writeShort(stateSequenceNumber);

			output.writeMedium(snapshotTimestamp >> 8);

			if (snapshotType != SnapshotType.HEADER) {
				IP_TYPE type = IP_TYPE.fromInetSocketAddress(address);
				type.writeOut(address, output);

				output.writeShort(tcpPort);

				if (snapshotType == SnapshotType.PAYLOAD_UPDATE) {
					output.writeShort(data.size());
					for (Entry<String, byte[]> e : data.entrySet()) {
						AbstractGossipMessage.writeUTF8(output, e.getKey());
						byte[] value = e.getValue();
						if (value.length > 0x0FFF) {
							throw new IllegalArgumentException(
								"The stored value for key " + e.getKey() + " is too large");
						}
						output.writeShort(value.length);
						output.writeBytes(value);
					}
				}
				output.writeByte(hopsToLive);
			}
		} catch (IOException ioe) {
			logger.error("Failed to write snapshot", ioe);
			throw new RuntimeException("Failed to write snapshot", ioe);
		}
	}

	public UUID getId() {
		return id;
	}

	public InetAddress getAddress() {
		return address == null ? null : address.getAddress();
	}

	public InetSocketAddress getUdpAddress() {
		return address;
	}

	public int getUdpPort() {
		return address == null ? -1 : address.getPort();
	}

	public int getTcpPort() {
		return tcpPort;
	}

	public short getStateSequenceNumber() {
		return stateSequenceNumber;
	}

	public int getSnapshotTimestamp() {
		return snapshotTimestamp;
	}

	public SnapshotType getMessageType() {
		return snapshotType;
	}

	public Map<String, byte[]> getData() {
		return data;
	}

	public boolean forwardable() {
		return hopsToLive > 0;
	}

	public int getRemainingHops() {
		return hopsToLive;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (obj instanceof Snapshot) {
			Snapshot other = (Snapshot) obj;
			return id.equals(other.id);
		}
		return false;
	}

	@Override
	public String toString() {
		return "Snapshot [id=" + id + ", address=" + address + ", tcpPort=" + tcpPort + ", stateSequenceNumber="
			+ stateSequenceNumber + ", snapshotTimestamp=" + snapshotTimestamp + ", snapshotType=" + snapshotType
			+ ", data=" + data + ", hopsToLive=" + hopsToLive + "]";
	}

	/**
	 * UUID + sequence + type + timestamp
	 */
	private static final int	FIXED_OVERHEAD_HEADER		= 16 + 2 + +1 + 3;
	/**
	 * HEADER + assumed IPV6 + udp port + tcp port + hops
	 */
	private static final int	FIXED_OVERHEAD_HEARTBEAT	= FIXED_OVERHEAD_HEADER + 16 + 2 + 2 + 1;

	public int guessSize() {
		switch (snapshotType) {
			case HEADER :
				return FIXED_OVERHEAD_HEADER;
			case HEARTBEAT :
				return FIXED_OVERHEAD_HEARTBEAT;
			default :
				int extra = 0;
				if (data != null) {
					extra = data.entrySet()
						.stream()
						.mapToInt(e -> ByteBufUtil.utf8MaxBytes(e.getKey()) + 2 + e.getValue().length)
						.sum();
				}

				return FIXED_OVERHEAD_HEARTBEAT + extra;
		}
	}
}
