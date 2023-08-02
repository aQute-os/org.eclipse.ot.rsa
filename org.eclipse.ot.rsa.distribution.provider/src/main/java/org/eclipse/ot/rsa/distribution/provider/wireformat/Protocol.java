package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType;
import org.eclipse.ot.rsa.distribution.util.Utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class Protocol implements Dispatcher {
	final Serializer	serializer;
	final Consumer<Msg>	queue;

	public Protocol(Serializer serializer, Consumer<Msg> queue) {
		this.serializer = serializer;
		this.queue = queue;
	}

	@Override
	public Msg callWithReturn(UUID service, int callId, int methodIndex, Object... args) {
		try {

			ByteBuf buffer = header(ClientMessageType.CALL_WITH_RETURN_TYPE, callId, service);
			buffer.writeShort(methodIndex);
			serialize(buffer, args);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeCallWithReturn(Msg msg, Server server) {
		try {
			ByteBuf copy = msg.copyPayload();
			int methodIndex = copy.readShort();
			return server.callWithReturn(msg.serviceId, msg.callId, methodIndex, serializer.deserializeArgs(copy));
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg callWithoutReturn(UUID service, int callId, int methodIndex, Object... args) {
		try {
			ByteBuf buffer = header(ClientMessageType.CALL_WITHOUT_RETURN_TYPE, callId, service);
			buffer.writeShort(methodIndex);
			serialize(buffer, args);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeCallWithoutReturn(Msg msg, Server server) {
		try {
			ByteBuf copy = msg.copyPayload();
			int methodIndex = copy.readShort();
			return server.callWithoutReturn(msg.serviceId, msg.callId, methodIndex, serializer.deserializeArgs(copy));
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg cancel(UUID service, int callId, boolean interrupt) {
		try {
			ByteBuf buffer = header(ClientMessageType.CANCEL_TYPE, callId, service);
			buffer.writeBoolean(interrupt);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeCancel(Msg msg, Server server) {
		try {
			ByteBuf copy = msg.copyPayload();
			boolean interrupt = copy.readBoolean();
			return server.cancel(msg.serviceId, msg.callId, interrupt);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg asyncMethodParamData(UUID service, int callId, int paramIndex, Object value) {
		try {
			ByteBuf buffer = header(ClientMessageType.ASYNC_METHOD_PARAM_DATA_TYPE, callId, service);
			buffer.writeByte(paramIndex);
			serializer.serializeReturn(buffer, value);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeAsyncMethodParamData(Msg msg, Server server) {
		try {
			ByteBuf copy = msg.copyPayload();
			byte paramIndex = copy.readByte();
			return server.asyncMethodParamData(msg.serviceId, msg.callId, paramIndex,
				serializer.deserializeReturn(copy));
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg asyncMethodParamClose(UUID service, int callId, int paramIndex) {
		try {
			ByteBuf buffer = header(ClientMessageType.ASYNC_METHOD_PARAM_CLOSE_TYPE, callId, service);
			buffer.writeByte(paramIndex);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeAsyncMethodParamClose(Msg msg, Server server) {
		try {
			ByteBuf copy = msg.copyPayload();
			byte paramIndex = copy.readByte();
			return server.asyncMethodParamClose(msg.serviceId, msg.callId, paramIndex);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg asyncMethodParamFailure(UUID service, int callId, int paramIndex, Object failure) {
		try {
			ByteBuf buffer = header(ClientMessageType.ASYNC_METHOD_PARAM_CLOSE_TYPE, callId, service);
			buffer.writeByte(paramIndex);
			serializer.serializeReturn(buffer, failure);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeAsyncMethodParamFailure(Msg msg, Server server) {
		try {
			ByteBuf copy = msg.copyPayload();
			byte paramIndex = copy.readByte();
			return server.asyncMethodParamClose(msg.serviceId, msg.callId, paramIndex);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg clientOpen(UUID service, int callId) {
		try {
			ByteBuf buffer = header(ClientMessageType.CLIENT_OPEN_TYPE, callId, service);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeClientOpen(Msg msg, Server server) {
		try {
			ByteBuf copy = msg.copyPayload();
			return server.clientOpen(msg.serviceId, msg.callId);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg clientClose(UUID service, int callId) {
		try {
			ByteBuf buffer = header(ClientMessageType.CLIENT_CLOSE_TYPE, callId, service);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeClientClose(Msg msg, Server server) {
		try {
			return server.clientClose(msg.serviceId, msg.callId);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg clientBackPressure(UUID service, int callId) {
		try {
			ByteBuf buffer = header(ClientMessageType.CLIENT_BACK_PRESSURE_TYPE, callId, service);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeClientBackPressure(Msg msg, Server server) {
		try {
			return server.clientBackPressure(msg.serviceId, msg.callId);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg successResponse(UUID service, int callId, Object result) {
		try {
			ByteBuf buffer = header(ServerMessageType.SUCCESS_RESPONSE_TYPE, service, callId);
			serializer.serializeReturn(buffer, result);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeSuccessResponse(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();

			Object result = serializer.deserializeReturn(copy);
			return client.successResponse(msg.serviceId, msg.callId, result);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureResponse(UUID service, int callId, Throwable result) {
		try {
			ByteBuf buffer = header(ServerMessageType.FAILURE_RESPONSE_TYPE, service, callId);
			serializer.serializeReturn(buffer, result);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeFailureResponse(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			Throwable result = (Throwable) serializer.deserializeReturn(copy);
			return client.failureResponse(msg.serviceId, msg.callId, result);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureNoService(UUID service, int callid) {
		try {
			ByteBuf buffer = header(ServerMessageType.FAILURE_NO_SERVICE_TYPE, service, callid);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	Msg decodeFailureNoService(Msg msg, Client client) {
		try {
			return client.failureNoService(msg.serviceId, msg.callId);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureNoMethod(UUID service, int callId) {
		return fixup(header(ServerMessageType.FAILURE_NO_METHOD_TYPE, service, callId));
	}

	Msg decodeFailureNoMethod(Msg msg, Client client) {
		try {
			return client.failureNoMethod(msg.serviceId, msg.callId);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureToDeserialize(UUID service, int callId, String message) {
		try {
			ByteBuf buffer = header(ServerMessageType.FAILURE_TO_DESERIALIZE_TYPE, service, callId);
			serializer.serializeReturn(buffer, message);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeFailureToDeserialize(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			String message = readString(copy);
			return client.failureToDeserialize(msg.serviceId, msg.callId, message);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureToSerializeSuccess(UUID service, int callId, String message) {
		try {
			ByteBuf buffer = header(ServerMessageType.FAILURE_TO_SERIALIZE_SUCCESS_TYPE, service, callId);
			writeString(buffer, message);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeFailureToSerializeSuccess(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			String message = readString(copy);
			return client.failureToSerializeSuccess(msg.serviceId, msg.callId, message);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureToSerializeFailure(UUID service, int callId, String message) {
		try {
			ByteBuf buffer = header(ServerMessageType.FAILURE_TO_SERIALIZE_FAILURE_TYPE, service, callId);
			writeString(buffer, message);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeFailureToSerializeFailure(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			String message = readString(copy);
			return client.failureToSerializeFailure(msg.serviceId, msg.callId, message);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureServerOverloaded(UUID service, int callId, String message) {
		try {
			ByteBuf buffer = header(ServerMessageType.FAILURE_SERVER_OVERLOADED_TYPE, service, callId);
			writeString(buffer, message);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeFailureServerOverloaded(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			String message = readString(copy);
			return client.failureServerOverloaded(msg.serviceId, msg.callId, message);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureUnknown(UUID service, int callId, String message) {
		try {
			ByteBuf buffer = header(ServerMessageType.FAILURE_UNKNOWN_TYPE, service, callId);
			writeString(buffer, message);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeFailureUnknown(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			String message = readString(copy);
			return client.failureUnknown(msg.serviceId, msg.callId, message);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverAsyncMethodParamError(UUID service, int callId, int paramIndex, Object value) {
		try {
			ByteBuf buffer = header(ServerMessageType.SERVER_ASYNC_METHOD_PARAM_ERROR_TYPE, service, callId);
			buffer.writeByte(paramIndex);
			serializer.serializeReturn(buffer, value);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeServerAsyncMethodParamError(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			int paramIndex = copy.readByte(); // Read the param index
			Object value = serializer.deserializeReturn(copy);
			return client.serverAsyncMethodParamError(msg.serviceId, msg.callId, paramIndex, value);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverDataEvent(UUID service, int callId, Object serializedData) {
		try {
			ByteBuf buffer = header(ServerMessageType.SERVER_DATA_EVENT_TYPE, service, callId);
			serializer.serializeReturn(buffer, serializedData);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeServerDataEvent(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			Object serializedData = serializer.deserializeReturn(copy);
			return client.serverDataEvent(msg.serviceId, msg.callId, serializedData);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverCloseEvent(UUID service, int callId) {
		try {
			ByteBuf buffer = header(ServerMessageType.SERVER_CLOSE_EVENT_TYPE, service, callId);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeServerCloseEvent(Msg msg, Client client) {
		try {
			return client.serverCloseEvent(msg.serviceId, msg.callId);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverErrorEvent(UUID service, int callId, Object failure) {
		try {
			ByteBuf buffer = header(ServerMessageType.SERVER_ERROR_EVENT_TYPE, service, callId);
			serializer.serializeReturn(buffer, failure);
			return fixup(buffer);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg decodeServerErrorEvent(Msg msg, Client client) {
		try {
			ByteBuf copy = msg.copyPayload();
			Object failure = serializer.deserializeReturn(copy);
			return client.serverErrorEvent(msg.serviceId, msg.callId, failure);
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	private Msg fixup(ByteBuf buffer) {
		int size = buffer.writerIndex() - 4;
		for (int i = 0; i < 3; i++) {
			int shift = (2 - i) * 8;
			int bvalue = 0xFF & (size >> shift);
			buffer.setByte(i + 1, bvalue);
		}
		Msg msg = new Msg(this, buffer);
		queue.accept(msg);
		return msg;
	}

	@Override
	public Msg dispatch(Msg msg, Server server) {
		switch (msg.command) {
			case Protocol_V1.CALL_WITH_RETURN :
				return decodeCallWithReturn(msg, server);

			case Protocol_V1.CALL_WITHOUT_RETURN :
				return decodeCallWithoutReturn(msg, server);

			case Protocol_V1.CANCEL :
				return decodeCancel(msg, server);

			case Protocol_V2.ASYNC_METHOD_PARAM_DATA :
				return decodeAsyncMethodParamData(msg, server);

			case Protocol_V2.ASYNC_METHOD_PARAM_CLOSE :
				return decodeAsyncMethodParamClose(msg, server);
			case Protocol_V2.ASYNC_METHOD_PARAM_FAILURE :
				return decodeAsyncMethodParamFailure(msg, server);

			case Protocol_V2.CLIENT_OPEN :
				return decodeClientOpen(msg, server);

			case Protocol_V2.CLIENT_CLOSE :
				return decodeClientClose(msg, server);

			case Protocol_V2.CLIENT_BACK_PRESSURE :
				return decodeClientBackPressure(msg, server);

			default :
				throw new IllegalArgumentException("Unknown message command for a server: " + msg);
		}
	}

	@Override
	public Msg dispatch(Msg msg, Client client) {
		switch (msg.command) {
			case Protocol_V1.SUCCESS_RESPONSE :
				return decodeSuccessResponse(msg, client);
			case Protocol_V1.FAILURE_RESPONSE :
				return decodeFailureResponse(msg, client);
			case Protocol_V1.FAILURE_NO_SERVICE :
				return decodeFailureNoService(msg, client);
			case Protocol_V1.FAILURE_NO_METHOD :
				return decodeFailureNoMethod(msg, client);

			case Protocol_V1.FAILURE_TO_DESERIALIZE :
				return decodeFailureToDeserialize(msg, client);

			case Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS :
				return decodeFailureToSerializeSuccess(msg, client);

			case Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE :
				return decodeFailureToSerializeFailure(msg, client);

			case Protocol_V1.FAILURE_SERVER_OVERLOADED :
				return decodeFailureServerOverloaded(msg, client);

			case Protocol_V1.FAILURE_UNKNOWN :
				return decodeFailureUnknown(msg, client);

			case Protocol_V2.SERVER_ASYNC_METHOD_PARAM_ERROR :
				return decodeServerAsyncMethodParamError(msg, client);

			case Protocol_V2.SERVER_DATA_EVENT :
				return decodeServerDataEvent(msg, client);
			case Protocol_V2.SERVER_CLOSE_EVENT :
				return decodeServerCloseEvent(msg, client);

			case Protocol_V2.SERVER_ERROR_EVENT :
				return decodeServerErrorEvent(msg, client);

			default :
				throw new IllegalArgumentException("Unknown message command for a client: " + msg.command);
		}
	}

	public ByteBuf header(ClientMessageType cmt, int callId, UUID serviceId) {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeByte(cmt.getVersion())
			.writeBytes(new byte[3])
			.writeByte(cmt.getCommand())
			.writeLong(serviceId.getMostSignificantBits())
			.writeLong(serviceId.getLeastSignificantBits())
			.writeInt(callId);
		return buffer;
	}

	public ByteBuf header(ServerMessageType cmt, UUID serviceId, int callId) {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeByte(cmt.getVersion())
			.writeBytes(new byte[3])
			.writeByte(cmt.getCommand())
			.writeLong(serviceId.getMostSignificantBits())
			.writeLong(serviceId.getLeastSignificantBits())
			.writeInt(callId);
		return buffer;
	}

	public void close() {

	}

	private void serialize(ByteBuf buffer, Object[] args) throws IOException {
		if (args != null && args.length == 1 && args[0] instanceof ByteBuf) {
			ByteBuf b = (ByteBuf) args[0];
			buffer.writeBytes(b);
		} else {
			serializer.serializeArgs(buffer, args);
		}
	}

	private String readString(ByteBuf copy) {
		int l = copy.readUnsignedShort();
		return copy.readCharSequence(l, StandardCharsets.UTF_8)
			.toString();
	}

	private void writeString(ByteBuf buffer, String message) {
		int lengthIndex = buffer.writerIndex();
		buffer.writeShort(0);
		buffer.writeCharSequence(message, StandardCharsets.UTF_8);
		buffer.setShort(lengthIndex, buffer.writerIndex() - 2);

	}

}
