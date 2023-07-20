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
package org.eclipse.ot.rsa.distribution.provider.wireformat;

public interface Protocol_V2 extends Protocol_V1 {

	byte	VERSION							= 2;

	/**
	 * The standard message header format is as follows: byte 0 | byte 1-3 |
	 * byte 4 | long, long | int | Version | message length | Command | Service
	 * ID | Call ID |
	 */

	/**
	 * Commands - Note that these are backward compatible with V1, so no reused
	 * command ids
	 */

	/**
	 * Format: | Header | param index unsigned byte | serialized value | Usage -
	 * sent by the server to indicate an error processing a data event for the
	 * named argument
	 */
	byte	SERVER_ASYNC_METHOD_PARAM_ERROR	= 13;

	Msg serverAsyncMethodParamError(int callId, int paramIndex, Object value);

	/**
	 * Format: | Header | param index unsigned byte | serialized value | Usage -
	 * sent by the client to propagate a data event for the named argument
	 */
	byte	ASYNC_METHOD_PARAM_DATA			= 14;

	Msg asyncMethodParamData(int paramIndex, Object value);

	/**
	 * Format: | Header | param index unsigned byte | Usage - sent by the client
	 * to propagate a close event for the named argument
	 */
	byte	ASYNC_METHOD_PARAM_CLOSE		= 15;

	Msg asyncMethodParamClose(int paramIndex);

	/**
	 * Format: | Header | param index unsigned byte | serialized failure | Usage
	 * - sent by the client to propagate a failure event for the named argument
	 */
	byte	ASYNC_METHOD_PARAM_FAILURE		= 16;

	Msg asyncMethodParamFailure(int paramIndex, Object failure);

	/**
	 * Format: | Header | Usage - sent by the client to open a streaming
	 * response
	 */
	byte	CLIENT_OPEN						= 17;

	Msg clientOpen();

	/**
	 * Format: | Header | Usage - sent by the client to close a streaming
	 * response
	 */
	byte	CLIENT_CLOSE					= 18;

	Msg clientClose();

	/**
	 * Format: | Header | Usage - sent by the client to indicate back pressure
	 * for a streaming response
	 */
	byte	CLIENT_BACK_PRESSURE			= 19;

	Msg clientBackPressure();

	/**
	 * Format: | Header | serialized data | Usage - sent by server to pass a
	 * data event to the client
	 */
	byte	SERVER_DATA_EVENT				= 20;

	Msg serverDataEvent(int callId, Object serializedData);

	/**
	 * Format: | Header | Usage - sent by server to indicate that a streaming
	 * response should be closed
	 */
	byte	SERVER_CLOSE_EVENT				= 21;

	Msg serverCloseEvent(int callId);

	/**
	 * Format: | Header | serialized failure | Usage - sent by server to
	 * indicate that a streaming response should be failed
	 */
	byte	SERVER_ERROR_EVENT				= 22;

	Msg serverErrorEvent(int callId, Object failure);
}
