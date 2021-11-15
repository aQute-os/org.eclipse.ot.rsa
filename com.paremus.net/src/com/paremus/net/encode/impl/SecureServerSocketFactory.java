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
import java.net.ServerSocket;
import java.net.UnknownHostException;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

public class SecureServerSocketFactory extends ServerSocketFactory {

	private final SSLServerSocketFactory delegate;
	private final SSLParameters sslParameters;
	
	public SecureServerSocketFactory(SSLServerSocketFactory delegate, SSLParameters sslParameters) {
		this.delegate = delegate;
		this.sslParameters = sslParameters;
	}

	@Override
	public ServerSocket createServerSocket() throws IOException {
		SSLServerSocket socket = (SSLServerSocket) delegate.createServerSocket();
		socket.setSSLParameters(sslParameters);
		
		return socket;
	}

	@Override
	public ServerSocket createServerSocket(int port) throws IOException, UnknownHostException {
		ServerSocket socket = createServerSocket();
		socket.bind(new InetSocketAddress(port));
		return socket;
	}

	@Override
	public ServerSocket createServerSocket(int port, int backlog)
			throws IOException, UnknownHostException {
		ServerSocket socket = createServerSocket();
		socket.bind(new InetSocketAddress(port), backlog);
		return socket;
	}

	@Override
	public ServerSocket createServerSocket(int port, int backlog, InetAddress bindAddress)
			throws IOException {
		ServerSocket socket = createServerSocket();
		socket.bind(new InetSocketAddress(bindAddress, port), backlog);
		return socket;
	}
	
}