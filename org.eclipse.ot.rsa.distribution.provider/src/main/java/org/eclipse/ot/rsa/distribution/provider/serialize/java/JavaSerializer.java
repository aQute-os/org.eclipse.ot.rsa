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
package org.eclipse.ot.rsa.distribution.provider.serialize.java;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.osgi.framework.Bundle;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;

public class JavaSerializer implements Serializer {

	interface LoadClass {
		Class<?> loadClass(String name) throws ClassNotFoundException;
	}

	private final LoadClass classSpace;

	public JavaSerializer(LoadClass classSpace) {
		this.classSpace = classSpace;
	}

	public JavaSerializer(Bundle bundle) {
		this(bundle::loadClass);
	}

	public JavaSerializer(ClassLoader loader) {
		this(loader::loadClass);
	}

	public JavaSerializer(Class<?> loader) {
		this(loader.getClassLoader()::loadClass);
	}

	public JavaSerializer(Object object) {
		this(object.getClass()
			.getClassLoader()::loadClass);
	}

	public JavaSerializer() {
		this(JavaSerializer.class.getClassLoader()::loadClass);
	}

	@Override
	public void serializeArgs(ByteBuf buffer, Object... o) throws IOException {
		serializeReturn(buffer, o);
	}

	@Override
	public void serializeReturn(ByteBuf buffer, Object o) throws IOException {
		serialzeWithJava(new ByteBufOutputStream(buffer), o);
	}

	public static void serialzeWithJava(ByteBufOutputStream bbos, Object o) throws IOException {
		try (ObjectOutputStream oos = new ObjectOutputStream(bbos)) {
			oos.writeObject(o);
		}
	}

	@Override
	public Object[] deserializeArgs(ByteBuf buffer) throws ClassNotFoundException, IOException {
		return (Object[]) deserializeReturn(buffer);
	}

	@Override
	public Object deserializeReturn(ByteBuf buffer) throws ClassNotFoundException, IOException {
		try (ObjectInputStream ois = new ObjectInputStream(new ByteBufInputStream(buffer)) {
			@Override
			protected Class<?> resolveClass(ObjectStreamClass arg0) throws IOException, ClassNotFoundException {

				try {
					return classSpace.loadClass(arg0.getName());
				} catch (ClassNotFoundException e) {
					return super.resolveClass(arg0);
				}
			}
		}) {
			return ois.readObject();
		}
	}

	@Override
	public String toString() {
		return "java";
	}
}
