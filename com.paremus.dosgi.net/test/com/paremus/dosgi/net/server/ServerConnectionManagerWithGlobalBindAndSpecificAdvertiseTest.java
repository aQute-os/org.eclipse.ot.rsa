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
package com.paremus.dosgi.net.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class ServerConnectionManagerWithGlobalBindAndSpecificAdvertiseTest extends AbstractServerConnectionManagerTest {

	protected Map<String, Object> getExtraConfig() {
		Map<String, Object> toReturn = new HashMap<String, Object>();
		toReturn.put("allow.insecure.transports", true);
		
		String ipv6 = "TCP;bind=[::];advertise=localhost";
		String ipv4 = "TCP;bind=127.0.0.1;advertise=localhost";

		toReturn.put("server.protocols", selectProtocol(ipv6, ipv4));
		return toReturn;
	}

	protected ByteChannel getCommsChannel(URI uri) {
		
		try {
			SocketChannel sc = SocketChannel.open();
			sc.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
			sc.configureBlocking(false);
			return sc;
		} catch (IOException e) {
			throw new RuntimeException();
		}
	}
	
	@Test
	public void checkURI() {
		assertEquals("localhost", serviceUri.getHost());
	}
}
