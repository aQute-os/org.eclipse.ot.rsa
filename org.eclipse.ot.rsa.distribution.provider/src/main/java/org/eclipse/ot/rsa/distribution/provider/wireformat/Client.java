package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.util.UUID;

public interface Client {
	Msg successResponse(UUID service, int callId, Object result);

	Msg failureResponse(UUID service, int callId, Throwable result);

	Msg failureNoService(UUID service, int callId);

	Msg failureNoMethod(UUID service, int callId);

	Msg failureToDeserialize(UUID service, int callId, String message);

	Msg failureToSerializeSuccess(UUID service, int callId, String message);

	Msg failureToSerializeFailure(UUID service, int callId, String message);

	Msg failureServerOverloaded(UUID service, int callId, String message);

	Msg failureUnknown(UUID service, int callId, String message);

	Msg serverDataEvent(UUID service, int callId, Object serializedData);

	Msg serverCloseEvent(UUID service, int callId);

	Msg serverErrorEvent(UUID service, int callId, Object failure);

	Msg serverAsyncMethodParamError(UUID service, int callId, int paramIndex, Object value);

}
