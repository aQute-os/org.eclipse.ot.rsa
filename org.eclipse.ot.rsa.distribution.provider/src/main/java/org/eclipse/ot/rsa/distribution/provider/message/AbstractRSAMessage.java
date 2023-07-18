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
package org.eclipse.ot.rsa.distribution.provider.message;

import java.io.IOException;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;

public abstract class AbstractRSAMessage<M extends MessageType> {

	private final M		type;

	private final UUID	serviceId;

	private final int	callId;

	public AbstractRSAMessage(M type, UUID serviceId, int callId) {
		this.type = type;
		this.serviceId = serviceId;
		this.callId = callId;
	}

	public final M getType() {
		return type;
	}

	public final UUID getServiceId() {
		return serviceId;
	}

	public final int getCallId() {
		return callId;
	}

	public abstract void write(ByteBuf buffer, ChannelPromise promise) throws IOException;

	protected final void writeHeader(ByteBuf buffer) {
		buffer.writeByte(type.getVersion())
			.writeMedium(0)
			.writeByte(type.getCommand())
			.writeLong(serviceId.getMostSignificantBits())
			.writeLong(serviceId.getLeastSignificantBits())
			.writeInt(callId);
	}

	protected final void writeLength(ByteBuf buffer) {
		final int pos = buffer.readerIndex();
		final int length = buffer.readableBytes() - 4;
		if ((length & 0xF000) != 0) {
			lengthError(length);
		}
		buffer.setMedium(pos + 1, length);
	}

	private void lengthError(final int length) {
		if (length < 0) {
			throw new IllegalArgumentException("Adjusted frame length (" + length + ") is less than zero");
		} else if (length >= 16777216) {
			throw new IllegalArgumentException("length does not fit into a medium integer: " + length);
		}
	}

	public final CacheKey getKey() {
		return new CacheKey(getServiceId(), getCallId());
	}

	public static final class CacheKey {
		private final UUID	serviceId;

		private final int	callId;

		public CacheKey(UUID serviceId, int callId) {
			this.serviceId = serviceId;
			this.callId = callId;
		}

		@Override
		public int hashCode() {
			return serviceId.hashCode() ^ callId;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if ((obj == null) || !(obj instanceof CacheKey))
				return false;
			CacheKey other = (CacheKey) obj;
			if (callId != other.callId)
				return false;
			if (serviceId == null) {
				if (other.serviceId != null)
					return false;
			} else if (!serviceId.equals(other.serviceId))
				return false;
			return true;
		}

		public UUID getId() {
			return serviceId;
		}

		public int getCallId() {
			return callId;
		}
	}
}
