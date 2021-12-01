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
package com.paremus.dosgi.net.wireformat;

import java.lang.reflect.Method;

public class Protocol_V1 {

	public static final byte VERSION = 1;
	
	/** 
	 * The standard message header format is as follows:
	 * byte 0  |    byte 1-3    | byte 4  | long, long |   int   |
	 * Version | message length | Command | Service ID | Call ID |
	 * 
	 */
	
	/** Commands */
	
	/** Header | method signature String | serialized args | */
	public static final byte CALL_WITH_RETURN = 1;
	/** Header | method signature String | serialized args | */
	public static final byte CALL_WITHOUT_RETURN = 2;
	/** Header | boolean */
	public static final byte CANCEL = 3;
	/** Header | serialized response */
	public static final byte SUCCESS_RESPONSE = 4;
	/** Header | serialized failure */
	public static final byte FAILURE_RESPONSE = 5;
	/** Header only */
	public static final byte FAILURE_NO_SERVICE = 6;
	/** Header only */
	public static final byte FAILURE_NO_METHOD = 7;
	/** Header | String message */
	public static final byte FAILURE_TO_DESERIALIZE = 8;
	/** Header only */
	public static final byte FAILURE_TO_SERIALIZE_SUCCESS = 9;
	/** Header only */
	public static final byte FAILURE_TO_SERIALIZE_FAILURE = 10;
	/** Header | String message */
	public static final byte FAILURE_SERVER_OVERLOADED = 11;
	/** Header | String message */
	public static final byte FAILURE_UNKNOWN = 12;
	
	
	public static String toSignature(Method m) {
		StringBuilder sb = new StringBuilder(m.getName());
    	sb.append('[');
    	boolean hasParameters = false;
    	for(Class<?> clazz : m.getParameterTypes()) {
    		sb.append(clazz.getName()).append(",");
    		hasParameters = true;
    	}
    	if(hasParameters) {
    		sb.deleteCharAt(sb.length() - 1);
    	}
    	return sb.append(']').toString();
	}


	public static final int SIZE_WIDTH_IN_BYTES = 3;
}
