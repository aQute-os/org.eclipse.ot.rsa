package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class Msg {
	final ByteBuf	buffer	= Unpooled.buffer(1000);
	final int		callId;
	final UUID		serviceId;
	final int		version;
	final int		command;

	public Msg(int version, int command, UUID serviceId, int callId) {
		assert version < 128;
		assert command < 128;
		assert serviceId != null;

		this.version = version;
		this.command = command;
		this.callId = callId;
		this.serviceId = serviceId;
		buffer.writeByte(version)
			.writeBytes(new byte[3])
			.writeLong(serviceId.getMostSignificantBits())
			.writeLong(serviceId.getLeastSignificantBits())
			.writeByte(command)
			.writeInt(callId);
	}

	public Msg fixup() {
		int l = version << 24 + buffer.writerIndex();
		l |= (version << 24) + l;
		buffer.setInt(0, l);
		return this;
	}

}
