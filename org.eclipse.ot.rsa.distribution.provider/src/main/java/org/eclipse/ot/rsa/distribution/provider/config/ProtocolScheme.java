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
package org.eclipse.ot.rsa.distribution.provider.config;

import static java.lang.String.format;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtocolScheme {

	private static final Logger			LOG						= LoggerFactory.getLogger(ProtocolScheme.class);

	private final Pattern				COLON					= Pattern.compile(":");

	private final Protocol				protocol;

	private final int					receiveBufferSize;

	private final int					sendBufferSize;

	private final InetSocketAddress		bindAddress;

	private final Map<String, Integer>	toAdvertise				= new LinkedHashMap<>();

	private final Map<String, String>	specificSocketOptions	= new HashMap<>();

	private final String				configuration;

	public ProtocolScheme(String spec) {
		this.configuration = spec;

		String[] stanzas = spec.split(";");

		protocol = Protocol.valueOf(stanzas[0]);

		int portDef = 0;
		int rcvBuf = 1 << 18;
		int sendBuf = 1 << 18;
		InetSocketAddress bindAddress = null;

		for (int i = 1; i < stanzas.length; i++) {
			String[] stanza = stanzas[i].split("=", 2);
			switch (stanza[0]) {
				case "port" :
					portDef = Integer.valueOf(stanza[1]);
					if (portDef < 0 || portDef > 65535) {
						throw new IllegalArgumentException("" + portDef + " is not a valid port number");
					}
					continue;
				case "bind" :
					try {
						bindAddress = handleAddress(spec, stanza[1]);
						InetAddress address = bindAddress.getAddress();
						if (NetworkInterface.getByInetAddress(address) == null && !address.isAnyLocalAddress()) {
							throw new IllegalArgumentException(
								"The bind address " + stanza[1] + " is not local to this machine.");
						}
					} catch (Exception e) {
						throw new IllegalArgumentException("Unable to bind to the address " + stanza[1], e);
					}
					continue;
				case "advertise" :
					InetSocketAddress isa = handleAddress(spec, stanza[1]);
					toAdvertise.put(isa.getHostString(), isa.getPort());
					continue;
				default :
					specificSocketOptions.put(stanza[0], stanza[1]);
			}
		}

		receiveBufferSize = rcvBuf;
		sendBufferSize = sendBuf;
		this.bindAddress = bindAddress;
	}

	private InetSocketAddress handleAddress(String spec, String value) {

		int portDelimiter = value.lastIndexOf(':');

		Matcher colonCounter = COLON.matcher(value);

		boolean hasMultipleColons = colonCounter.find() && colonCounter.find();

		boolean hasPort;
		if (value.startsWith("[")) {
			// IPv6
			int endOfAddress = value.lastIndexOf(']');
			hasPort = portDelimiter > endOfAddress;
		} else if (hasMultipleColons) {
			LOG.warn(
				"The protocol definition {} contains an address {} which uses IPV6 notation but is not surrounded by \"[]\". No port information can be detected using this syntax and it should be avoided.",
				spec, value);
			hasPort = false;
		} else {
			hasPort = portDelimiter > 0;
		}

		try {
			int port;
			String address;
			if (hasPort) {
				port = Integer.parseInt(value.substring(portDelimiter + 1));
				if (port < 0 || port > 65535) {
					LOG.error("The protocol definition {} contains an address {} which declares an invalid port", spec,
						value);
					throw new IllegalArgumentException("The " + port + " is not a valid port number");
				}
				address = value.substring(0, portDelimiter);
			} else {
				port = 0;
				address = value;
			}

			// Check for valid syntax when creating
			return new InetSocketAddress(InetAddress.getByName(address), port);
		} catch (Exception e) {
			throw new RuntimeException("The protocol specification " + spec + " contains an invalid clause " + value);
		}
	}

	public Protocol getProtocol() {
		return protocol;
	}

	public int getSendBufferSize() {
		return sendBufferSize;
	}

	public InetSocketAddress getBindAddress() {
		return bindAddress;
	}

	public Map<String, Integer> getAddressesToAdvertise() {
		return new LinkedHashMap<>(toAdvertise);
	}

	public int getReceiveBufferSize() {
		return receiveBufferSize;
	}

	@SuppressWarnings("unchecked")
	public <T> T getOption(String option, Class<T> clazz) {

		String value = specificSocketOptions.get(option);
		if (value == null) {
			return null;
		} else if (clazz.isInstance(value)) {
			return clazz.cast(value);
		} else {
			try {
				Method m = clazz.getMethod("valueOf", String.class);
				if (Modifier.isStatic(m.getModifiers())) {
					return (T) m.invoke(null, value);
				}
			} catch (NoSuchMethodException nsme) {
				// Look for a constructor instead
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
