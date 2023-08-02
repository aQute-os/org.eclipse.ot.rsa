package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.util.UUID;

public interface Server {

	Msg callWithReturn(UUID serviceId, int callId, int methodIndex, Object... args);

	Msg callWithoutReturn(UUID serviceId, int callId, int methodIndex, Object... args);

	Msg cancel(UUID serviceId, int callId, boolean interrupt);

	Msg asyncMethodParamData(UUID serviceId, int callId, int paramIndex, Object value);

	Msg asyncMethodParamClose(UUID serviceId, int callId, int paramIndex);

	Msg asyncMethodParamFailure(UUID serviceId, int callId, int paramIndex, Object failure);

	Msg clientOpen(UUID serviceId, int callId);

	Msg clientClose(UUID serviceId, int callId);

	Msg clientBackPressure(UUID serviceId, int callId);

}
