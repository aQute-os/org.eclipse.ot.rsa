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
package com.paremus.dosgi.net.message;

import java.util.UUID;

import com.paremus.dosgi.net.serialize.Serializer;

public abstract class AbstractPayloadMessage<M extends MessageType> extends AbstractRSAMessage<M> {
	
	private final Serializer serializer;
	
	public AbstractPayloadMessage(M type, UUID serviceId, int callId,
			Serializer serializer) {
		super(type, serviceId, callId);
		this.serializer = serializer;
	}
	
	public final Serializer getSerializer() {
		return serializer;
	}
}
