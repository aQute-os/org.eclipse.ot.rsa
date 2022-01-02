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
package com.paremus.gossip.v1.messages;
import static com.paremus.gossip.v1.messages.MessageType.FIRST_CONTACT_RESPONSE;

import java.util.Objects;

import io.netty.buffer.ByteBuf;


public class FirstContactResponse extends AbstractGossipMessage {
	
	private final Snapshot firstContactInfo;
	
	public FirstContactResponse(String clusterName, Snapshot snapshot, Snapshot receivedFrom) {
		super(clusterName, snapshot);
		Objects.nonNull(receivedFrom);
		this.firstContactInfo = receivedFrom;
	}
	
	public FirstContactResponse(final ByteBuf input) {
		super(input);
		firstContactInfo = new Snapshot(input);
	}
	
	public void writeOut(ByteBuf output) {
		super.writeOut(output);
		firstContactInfo.writeOut(output);
	}

	public Snapshot getFirstContactInfo() {
		return firstContactInfo;
	}

	@Override
	public MessageType getType() {
		return FIRST_CONTACT_RESPONSE;
	}
	
	@Override
	public int estimateSize() {
		return super.estimateSize() + firstContactInfo.guessSize();
	}
}
