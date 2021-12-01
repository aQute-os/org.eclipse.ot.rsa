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
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;


public abstract class AbstractGossipMessage {
	
	private final String clusterName;
	
	private Snapshot update;
	
	public AbstractGossipMessage(String clusterName, Snapshot snapshot) {
		Objects.nonNull(snapshot);
		this.clusterName = clusterName;
		this.update = snapshot;
	}
	
	public AbstractGossipMessage(final DataInput input) {
		try {
			clusterName = input.readUTF();
			update = new Snapshot(input);
		} catch (IOException ioe) {
			throw new RuntimeException("Failed to read message", ioe);
		}
	}
	
	public void writeOut(DataOutput output) {
		try {
			output.writeUTF(clusterName);
			update.writeOut(output);
		} catch (IOException ioe) {
			throw new RuntimeException("Failed to write message", ioe);
		}
	}

	public String getClusterName() {
		return clusterName;
	}
	
	public Snapshot getUpdate(InetSocketAddress sentFrom) {
		return new Snapshot(update, sentFrom);
	}
	
	public abstract MessageType getType();
}
