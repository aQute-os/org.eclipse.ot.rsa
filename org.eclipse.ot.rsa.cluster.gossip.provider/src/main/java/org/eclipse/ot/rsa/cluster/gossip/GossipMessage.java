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
package org.eclipse.ot.rsa.cluster.gossip;

import org.eclipse.ot.rsa.cluster.gossip.v1.messages.MessageType;

import io.netty.buffer.ByteBuf;

public interface GossipMessage {

	public void writeOut(ByteBuf output);

	public abstract MessageType getType();

	public int estimateSize();

}