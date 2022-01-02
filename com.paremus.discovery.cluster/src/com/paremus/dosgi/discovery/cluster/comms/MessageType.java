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
package com.paremus.dosgi.discovery.cluster.comms;

public enum MessageType {
	ANNOUNCEMENT, REVOCATION, ACKNOWLEDGMENT, REMINDER, REQUEST_REANNOUNCEMENT;

	public short code() {
		return (short) ordinal();
	}
	
	public static MessageType valueOf(short code) {
		MessageType[] values = MessageType.values();
		if(code < 0 || code >= values.length) {
			throw new IllegalArgumentException("Not a valid MessageType code " + code);
		}
		return values[code];
	}
}
