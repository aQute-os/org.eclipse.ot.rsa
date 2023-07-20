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
package org.eclipse.ot.rsa.distribution.provider.client;

import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.CacheAction.ADD;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.CacheAction.REMOVE;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.CacheAction.SKIP;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CANCEL;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_CLOSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_DATA;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_FAILURE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.CLIENT_BACK_PRESSURE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.CLIENT_CLOSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.CLIENT_OPEN;

import org.eclipse.ot.rsa.distribution.provider.message.MessageType;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;

public enum ClientMessageType implements MessageType {

	//@formatter:off
	CALL_WITH_RETURN_TYPE                   (Protocol_V1.VERSION, CALL_WITH_RETURN,              ADD),
	CALL_WITHOUT_RETURN_TYPE                (Protocol_V1.VERSION, CALL_WITHOUT_RETURN,           SKIP),
	CANCEL_TYPE                             (Protocol_V1.VERSION, CANCEL,                        REMOVE),
	ASYNC_METHOD_PARAM_DATA_TYPE            (Protocol_V2.VERSION, ASYNC_METHOD_PARAM_DATA,       SKIP),
	ASYNC_METHOD_PARAM_FAILURE_TYPE         (Protocol_V2.VERSION, ASYNC_METHOD_PARAM_FAILURE,    SKIP),
	ASYNC_METHOD_PARAM_CLOSE_TYPE           (Protocol_V2.VERSION, ASYNC_METHOD_PARAM_CLOSE,      SKIP),
	CLIENT_OPEN_TYPE                        (Protocol_V2.VERSION, CLIENT_OPEN,                   ADD),
	CLIENT_CLOSE_TYPE                       (Protocol_V2.VERSION, CLIENT_CLOSE,                  REMOVE),
	CLIENT_BACK_PRESSURE_TYPE               (Protocol_V2.VERSION, CLIENT_BACK_PRESSURE,          SKIP);
	//@formatter:on

	public enum CacheAction {
		ADD,
		REMOVE,
		SKIP
	}

	private final byte			version;
	private final byte			command;
	private final CacheAction	action;

	ClientMessageType(byte version, byte command, CacheAction action) {
		this.version = version;
		this.command = command;
		this.action = action;
	}

	@Override
	public byte getVersion() {
		return version;
	}

	@Override
	public byte getCommand() {
		return command;
	}

	public CacheAction getAction() {
		return action;
	}
}
