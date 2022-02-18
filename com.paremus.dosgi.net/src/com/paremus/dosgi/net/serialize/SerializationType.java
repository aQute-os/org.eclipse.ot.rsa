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
package com.paremus.dosgi.net.serialize;

import com.paremus.dosgi.net.serialize.freshvanilla.VanillaRMISerializerFactory;
import com.paremus.dosgi.net.serialize.java.JavaSerializerFactory;
import com.paremus.dosgi.net.serialize.protobuf.ProtobufSerializerFactory;

public enum SerializationType {

	FAST_BINARY(new VanillaRMISerializerFactory()),
	DEFAULT_JAVA_SERIALIZATION(new JavaSerializerFactory()),
	PROTOCOL_BUFFERS(new ProtobufSerializerFactory());

	private final SerializerFactory factory;

	private SerializationType(SerializerFactory factory) {
		this.factory = factory;
	}

	public SerializerFactory getFactory() {
		return factory;
	}
}
