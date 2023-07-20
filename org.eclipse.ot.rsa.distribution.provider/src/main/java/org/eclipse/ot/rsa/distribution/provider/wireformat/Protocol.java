package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType;
import org.eclipse.ot.rsa.distribution.util.Utils;

public class Protocol implements Protocol_V2, Protocol_V1 {
	final static AtomicInteger	callId	= new AtomicInteger(1000);
	final UUID					serviceId;
	final Serializer			serializer;

	public Protocol(UUID uuid, Serializer serializer) {
		this.serviceId = uuid;
		this.serializer = serializer;
	}

	/**
	 * Format: | Header | method index short | serialized args | Usage - sent by
	 * client to indicate a method call with an expectation of a return value
	 */
	@Override
	public Msg callWithReturn(int methodIndex, Object... args) {
		try {
			Msg msg = header(ClientMessageType.CALL_WITH_RETURN_TYPE);
			msg.buffer.writeShort(methodIndex);
			serializer.serializeArgs(msg.buffer, args);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg callWithoutReturn(int methodIndex, Object... args) {
		try {
			Msg msg = header(ClientMessageType.CALL_WITHOUT_RETURN_TYPE);
			msg.buffer.writeShort(methodIndex);
			serializer.serializeArgs(msg.buffer, args);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg cancel(boolean interrupt) {
		try {
			Msg msg = header(ClientMessageType.CANCEL_TYPE);
			msg.buffer.writeByte(interrupt ? -1 : 0);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg successResponse(int callId, Object result) {
		try {
			Msg msg = header(ServerMessageType.SUCCESS_RESPONSE_TYPE, callId);
			serializer.serializeArgs(msg.buffer, result);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureResponse(int callId, Throwable result) {
		try {
			Msg msg = header(ServerMessageType.FAILURE_RESPONSE_TYPE, callId);
			serializer.serializeArgs(msg.buffer, result);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureNoService(int callid) {
		try {
			return header(ServerMessageType.FAILURE_NO_SERVICE_TYPE, callid).fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureNoMethod(int callId) {
		return header(ServerMessageType.FAILURE_NO_METHOD_TYPE, callId).fixup();
	}

	@Override
	public Msg failureToDeserialize(int callId, String message) {
		try {
			Msg msg = header(ServerMessageType.FAILURE_TO_DESERIALIZE_TYPE, callId);
			serializer.serializeReturn(msg.buffer, message);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureToSeserializeSuccess(int callId, String message) {
		try {
			Msg msg = header(ServerMessageType.FAILURE_TO_SERIALIZE_SUCCESS_TYPE, callId);
			serializer.serializeReturn(msg.buffer, message);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureToSeserializeFailure(int callId, String message) {
		try {
			Msg msg = header(ServerMessageType.FAILURE_TO_SERIALIZE_FAILURE_TYPE, callId);
			serializer.serializeReturn(msg.buffer, message);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureServerOverloaded(int callId, String message) {
		try {
			Msg msg = header(ServerMessageType.FAILURE_SERVER_OVERLOADED_TYPE, callId);
			serializer.serializeReturn(msg.buffer, message);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg failureUnknown(int callId, String message) {
		try {
			Msg msg = header(ServerMessageType.FAILURE_UNKNOWN_TYPE, callId);
			serializer.serializeReturn(msg.buffer, message);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverAsyncMethodParamError(int callId, int paramIndex, Object value) {
		try {
			Msg msg = header(ServerMessageType.SERVER_ASYNC_METHOD_PARAM_ERROR_TYPE, callId);
			msg.buffer.writeByte(paramIndex);
			serializer.serializeReturn(msg.buffer, value);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg asyncMethodParamData(int paramIndex, Object value) {
		try {
			Msg msg = header(ClientMessageType.ASYNC_METHOD_PARAM_DATA_TYPE);
			msg.buffer.writeByte(paramIndex);
			serializer.serializeReturn(msg.buffer, value);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg asyncMethodParamClose(int paramIndex) {
		try {
			Msg msg = header(ClientMessageType.ASYNC_METHOD_PARAM_CLOSE_TYPE);
			msg.buffer.writeByte(paramIndex);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg asyncMethodParamFailure(int paramIndex, Object failure) {
		try {
			Msg msg = header(ClientMessageType.ASYNC_METHOD_PARAM_CLOSE_TYPE);
			msg.buffer.writeByte(paramIndex);
			serializer.serializeReturn(msg.buffer, failure);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg clientOpen() {
		try {
			Msg msg = header(ClientMessageType.CLIENT_OPEN_TYPE);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg clientClose() {
		try {
			Msg msg = header(ClientMessageType.CLIENT_CLOSE_TYPE);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg clientBackPressure() {
		try {
			Msg msg = header(ClientMessageType.CLIENT_BACK_PRESSURE_TYPE);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverDataEvent(int callId, Object serializedData) {
		try {
			Msg msg = header(ServerMessageType.SERVER_DATA_EVENT_TYPE, callId);
			serializer.serializeReturn(msg.buffer, serializedData);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverCloseEvent(int callId) {
		try {
			Msg msg = header(ServerMessageType.SERVER_CLOSE_EVENT_TYPE, callId);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public Msg serverErrorEvent(int callId, Object failure) {
		try {
			Msg msg = header(ServerMessageType.SERVER_ERROR_EVENT_TYPE, callId);
			serializer.serializeReturn(msg.buffer, failure);
			return msg.fixup();
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	public Msg header(ClientMessageType cmt) {
		return new Msg(cmt.getVersion(), cmt.getCommand(), serviceId, callId.getAndIncrement());
	}

	public Msg header(ServerMessageType cmt, int callId) {
		return new Msg(cmt.getVersion(), cmt.getCommand(), serviceId, callId);
	}

}
