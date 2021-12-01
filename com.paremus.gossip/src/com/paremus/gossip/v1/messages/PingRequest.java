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
import static com.paremus.gossip.v1.messages.MessageType.PING_REQUEST;

import java.io.DataInput;
import java.io.DataOutput;

public class PingRequest extends AbstractGossipMessage {
	
	public PingRequest(String clusterName, Snapshot snapshot) {
		super(clusterName, snapshot);
	}
	
	public PingRequest(final DataInput input) {
		super(input);
	}
	
	public void writeOut(DataOutput output) {
		super.writeOut(output);
	}
	
	@Override
	public MessageType getType() {
		return PING_REQUEST;
	}
}
