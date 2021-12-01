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
import static com.paremus.gossip.v1.messages.MessageType.FORWARDABLE;
import static java.util.stream.Collectors.toList;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;


public class ForwardableGossipMessage extends AbstractGossipMessage {
	
	/**
	 * What are the other Snapshots from this message?
	 */
	private final List<Snapshot> forwards;
	
	public ForwardableGossipMessage(final DataInput input) {
		super(input);
		try {
			final int size = input.readUnsignedByte();
			
			forwards = new ArrayList<>(size);
			for(int i=0; i < size; i++) {
				forwards.add(new Snapshot(input));
			}
		} catch (IOException ioe) {
			throw new RuntimeException("Failed to read message", ioe);
		}
	}
	
	public ForwardableGossipMessage(String clusterName, Snapshot snapshot, List<Snapshot> forwards) {
		super(clusterName, snapshot);
		this.forwards = forwards;
	}	

	public void writeOut(DataOutput output) {
		try {
			super.writeOut(output);
			output.writeByte(forwards.size());
		} catch (IOException ioe) {
			throw new RuntimeException("Failed to write snapshot", ioe);
		}
		
		forwards.forEach((s) -> s.writeOut(output));
	}

	public List<Snapshot> getAllSnapshots(InetSocketAddress sentFrom) {
		List<Snapshot> toReturn = forwards.stream().map(Snapshot::new).collect(toList());
		toReturn.add(getUpdate(sentFrom));
		return toReturn;
	}
	
	@Override
	public MessageType getType() {
		return FORWARDABLE;
	}
}
