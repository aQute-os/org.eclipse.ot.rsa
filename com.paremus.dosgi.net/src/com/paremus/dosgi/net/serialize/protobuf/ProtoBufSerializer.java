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
package com.paremus.dosgi.net.serialize.protobuf;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.osgi.framework.Bundle;

import com.paremus.dosgi.net.serialize.Serializer;
import com.paremus.dosgi.net.serialize.java.JavaSerializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;

public class ProtoBufSerializer implements Serializer {
	
	private static final byte NULL_TAG = 0x00;
	private static final byte BOOLEAN_TAG = 0x01;
	private static final byte BYTE_TAG = 0x02;
	private static final byte SHORT_TAG = 0x03;
	private static final byte CHARACTER_TAG = 0x04;
	private static final byte INTEGER_TAG = 0x05;
	private static final byte FLOAT_TAG = 0x06;
	private static final byte LONG_TAG = 0x07;
	private static final byte DOUBLE_TAG = 0x08;
	private static final byte STRING_TAG = 0x09;
	private static final byte BOOLEAN_ARRAY_TAG = 0x0A;
	private static final byte BYTE_ARRAY_TAG = 0x0B;
	private static final byte SHORT_ARRAY_TAG = 0x0C;
	private static final byte CHAR_ARRAY_TAG = 0x0D;
	private static final byte INT_ARRAY_TAG = 0x0E;
	private static final byte FLOAT_ARRAY_TAG = 0x0F;
	private static final byte LONG_ARRAY_TAG = 0x10;
	private static final byte DOUBLE_ARRAY_TAG = 0x11;
	private static final byte STRING_ARRAY_TAG = 0x12;
	private static final byte ARRAY_TAG = 0x13;
	private static final byte LIST_TAG = 0x14;
	private static final byte SET_TAG = 0x15;
	private static final byte SORTED_SET_TAG = 0x16;
	private static final byte MAP_TAG = 0x17;
	private static final byte SORTED_MAP_TAG = 0x18;
	private static final byte PROTOBUF_OBJECT_TAG = 0x19;
	private static final byte JAVA_OBJECT_TAG = 0x1A;
	
	private interface SimpleTypeSerializer {
		public void serialize(ByteBufOutputStream os, Object o) throws IOException;
	}
	
	private static final Map<Class<?>, SimpleTypeSerializer> SERIALIZERS;
	
	static {
		Map<Class<?>, SimpleTypeSerializer> map = new HashMap<Class<?>, SimpleTypeSerializer>();
		
		map.put(null, (os,o) -> os.write(NULL_TAG));
		map.put(Boolean.class, (os,o) -> {
				os.write(BOOLEAN_TAG);
				os.write(((Boolean)o) ? 1 : 0);
			});
		map.put(Byte.class, (os,o) -> { 
				os.write(BYTE_TAG);
				os.write((Byte)o);
			});
		map.put(Short.class, (os,o) -> {
				os.write(SHORT_TAG);
				os.writeShort((Short)o);
			});
		map.put(Character.class, (os,o) -> {
				os.write(CHARACTER_TAG);
				os.writeChar((Character)o);
			});
		map.put(Integer.class, (os,o) -> {
				os.write(INTEGER_TAG);
				os.writeInt((Integer)o);
			});
		map.put(Float.class, (os,o) -> {
				os.write(FLOAT_TAG);
				os.writeFloat((Float)o);
			});
		map.put(Long.class, (os,o) -> {
				os.write(LONG_TAG);
				os.writeLong((Long)o);
			});
		map.put(Double.class, (os,o) ->  {
				os.write(DOUBLE_TAG);
				os.writeDouble((Double)o);
			});
		map.put(String.class, (os,o) -> {
				os.write(STRING_TAG);
				os.writeUTF(o.toString());
			});

		map.put(boolean[].class, (os,o) ->  {
				os.write(BOOLEAN_ARRAY_TAG);
				os.write(((boolean[])o).length);
				for(boolean b : (boolean[])o)
					os.write(b ? 1 : 0);
			});
		map.put(byte[].class, (os,o) ->  {
				os.write(BYTE_ARRAY_TAG);
				os.write(((byte[])o).length);
				for(byte b : (byte[])o)
					os.write(b);
			});
		map.put(short[].class, (os,o) ->  {
				os.write(SHORT_ARRAY_TAG);
				os.write(((short[])o).length);
				for(short s : (short[])o)
					os.writeShort(s);
			});
		map.put(char[].class, (os,o) -> {
				os.write(CHAR_ARRAY_TAG);
				os.write(((char[])o).length);
				for(char c : (char[])o)
					os.writeChar(c);
			});
		map.put(int[].class, (os,o) -> {
				os.write(INT_ARRAY_TAG);
				os.write(((int[])o).length);
				for(int i : (int[])o)
					os.writeInt(i);
			});
		map.put(float[].class, (os,o) -> {
				os.write(FLOAT_ARRAY_TAG);
				os.write(((float[])o).length);
				for(float f : (float[])o)
					os.writeFloat(f);
			});
		map.put(long[].class, (os,o) -> {
				os.write(LONG_ARRAY_TAG);
				os.write(((long[])o).length);
				for(long l : (long[])o)
					os.writeLong(l);
			});
		map.put(double[].class, (os,o) -> {
				os.write(DOUBLE_ARRAY_TAG);
				os.write(((double[])o).length);
				for(double d : (double[])o)
					os.writeDouble(d);
			});
		map.put(String[].class, (os,o) -> {
				os.write(STRING_ARRAY_TAG);
				os.write(((String[])o).length);
				for(String s : (String[])o)
					os.writeUTF(s);
			});
		
		SERIALIZERS = Collections.unmodifiableMap(map);
	}
	
