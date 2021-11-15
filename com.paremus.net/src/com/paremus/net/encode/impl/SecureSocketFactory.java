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
package com.paremus.net.encode.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class SecureSocketFactory extends SocketFactory {

	private final SSLSocketFactory delegate;
	private final SSLParameters sslParameters;
	
	public SecureSocketFactory(SSLSocketFactory delegate, SSLParameters sslParameters) {
		this.delegate = delegate;
		this.sslParameters = sslParameters;
	}

	@Override
	public Socket createSocket() throws IOException {
		SSLSocket socket = (SSLSocket) delegate.createSocket();
		socket.setSSLParameters(sslParameters);
		return socket;
	}

	@Override
	public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
		Socket socket = createSocket();
		socket.connect(new InetSocketAddress(host, port));
		return socket;
	}

	@Override
	public Socket createSocket(InetAddress host, int port) throws IOException {
		Socket socket = createSocket();
		socket.connect(new InetSocketAddress(host, port));
		return socket;
	}

	@Override
	public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
			throws IOException, UnknownHostException {
		Socket socket = createSocket();
		socket.bind(new InetSocketAddress(localHost, localPort));
		socket.connect(new InetSocketAddress(host, port));
		return socket;
	}

	@Override
	public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
			throws IOException {
		Socket socket = createSocket();
		socket.bind(new InetSocketAddress(localAddress, localPort));
		socket.connect(new InetSocketAddress(address, port));
		return socket;
	}
	
}