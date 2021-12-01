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
package com.paremus.dosgi.net.proxy;

import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITH_RETURN;

import org.osgi.util.promise.Promise;

public interface MethodCallHandler {

	public enum CallType {
		WITH_RETURN(CALL_WITH_RETURN), FIRE_AND_FORGET(CALL_WITHOUT_RETURN);
		
		private final byte command;

		private CallType(byte command) {
			this.command = command;
		}

		public byte getCommand() {
			return command;
		}
	}
	
	Promise<? extends Object> call(CallType type, int method, Object[] args, int timeout);
	
}