	private final Bundle classSpace;
	
	public ProtoBufSerializer(Bundle classSpace) {
		this.classSpace = classSpace;
	}

	@Override
	public void serializeArgs(ByteBuf buffer, Object[] o) throws IOException {
		ByteBufOutputStream bbos = new ByteBufOutputStream(buffer);
		bbos.writeInt(o.length);
		for(Object x : o)
			serialzeWithProtoBuf(bbos, x);
	}
	
	@Override
	public void serializeReturn(ByteBuf buffer, Object o) throws IOException {
		serialzeWithProtoBuf(new ByteBufOutputStream(buffer), o);
	}

	@Override
	public Object[] deserializeArgs(ByteBuf buffer) throws ClassNotFoundException, IOException {
		Object[] o = new Object[buffer.readInt()];
		for(int i = 0; i < o.length ; i++) 
			o[i] = deserializeWithProtoBuf(new ByteBufInputStream(buffer), classSpace);
		return o;
	}
	
	@Override
	public Object deserializeReturn(ByteBuf buffer) throws ClassNotFoundException, IOException {
		return deserializeWithProtoBuf(new ByteBufInputStream(buffer), classSpace);
	}

	public static void serialzeWithProtoBuf(ByteBufOutputStream bbos,
			Object e) throws IOException {
		
		Class<? extends Object> classType = e.getClass();

		SimpleTypeSerializer s = SERIALIZERS.get(classType);
		
		if(s != null) {
			s.serialize(bbos, e);
			return;
		} else {
			if(classType.isArray()) {
				int length = Array.getLength(e);

				bbos.write(ARRAY_TAG);
				bbos.writeUTF(classType.getComponentType().getName());
				bbos.writeInt(length);
				for(int i = 0; i < length; i++) {
					serialzeWithProtoBuf(bbos, Array.get(e, i));
				}
				return;
			} else if (Collection.class.isAssignableFrom(classType)) {
				if(e instanceof SortedSet) {
					bbos.write(SORTED_SET_TAG);
				} else if(e instanceof Set) {
					bbos.write(SET_TAG);
				} else {
					bbos.write(LIST_TAG);
				}
				bbos.writeInt(((Collection<?>)e).size());
				for(Object entry : (Collection<?>)e) {
					serialzeWithProtoBuf(bbos, entry);
				}
			} else if (Map.class.isAssignableFrom(classType)) {
				if(e instanceof SortedMap) {
					bbos.write(SORTED_MAP_TAG);
				} else {
					bbos.write(MAP_TAG);
				}
				
				bbos.writeInt(((Map<?,?>)e).size());
				for(Entry<?,?> entry : ((Map<?,?>)e).entrySet()) {
					serialzeWithProtoBuf(bbos, entry.getKey());
					serialzeWithProtoBuf(bbos, entry.getValue());
				}
			} else {
				try {
					Method m = classType.getMethod("writeTo", OutputStream.class);
					bbos.write(PROTOBUF_OBJECT_TAG);
					bbos.writeUTF(classType.getName());
					m.invoke(e, bbos);
				} catch (Exception e1) {
					//Fall back to normal Java com.paremus.dosgi.dsw.net.serialization
					bbos.write(JAVA_OBJECT_TAG);
					JavaSerializer.serialzeWithJava(bbos, e);
				}
			}
		}
	}
	
