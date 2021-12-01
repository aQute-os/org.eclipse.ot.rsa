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
package com.paremus.dosgi.net.config;

import static java.lang.String.format;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Map;

public class ProtocolScheme {
	
	private final Protocol protocol;
	
	private final int port;
	
	private final int receiveBufferSize;
	
	private final int sendBufferSize;

	private final InetAddress bindAddress;
	
	private final Map<String, String> specificSocketOptions = new HashMap<String, String>();

	private final String configuration;
	
	public ProtocolScheme(String spec) {
		this.configuration = spec;
		
		String[] stanzas = spec.split(";");
		
		protocol = Protocol.valueOf(stanzas[0]);
		
		int portDef = 0;
		int rcvBuf = 1 << 18;
		int sendBuf = 1 << 18;
		InetAddress bindAddress = null;
		
		for(int i = 1; i < stanzas.length; i++) {
			String[] stanza = stanzas[i].split("=",2);
			switch(stanza[0]) {
				case "port" :
					portDef = Integer.valueOf(stanza[1]);
					if(portDef < 0 || portDef > 65535)  {
						throw new IllegalArgumentException("" + portDef + " is not a valid port number");
					}
				case "bind" :
					try {
						bindAddress = InetAddress.getByName(stanza[1]);
						if(NetworkInterface.getByInetAddress(bindAddress) == null && 
								!bindAddress.isAnyLocalAddress()) {
							throw new IllegalArgumentException("The bind address " + 
									stanza[1] + " is not local to this machine.");
						}
					} catch (Exception e) {
						throw new IllegalArgumentException("Unable to bind to the address " + stanza[1], e);
					}
				default:
					specificSocketOptions.put(stanza[0], stanza[1]);
			}
		}
		
		port = portDef;
		receiveBufferSize = rcvBuf;
		sendBufferSize = sendBuf;
		this.bindAddress = bindAddress;
	}

	public Protocol getProtocol() {
		return protocol;
	}

	public int getPort() {
		return port;
	}

	public int getSendBufferSize() {
		return sendBufferSize;
	}
	
	public InetAddress getBindAddress() {
		return bindAddress;
	}

	public int getReceiveBufferSize() {
		return receiveBufferSize;
	}

	@SuppressWarnings("unchecked")
	public <T> T getOption(String option, Class<T> clazz) {
		
		String value = specificSocketOptions.get(option);
		if(value == null) {
			return null;
		} else if(clazz.isInstance(value)) {
			return clazz.cast(value);
		} else {
			try {
				Method m = clazz.getMethod("valueOf", String.class);
				if(Modifier.isStatic(m.getModifiers())) {
					return (T) m.invoke(null, value);
				}
			} catch (NoSuchMethodException nsme) {
				//Look for a constructor instead
			} catch (Exception e) {
				throw new RuntimeException(
						format("Unable to convert the property value {} for option {}", value, option), e);
			}
			try {
				Constructor<T> c = clazz.getConstructor(String.class);
				return c.newInstance(value);
			} catch (Exception e) {
				throw new RuntimeException(
						format("Unable to convert the property value {} for option {}", value, option), e);
			}
		}
	}

	public String getConfigurationString() {
		return configuration;
	}
	
}
