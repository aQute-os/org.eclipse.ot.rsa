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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import io.netty.buffer.ByteBuf;

enum IP_TYPE {
	IPV4 {
		@Override
		InetAddress getInetAddress(ByteBuf input) throws IOException {
			byte[] address = new byte[4];
			input.readBytes(address);
			return InetAddress.getByAddress(address);
		}
	},
	IPV6 {
		@Override
		InetAddress getInetAddress(ByteBuf input) throws IOException {
			byte[] address = new byte[16];
			input.readBytes(address);
			return InetAddress.getByAddress(address);
		}
	},
	HOSTNAME {
		@Override
		InetAddress getInetAddress(ByteBuf input) throws IOException {
			String address = input.readCharSequence(input.readUnsignedShort(), UTF_8)
				.toString();
			return InetAddress.getByName(address);
		}

		@Override
		void writeOut(InetSocketAddress address, ByteBuf output) throws IOException {
			output.writeByte(ordinal());
			AbstractGossipMessage.writeUTF8(output, address.getHostName());
			output.writeShort(address.getPort());
		}
	},
	UNKNOWN {

		@Override
		InetAddress getInetAddress(ByteBuf input) throws IOException, UnknownHostException {
			return null;
		}

		@Override
		void writeOut(InetSocketAddress address, ByteBuf output) throws IOException {
			output.writeByte(ordinal());
		}
	};

	abstract InetAddress getInetAddress(ByteBuf input) throws IOException, UnknownHostException;

	static InetAddress fromDataInput(ByteBuf input) throws UnknownHostException, IOException {
		return values()[input.readUnsignedByte()].getInetAddress(input);
	}

	static IP_TYPE fromInetSocketAddress(InetSocketAddress address) {
		if (address == null || address.getAddress() == null) {
			return UNKNOWN;
		}
		InetAddress inetAddress = address.getAddress();
		if (inetAddress.toString()
			.startsWith("/")) {
			if (inetAddress instanceof Inet4Address) {
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

	void writeOut(InetSocketAddress address, ByteBuf output) throws IOException {
		output.writeByte(ordinal());
		output.writeBytes(address.getAddress()
			.getAddress());
		output.writeShort(address.getPort());
	}
}