	public static Object deserializeWithProtoBuf(ByteBufInputStream bbis, Bundle classSpace) throws IOException {
		
		switch(bbis.read()) {
			case NULL_TAG :
				return null;
			case BOOLEAN_TAG :
				return bbis.read() == 1;
			case BYTE_TAG :
				return bbis.readByte();
			case SHORT_TAG :
				return bbis.readShort();
			case CHARACTER_TAG :
				return bbis.readChar();
			case INTEGER_TAG :
				return bbis.readInt();
			case FLOAT_TAG :
				return bbis.readFloat();
			case LONG_TAG :
				return bbis.readLong();
			case DOUBLE_TAG :
				return bbis.readDouble();
			case STRING_TAG :
				return bbis.readUTF();
			case BOOLEAN_ARRAY_TAG : {
				int length = bbis.readInt();
				boolean[] array = new boolean[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readByte() == 1;
				}
				return array;
			}
			case BYTE_ARRAY_TAG : {
				int length = bbis.readInt();
				byte[] array = new byte[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readByte();
				}
				return array;
			}
			case SHORT_ARRAY_TAG : {
				int length = bbis.readInt();
				short[] array = new short[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readShort();
				}
				return array;
			}
			case CHAR_ARRAY_TAG : {
				int length = bbis.readInt();
				char[] array = new char[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readChar();
				}
				return array;
			}
			case INT_ARRAY_TAG : {
				int length = bbis.readInt();
				int[] array = new int[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readInt();
				}
				return array;
			}
			case FLOAT_ARRAY_TAG : {
				int length = bbis.readInt();
				float[] array = new float[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readFloat();
				}
				return array;
			}
			case LONG_ARRAY_TAG : {
				int length = bbis.readInt();
				long[] array = new long[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readLong();
				}
				return array;
			}
			case DOUBLE_ARRAY_TAG : {
				int length = bbis.readInt();
				double[] array = new double[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readDouble();
				}
				return array;
			}
			case STRING_ARRAY_TAG : {
				int length = bbis.readInt();
				String[] array = new String[length];
				for(int i = 0; i < length; i++) {
					array[i] = bbis.readUTF();
				}
				return array;
			}
			case ARRAY_TAG : {
				String type = bbis.readUTF();
				int length = bbis.readInt();
				
				try {
					Class<? extends Object> componentType = null;
					try {
						componentType = classSpace.loadClass(type);
					} catch (ClassNotFoundException cnfe) {
						try {
							return ProtoBufSerializer.class.getClassLoader().loadClass(type);
						} catch (ClassNotFoundException cnfe2) {
							throw cnfe;
						}
					}
					Object array = Array.newInstance(componentType, length);
					for(int i = 0; i < length; i++) {
						Array.set(array, i, deserializeWithProtoBuf(bbis, classSpace));
					}
					return array;
				} catch (ClassNotFoundException e) {
					throw new IOException("Unable to deserialize", e);
				}
			}
			case LIST_TAG : {
				int length = bbis.readInt();
				List<Object> list = new ArrayList<Object>(length);
				for(int i = 0; i < length; i++) {
					list.add(deserializeWithProtoBuf(bbis, classSpace));
				}
				return list;
			}
			case SET_TAG : {
				int length = bbis.readInt();
				Set<Object> set = new HashSet<Object>(length);
				for(int i = 0; i < length; i++) {
					set.add(deserializeWithProtoBuf(bbis, classSpace));
				}
				return set;
			}
			case SORTED_SET_TAG : {
				int length = bbis.readInt();
				SortedSet<Object> set = new TreeSet<Object>();
				for(int i = 0; i < length; i++) {
					set.add(deserializeWithProtoBuf(bbis, classSpace));
				}
				return set;
			}
			case MAP_TAG : {
				int length = bbis.readInt();
				Map<Object, Object> map = new HashMap<Object, Object>(length);
				for(int i = 0; i < length; i++) {
					map.put(deserializeWithProtoBuf(bbis, classSpace), 
							deserializeWithProtoBuf(bbis, classSpace));
				}
				return map;
			}
			case SORTED_MAP_TAG : {
				int length = bbis.readInt();
				SortedMap<Object, Object> map = new TreeMap<Object, Object>();
				for(int i = 0; i < length; i++) {
					map.put(deserializeWithProtoBuf(bbis, classSpace), 
							deserializeWithProtoBuf(bbis, classSpace));
				}
				return map;
			}
			case PROTOBUF_OBJECT_TAG : {
				String type = bbis.readUTF();
				try {
					Class<? extends Object> cls = null;
					try {
						cls = classSpace.loadClass(type);
					} catch (ClassNotFoundException cnfe) {
						try {
							return ProtoBufSerializer.class.getClassLoader().loadClass(type);
						} catch (ClassNotFoundException cnfe2) {
							throw cnfe;
						}
					}
					Method m = cls.getMethod("parseFrom", InputStream.class);
					return m.invoke(null, bbis);
				} catch (Exception e) {
					throw new IOException("Unable to deserialize", e);
				}
			}
			case JAVA_OBJECT_TAG : {
				@SuppressWarnings("resource")
				ObjectInputStream ois = null;
				try {
					ois = new ObjectInputStream(bbis) {
						@Override
						protected Class<?> resolveClass(ObjectStreamClass arg0)
								throws IOException, ClassNotFoundException {
							try {
								return classSpace.loadClass(arg0.getName());
							} catch (ClassNotFoundException cnfe) {
								try {
									return ProtoBufSerializer.class.getClassLoader().loadClass(arg0.getName());
								} catch (ClassNotFoundException cnfe2) {
									throw cnfe;
								}
							}
						}
					};
					return ois.readObject();
				} catch (Exception e) {
					throw new IOException("Unable to deserialize", e);
				} finally {
					if(ois != null) {
						ois.close();
					}
				}
			}
			default :
				throw new IOException("Unknown state");
		}
		
	}
}
