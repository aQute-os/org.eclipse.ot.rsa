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
package org.eclipse.ot.rsa.distribution.provider.serialize;

import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializerFactory;
import org.eclipse.ot.rsa.distribution.provider.serialize.java.JavaSerializerFactory;
import org.eclipse.ot.rsa.distribution.provider.serialize.protobuf.ProtobufSerializerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum SerializationType {
	FAST_BINARY(new VanillaRMISerializerFactory()),
	DEFAULT_JAVA_SERIALIZATION(new JavaSerializerFactory()),
	PROTOCOL_BUFFERS(new ProtobufSerializerFactory());

	final static Logger				logger	= LoggerFactory.getLogger(SerializationType.class);

	private final SerializerFactory factory;

	SerializationType(SerializerFactory factory) {
		this.factory = factory;
	}

	public SerializerFactory getFactory() {
		return factory;
	}

	public static SerializationType of(String type) {
		try {
			if (type == null || type.isEmpty())
				return FAST_BINARY;
			return SerializationType.valueOf(type);

		} catch (Exception e) {
			throw new IllegalArgumentException("unknown serialization: " + type);
		}
	}
}
