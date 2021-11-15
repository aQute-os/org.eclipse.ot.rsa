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
package com.paremus.net.encode;

public class MissingEncryptionDetailsException extends RuntimeException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 2093646234485826361L;

	public MissingEncryptionDetailsException(String message, Throwable cause) {
		super(message, cause);
		// TODO Auto-generated constructor stub
	}

	public MissingEncryptionDetailsException(String message) {
		super(message);
		// TODO Auto-generated constructor stub
	}

	public MissingEncryptionDetailsException(Throwable cause) {
		super(cause);
		// TODO Auto-generated constructor stub
	}

}
