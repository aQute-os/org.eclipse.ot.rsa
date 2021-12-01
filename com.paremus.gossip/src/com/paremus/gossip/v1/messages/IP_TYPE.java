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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

enum IP_TYPE {
	IPV4 {
		@Override
		InetAddress getInetAddress(DataInput input) throws IOException {
			byte[] address = new byte[4];
			input.readFully(address);
			return InetAddress.getByAddress(address);
		}
	},
	IPV6 {
		@Override
		InetAddress getInetAddress(DataInput input) throws IOException {
			byte[] address = new byte[16];
			input.readFully(address);
			return InetAddress.getByAddress(address);
		}
	},
	HOSTNAME {
		@Override
		InetAddress getInetAddress(DataInput input) throws IOException {
			String address = input.readUTF();
			return InetAddress.getByName(address);
		}
		
		@Override
		void writeOut(InetSocketAddress address, DataOutput output) throws IOException {
			output.writeByte(ordinal());
			output.writeUTF(address.getHostName());
			output.writeShort(address.getPort());
		}
	}, UNKNOWN {

		@Override
		InetAddress getInetAddress(DataInput input) throws IOException,
				UnknownHostException {
			return null;
		}
		
		@Override
		void writeOut(InetSocketAddress address, DataOutput output) throws IOException {
			output.writeByte(ordinal());
		}
	};

	abstract InetAddress getInetAddress(DataInput input) throws IOException, UnknownHostException;
	
	static InetAddress fromDataInput(DataInput input) throws UnknownHostException, IOException {
		return values()[input.readUnsignedByte()].getInetAddress(input);
	}
	
	static IP_TYPE fromInetSocketAddress(InetSocketAddress address) {
		if(address == null || address.getAddress() == null) {
			return UNKNOWN;
		} 
		InetAddress inetAddress = address.getAddress();
		if(inetAddress.toString().startsWith("/")) {
			if(inetAddress instanceof Inet4Address) {
				return IPV4;
			} else if (inetAddress instanceof Inet6Address) {
				return IPV6;
			} else {
				throw new IllegalArgumentException("Unknown address type " + address);
			}
		} else {
			return HOSTNAME;
		}
	}

	void writeOut(InetSocketAddress address, DataOutput output) throws IOException {
		output.writeByte(ordinal());
		output.write(address.getAddress().getAddress());
		output.writeShort(address.getPort());
	}
}