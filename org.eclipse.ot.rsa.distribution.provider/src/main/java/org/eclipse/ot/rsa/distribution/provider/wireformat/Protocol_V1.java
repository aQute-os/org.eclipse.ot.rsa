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

import java.lang.reflect.Method;

public interface Protocol_V1 {

	byte	VERSION							= 1;

	/**
	 * The standard message header format is as follows:
	 *
	 * <pre>
	 * byte 0  | byte 1-3       | byte 4  | long, long | int     |
	 * Version | message length | Command | Service ID | Call ID |
	 * </pre>
	 */

	/** Commands */

	/**
	 * Format: | Header | method index short | serialized args | Usage - sent by
	 * client to indicate a method call with an expectation of a return value
	 */
	byte	CALL_WITH_RETURN				= 1;
	/**
	 * Format: | Header | method index short | serialized args | Usage - sent by
	 * client to indicate a method call with no expectation of a return value
	 */
	byte	CALL_WITHOUT_RETURN				= 2;

	/**
	 * Format: | Header | interrupt boolean | Usage - sent by client to cancel a
	 * method call. The boolean represents whether the caller should be
	 * interrupted.
	 */
	byte	CANCEL							= 3;

	/**
	 * Format: | Header | serialized response | Usage - sent by server to
	 * indicate a successful return value. Multiple responses for the same
	 * method call may occur if the response is a Streaming response.
	 */
	byte	SUCCESS_RESPONSE				= 4;

	/**
	 * Format: | Header | serialized failure | Usage - sent by server to
	 * indicate a failure including the exception
	 */
	byte	FAILURE_RESPONSE				= 5;

	/**
	 * Format: | Header | Usage - sent by server to indicate no service existed
	 * for the requested id
	 */
	byte	FAILURE_NO_SERVICE				= 6;

	/**
	 * Format: | Header | Usage - sent by server to indicate no method with the
	 * supplied id existed on the identified service
	 */
	byte	FAILURE_NO_METHOD				= 7;

	/**
	 * Format: | Header | String message | Usage - sent by server to indicate
	 * that a deserialization error occurred when processing the arguments
	 */
	byte	FAILURE_TO_DESERIALIZE			= 8;
	/**
	 * Format: | Header | Message Usage - sent by server to indicate that the
	 * success response could not be serialized
	 */
	byte	FAILURE_TO_SERIALIZE_SUCCESS	= 9;
	/**
	 * Format: | Header | Message Usage - sent by server to indicate that the
	 * failure response could not be serialized
	 */
	byte	FAILURE_TO_SERIALIZE_FAILURE	= 10;

	/**
	 * Format: | Header | String message | Usage - sent by server to indicate
	 * that no more requests can be processed at this time
	 */
	byte	FAILURE_SERVER_OVERLOADED		= 11;

	/**
	 * Format: | Header | String message Usage - sent by server to indicate an
	 * unknown failure occurred
	 */
	byte	FAILURE_UNKNOWN					= 12;

	/**
	 * Converts a java.lang.reflect.Method into a canonicalised String
	 *
	 * @param m the method
	 * @return A string identifier for the method
	 */
	static String toSignature(Method m) {
		StringBuilder sb = new StringBuilder(m.getName());
		sb.append('[');
		boolean hasParameters = false;
		for (Class<?> clazz : m.getParameterTypes()) {
			sb.append(clazz.getName())
				.append(",");
			hasParameters = true;
		}
		if (hasParameters) {
			sb.deleteCharAt(sb.length() - 1);
		}
		return sb.append(']')
			.toString();
	}

	/**
	 * The width (in bytes) of the size field in the RSA messages. This field is
	 * unsigned.
	 */
	int SIZE_WIDTH_IN_BYTES = 3;

}
