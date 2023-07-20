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
package org.eclipse.ot.rsa.distribution.provider.server;

import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_NO_METHOD;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_NO_SERVICE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_RESPONSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_SERVER_OVERLOADED;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_DESERIALIZE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.FAILURE_UNKNOWN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.SUCCESS_RESPONSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.SERVER_ASYNC_METHOD_PARAM_ERROR;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.SERVER_CLOSE_EVENT;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.SERVER_DATA_EVENT;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.SERVER_ERROR_EVENT;

import org.eclipse.ot.rsa.distribution.provider.message.MessageType;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;

public enum ServerMessageType implements MessageType {

	// @formatter:off
	SUCCESS_RESPONSE_TYPE                (Protocol_V1.VERSION, SUCCESS_RESPONSE,             false),
	FAILURE_RESPONSE_TYPE                (Protocol_V1.VERSION, FAILURE_RESPONSE,             true),
	FAILURE_NO_METHOD_TYPE               (Protocol_V1.VERSION, FAILURE_NO_METHOD,            true),
	FAILURE_NO_SERVICE_TYPE               (Protocol_V1.VERSION, FAILURE_NO_SERVICE,          true),
	FAILURE_SERVER_OVERLOADED_TYPE       (Protocol_V1.VERSION, FAILURE_SERVER_OVERLOADED,    true),
	FAILURE_TO_DESERIALIZE_TYPE          (Protocol_V1.VERSION, FAILURE_TO_DESERIALIZE,       true),
	FAILURE_TO_SERIALIZE_SUCCESS_TYPE    (Protocol_V1.VERSION, FAILURE_TO_SERIALIZE_SUCCESS, true),
	FAILURE_TO_SERIALIZE_FAILURE_TYPE    (Protocol_V1.VERSION, FAILURE_TO_SERIALIZE_FAILURE, true),
	FAILURE_UNKNOWN_TYPE                 (Protocol_V1.VERSION, FAILURE_UNKNOWN,              true),
	SERVER_ASYNC_METHOD_PARAM_ERROR_TYPE (Protocol_V2.VERSION, SERVER_ASYNC_METHOD_PARAM_ERROR, true),
	SERVER_DATA_EVENT_TYPE               (Protocol_V2.VERSION, SERVER_DATA_EVENT,            false),
	SERVER_CLOSE_EVENT_TYPE              (Protocol_V2.VERSION, SERVER_CLOSE_EVENT,           false),
	SERVER_ERROR_EVENT_TYPE              (Protocol_V2.VERSION, SERVER_ERROR_EVENT,           true);
	// @formatter:on

	private final byte		version;
	private final byte		command;
	private final boolean	isError;

	ServerMessageType(byte version, byte command, boolean isError) {
		this.version = version;
		this.command = command;
		this.isError = isError;
	}

	@Override
	public byte getVersion() {
		return version;
	}

	@Override
	public byte getCommand() {
		return command;
	}

	public boolean isError() {
		return isError;
	}

}
