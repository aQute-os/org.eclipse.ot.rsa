package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Formatter;
import java.util.UUID;

import org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType;
import org.eclipse.ot.rsa.distribution.provider.message.MessageType;
import org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType;
import org.eclipse.ot.rsa.distribution.util.Utils;

import io.netty.buffer.ByteBuf;

public class Msg {

	final static MessageType[] MT = new MessageType[Protocol_V2.LAST_COMMAND];
	static {
		for (ServerMessageType st : ServerMessageType.values()) {
			int index = st.getCommand();
			assert MT[index] == null;
			MT[index] = st;
		}
		for (ClientMessageType st : ClientMessageType.values()) {
			int index = st.getCommand();
			assert MT[index] == null;
			MT[index] = st;
		}
	}

	private final ByteBuf	buffer;
	final int				version;
	final int				length;
	final int				command;
	final UUID				serviceId;
	final int				callId;
	final int				start;
	final MessageType		messageType;
	final Protocol			protocol;
	String					text;

	public Msg(Protocol protocol, ByteBuf buffer) {
		this.protocol = protocol;
		this.buffer = buffer.copy()
			.asReadOnly();
		this.version = buffer.readByte();
		int l = 0;
		for (int i = 0; i < 3; i++) {
			int v = buffer.readUnsignedByte();
			l = l * 256;
			l += v;
		}
		this.length = l;
		this.command = buffer.readByte();
		long high = buffer.readLong();
		long low = buffer.readLong();
		this.serviceId = new UUID(high, low);
		this.callId = buffer.readInt();
		this.start = buffer.readerIndex();
		this.messageType = MT[this.command];
	}

	public Msg send(ByteChannel channel) throws IOException {
		ByteBuffer nioBuffer = buffer.nioBuffer(0, buffer.writerIndex());
		channel.write(nioBuffer);
		return this;
	}

	public ByteBuf copy() {
		return buffer.copy();
	}

	public ByteBuf copyPayload() {
		ByteBuf copy = copy();
		copy.readerIndex(start);
		return copy;
	}

	@Override
	public String toString() {
		if (text == null)
			try {
				StringWriter sw = new StringWriter();
				if (messageType instanceof ServerMessageType) {
					Client printer = Utils.printer(Client.class, sw);
					protocol.dispatch(this, printer);
				} else {
					Server printer = Utils.printer(Server.class, sw);
					protocol.dispatch(this, printer);
				}
				this.text = sw.toString();
			} catch (Exception e) {
				return e.toString();
			}
		return text;
	}

	public static String report(ByteBuf buffer) {
		ByteBuf copy = buffer.copy();
		int n = 0;
		try (Formatter f = new Formatter()) {
			String del = "";
			while (copy.isReadable()) {
				if ((n % 16) == 0) {
					f.format("%s%04X ", del, n);
					del = "\n";
				}
				f.format(" %02X", copy.readUnsignedByte());
				n++;
			}
			f.format("\n");
			return f.toString();
		}
	}

}
