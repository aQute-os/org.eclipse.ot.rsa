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
package org.eclipse.ot.rsa.cluster.gossip.v1.messages;
import static org.eclipse.ot.rsa.cluster.gossip.v1.messages.MessageType.PING_REQUEST;

import io.netty.buffer.ByteBuf;

public class PingRequest extends AbstractGossipMessage {

	public PingRequest(String clusterName, Snapshot snapshot) {
		super(clusterName, snapshot);
	}

	public PingRequest(final ByteBuf input) {
		super(input);
	}

	@Override
	public void writeOut(ByteBuf output) {
		super.writeOut(output);
	}

	@Override
	public MessageType getType() {
		return PING_REQUEST;
	}
}
