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
package com.paremus.dosgi.net.client;

import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.util.Timeout;

class PendingCall {
	final FuturePromise<Object> promise;
	final Serializer serializer;
	final Timeout pendingTimeout;
	final int methodId;
	
	public PendingCall(FuturePromise<Object> promise, Serializer serializer, Timeout pendingTimeout,
			int methodId) {
		this.promise = promise;
		this.serializer = serializer;
		this.pendingTimeout = pendingTimeout;
		this.methodId = methodId;
	}
}
