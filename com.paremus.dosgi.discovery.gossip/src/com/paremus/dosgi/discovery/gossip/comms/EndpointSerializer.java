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
package com.paremus.dosgi.discovery.gossip.comms;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.osgi.framework.Version;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EndpointSerializer {

	/**
	 * V_1 format :
	 * V_1 id as a single byte
	 * A four byte int as the size of the Map
	 * Each entry is a UTF-8 String Key, followed by a one byte type indicator
	 * and then the bytes for that type
	 */
	
	private static final Logger logger = LoggerFactory.getLogger(EndpointSerializer.class);
	
	private static final byte V_1 = 1;

	/* TYPE CODES */
	private static final byte NULL = 0;
	private static final byte BOOLEAN = 1;
	private static final byte BYTE = 2;
	private static final byte SHORT = 3;
	private static final byte CHAR = 4;
	private static final byte INT = 5;
	private static final byte FLOAT = 6;
	private static final byte LONG = 7;
	private static final byte DOUBLE = 8;
	private static final byte STRING = 9;
	private static final byte VERSION = 10;
	private static final byte COLLECTION = 11;
	private static final byte MAP = 12;
	private static final byte PRIMITIVE_ARRAY = 13;
	private static final byte ARRAY = 14;
	
	public static void serialize(EndpointDescription ed, DataOutput dos) {
		Map<String, Object> endpointProperties = ed.getProperties();
		try {
			dos.writeByte(V_1);
			dos.writeInt(endpointProperties.size());
		} catch (IOException ioe) {
			throw new IllegalArgumentException(ioe);
		}
		
		endpointProperties.forEach((k,v) -> {
			try {
				dos.writeUTF(k);
			} catch (IOException ioe) {
				throw new IllegalArgumentException("Unable to serialize: " + k, ioe);
			}
			safeWriteType(v, dos);
		});
	}

	private static void safeWriteType(Object v, DataOutput dos) {
		try {
			writeType(v, dos);
		} catch (IOException ioe) {
			throw new IllegalArgumentException("Unable to serialize: " + v, ioe);
		}
	}
	
	private static void writeType(Object v, DataOutput dos) throws IOException {
		
		if(v == null) {
			dos.writeByte(NULL);
			return;
		}
		
		if(v instanceof Boolean) {
			dos.writeByte(BOOLEAN);
			dos.writeBoolean((Boolean) v);
			return;
		}

		if(v instanceof Byte) {
			dos.writeByte(BYTE);
			dos.writeByte((Byte) v);
			return;
		}

		if(v instanceof Short) {
			dos.writeByte(SHORT);
			dos.writeShort((Short) v);
			return;
		}

		if(v instanceof Character) {
			dos.writeByte(CHAR);
			dos.writeChar(((Character) v).charValue());
			return;
		}
		
		if(v instanceof Integer) {
			dos.writeByte(INT);
			dos.writeInt((Integer) v);
			return;
		}

		if(v instanceof Float) {
			dos.writeByte(FLOAT);
			dos.writeFloat((Float) v);
			return;
		}
		
		if(v instanceof Long) {
			dos.writeByte(LONG);
			dos.writeLong((Long) v);
			return;
		}

		if(v instanceof Double) {
			dos.writeByte(DOUBLE);
			dos.writeDouble((Double) v);
			return;
		}

		if(v instanceof String) {
			dos.writeByte(STRING);
			dos.writeUTF((String) v);
			return;
		}

		if(v instanceof Version) {
			dos.writeByte(VERSION);
			dos.writeUTF(v.toString());
			return;
		}

		if(v instanceof Collection) {
			dos.writeByte(COLLECTION);
			Collection<?> c = (Collection<?>)v;
			dos.writeInt(c.size());
			c.forEach(o -> safeWriteType(o, dos));
			return;
		}

		if(v instanceof Map) {
			dos.writeByte(MAP);
			Map<?, ?> m = (Map<?, ?>)v;
			dos.writeInt(m.size());
			m.forEach((key, val) -> {
				safeWriteType(key, dos);
				safeWriteType(val, dos);
			});
			return;
		}
		
		if(v.getClass().isArray()) {
			Class<?> componentType = v.getClass().getComponentType();
			boolean primitive = componentType.isPrimitive();
			dos.writeByte(primitive ? PRIMITIVE_ARRAY : ARRAY);
			writeTypeOnly(componentType, dos);
			int length = Array.getLength(v);
			dos.writeInt(length);
			
			for(int i =0; i < length; i++) {
				writeType(Array.get(v, i), dos);
			}
			return;
		}
		
		if(logger.isInfoEnabled()) {
			logger.info("Unable to serialize the value {} of type {}. It will be treated as a String.", v, v.getClass());
		}
		
		dos.writeByte(STRING);
		dos.writeUTF(v.toString());
	}

	private static void writeTypeOnly(Class<?> componentType, DataOutput dos) throws IOException {
		
		if(componentType.isArray()) {
			Class<?> nestedComponentType = componentType.getComponentType();
			boolean primitive = nestedComponentType.isPrimitive();
			dos.writeByte(primitive ? PRIMITIVE_ARRAY : ARRAY);
			writeType(nestedComponentType, dos);
			return;
		}
		
		if(Boolean.class == componentType || boolean.class == componentType) {
			dos.writeByte(BOOLEAN);
			return;
		}
		if(Byte.class == componentType || byte.class == componentType) {
			dos.writeByte(BYTE);
			return;
		}
		if(Short.class == componentType || short.class == componentType) {
			dos.writeByte(SHORT);
			return;
		}
		if(Character.class == componentType || char.class == componentType) {
			dos.writeByte(CHAR);
			return;
		}
		if(Integer.class == componentType || int.class == componentType) {
			dos.writeByte(INT);
			return;
		}
		if(Float.class == componentType || float.class == componentType) {
			dos.writeByte(FLOAT);
			return;
		}
		if(Long.class == componentType || long.class == componentType) {
			dos.writeByte(LONG);
			return;
		}
		if(Double.class == componentType || double.class == componentType) {
			dos.writeByte(DOUBLE);
			return;
		}
		if(String.class == componentType) {
			dos.writeByte(STRING);
			return;
		}
		if(Version.class == componentType) {
			dos.writeByte(VERSION);
			return;
		}
		dos.writeByte(NULL);
	}

	public static EndpointDescription deserializeEndpoint(DataInput input) {
		try {
			int version = input.readByte();
			if(version != V_1) {
				throw new IllegalArgumentException("Version " + version + " is not supported");
			}
			
			int size = input.readInt();
			Map<String, Object> props = new HashMap<String, Object>();
			for(int i = 0; i < size; i++) {
				props.put(input.readUTF(), readType(input));
			}
			
			return new EndpointDescription(props);
		} catch (IOException ioe) {
			throw new IllegalArgumentException(ioe);
		}
	}

	private static Object readType(DataInput input) throws IOException {
		byte type = input.readByte();
		switch(type) {
			case NULL:
				return null;
			case BOOLEAN:
				return input.readBoolean();
			case BYTE:
				return input.readByte();
			case SHORT:
				return input.readShort();
			case CHAR:
				return input.readChar();
			case INT:
				return input.readInt();
			case FLOAT:
				return input.readFloat();
			case LONG:
				return input.readLong();
			case DOUBLE:
				return input.readDouble();
			case STRING:
				return input.readUTF();
			case VERSION:
				return Version.parseVersion(input.readUTF());
			case COLLECTION: {
				 int size = input.readInt();
				 Collection<Object> c = new ArrayList<>();
				 for(int i = 0; i < size; i++) {
					 c.add(readType(input));
				 }
				 return c;
			}
			case MAP: {
				int size = input.readInt();
				Map<Object, Object> c = new LinkedHashMap<>();
				for(int i = 0; i < size; i++) {
					c.put(readType(input), readType(input));
				}
				return c;
			}
			case ARRAY: {
				Class<?> componentType = determineComponentType(input);
				int length = input.readInt();
				
				Object array = Array.newInstance(componentType, length);
				for(int i = 0; i < length; i++) {
					Array.set(array, i, readType(input));
				}
				return array;
			}
			case PRIMITIVE_ARRAY: {
				byte typeCode = input.readByte();
				int length = input.readInt();
				Object array = createPrimitiveArray(typeCode, length);
				for(int i = 0; i < length; i++) {
					Array.set(array, i, readType(input));
				}
				return array;
			}
			default :
				throw new IllegalArgumentException("Unrecognized type " + type);
		}
	}

	private static Class<?> determineComponentType(DataInput input) throws IOException {
		byte typeCode = input.readByte();
		
		switch (typeCode) {
			case PRIMITIVE_ARRAY:
				return createPrimitiveArray(input.readByte(), 0).getClass();
			case ARRAY:
				return Array.newInstance(determineComponentType(input), 0).getClass();
			default:
				return getType(typeCode);
		}
	}

	private static Class<?> getType(byte typeCode) {
		Class<?> type;
		switch(typeCode) {
		case NULL:
			type = Object.class;
			break;
		case BOOLEAN:
			type = Boolean.class;
			break;
		case BYTE:
			type = Byte.class;
			break;
		case SHORT:
			type = Short.class;
			break;
		case CHAR:
			type = Character.class;
			break;
		case INT:
			type = Integer.class;
			break;
		case FLOAT:
			type = Float.class;
			break;
		case LONG:
			type = Long.class;
			break;
		case DOUBLE:
			type = Double.class;
			break;
		case STRING:
			type = String.class;
			break;
		case VERSION:
			type = Version.class;
			break;
		case COLLECTION:
			type = Collection.class;
			break;
		case MAP:
			type = Map.class;
			break;
		default:
			throw new IllegalArgumentException("Not a known non-array type code: " + typeCode);
		}
		return type;
	}

	private static Object createPrimitiveArray(byte typeCode, int length) {
		Class<?> type;
		switch(typeCode) {
			case NULL:
				type = Object.class;
				break;
			case BOOLEAN:
				type = boolean.class;
				break;
			case BYTE:
				type = byte.class;
				break;
			case SHORT:
				type = short.class;
				break;
			case CHAR:
				type = char.class;
				break;
			case INT:
				type = int.class;
				break;
			case FLOAT:
				type = float.class;
				break;
			case LONG:
				type = long.class;
				break;
			case DOUBLE:
				type = double.class;
				break;
			default:
				throw new IllegalArgumentException("Not a primitive type code: " + typeCode);
		}
		return Array.newInstance(type, length);
	}

}
