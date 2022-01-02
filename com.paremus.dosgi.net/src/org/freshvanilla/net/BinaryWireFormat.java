/*
 Copyright 2008-2011 the original author or authors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.freshvanilla.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.freshvanilla.lang.MetaClass;
import org.freshvanilla.lang.MetaClasses;
import org.freshvanilla.lang.MetaField;
import org.freshvanilla.lang.ObjectBuilder;
import org.freshvanilla.utils.Classes;
import org.freshvanilla.utils.VanillaResource;

import io.netty.buffer.ByteBuf;

public class BinaryWireFormat implements WireFormat {

    private static final int BYTES_SIZE = 1024;
    private static final Object[] NO_OBJECTS = {};

    private static final byte SIGNED8_STAG = (byte)~SpecialTag.SIGNED8.ordinal();
    private static final SpecialTag[] SPECIAL_TAGS = SpecialTag.values();

    private final byte[] _outBytesArray = new byte[BYTES_SIZE];

    private final Map<Object, Integer> seenWritableObjects = new IdentityHashMap<Object, Integer>(64);
    private final ArrayList<Object> seenReadableObjects = new ArrayList<Object>(64);
    private final MetaClasses _metaClasses;
    private final PojoSerializer _serializer;

    public BinaryWireFormat(MetaClasses metaclasses, PojoSerializer serializer) {
        super();
        _metaClasses = metaclasses;
        _serializer = serializer;
    }

    public void flush(DataSocket ds, ByteBuf writeBuffer) throws IOException {
        ds.flush();
    }

    public boolean readBoolean(ByteBuf readBuffer) throws StreamCorruptedException {
        byte b = readBuffer.readByte();
        SpecialTag tag = asSTag(b, "boolean");
        switch (tag) {
            case TRUE :
                return true;
            case FALSE :
                return false;
            default :
                throw new StreamCorruptedException("Expected a boolean but got a " + tag);
        }
    }

    public void writeBoolean(ByteBuf writeBuffer, boolean flag) {
        writeSTag(writeBuffer, flag ? SpecialTag.TRUE : SpecialTag.FALSE);
    }

    private static SpecialTag asSTag(byte b, String expected) throws StreamCorruptedException {
        if (b >= 0) throw new StreamCorruptedException("Expected " + expected + " but got a value of " + b);
        final SpecialTag[] tags = SPECIAL_TAGS;
        int b2 = ~b;
        if (b2 >= tags.length) {
            throw new StreamCorruptedException("Expected " + expected + " but unknown SpecialTag " + b);
        }
        return tags[b2];
    }

    public long readNum(ByteBuf readBuffer) throws StreamCorruptedException {
        byte b = readBuffer.readByte();
        if (b >= 0) {
            return b;
        }
        SpecialTag tag = asSTag(b, "number");
        switch (tag) {
            case SIGNED1 :
                return readBuffer.readByte();

            case SIGNED2 :
                return readBuffer.readShort();

            case SIGNED4 :
                return readBuffer.readInt();

            case SIGNED8 :
                return readBuffer.readLong();

            case FLOAT4 :
                return (long)readBuffer.readFloat();

            case FLOAT8 :
                return (long)readBuffer.readDouble();

            case CHAR :
                return readBuffer.readChar();

            default :
                throw new StreamCorruptedException("Expected a number, got a " + tag);
        }
    }

    public int readLen(ByteBuf readBuffer) throws StreamCorruptedException {
        long len = readNum(readBuffer);
        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new StreamCorruptedException("length invalid, len=" + len);
        }
        return (int)len;
    }

    public double readDouble(ByteBuf readBuffer) throws StreamCorruptedException {
        byte b = readBuffer.readByte();
        if (b >= 0) return b;

        SpecialTag tag = asSTag(b, "number");
        switch (tag) {
            case SIGNED1 :
                return readBuffer.readByte();

            case SIGNED2 :
                return readBuffer.readShort();

            case SIGNED4 :
                return readBuffer.readInt();

            case SIGNED8 :
                return readBuffer.readLong();

            case FLOAT4 :
                return readBuffer.readFloat();

            case FLOAT8 :
                return readBuffer.readDouble();

            case CHAR :
                return readBuffer.readChar();

            default :
                throw new StreamCorruptedException("Expected a double, got a " + tag);
        }
    }

    private void writeArray(ByteBuf writeBuffer, int maxLength, Object[] objects) throws IOException {
        writeSTag(writeBuffer, SpecialTag.ARRAY);
        int len = maxLength > objects.length ? objects.length : maxLength;
        writeNum(writeBuffer, len);
        MetaClass<?> oClass = _metaClasses.acquireMetaClass(objects.getClass());
        writeTag(writeBuffer, oClass.getComponentType().getName());
        for (int i = 0; i < len; i++) {
            writeObject(writeBuffer, objects[i]);
        }
    }

    private Object[] readArray0(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        int len = readLen(readBuffer);
        MetaClass<?> componentType = _metaClasses.acquireMetaClass(readString(readBuffer));
        Object[] objects;

        if (componentType.getType() == Object.class) {
            if (len == 0) {
                objects = NO_OBJECTS;
            }
            else {
                objects = new Object[len];
            }
        }
        else {
            objects = (Object[])Array.newInstance(componentType.getType(), len);
        }

        seenReadableObjects.add(objects);
        
        for (int i = 0; i < len; i++) {
            objects[i] = readObject(readBuffer);
        }

        return objects;
    }

    public Object readObject(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        byte b = readBuffer.readByte();
        if (b >= 0) {
            return (int)b;
        }

        SpecialTag stag = asSTag(b, "object");
        Object o;
        switch (stag) {
            case NULL :
                return null;

            case TRUE :
                return Boolean.TRUE;

            case FALSE :
                return Boolean.FALSE;

            case ARRAY :
            	//seenReadableObjects handled internally
                o = readArray0(readBuffer);
                return o;

            case SIGNED1 :
                return readBuffer.readByte();

            case SIGNED2 :
                return readBuffer.readShort();

            case SIGNED4 :
                return readBuffer.readInt();

            case SIGNED8 :
                return readBuffer.readLong();

            case FLOAT4 :
                return readBuffer.readFloat();

            case FLOAT8 :
                return readBuffer.readDouble();

            case CHAR :
                return readBuffer.readChar();

            case SERIALIZABLE :
                o = readSerializable0(readBuffer);
                seenReadableObjects.add(o);
                return o;

            case STRING :
                o = readString0(readBuffer);
                seenReadableObjects.add(o);
                return o;

            case TAG :
                return readTag0(readBuffer);

            case LIST :
            	//seenReadableObjects handled internally
            	return readList(readBuffer);

            case MAP :
            	//seenReadableObjects handled internally
            	return readMap(readBuffer);

            case ENTRY :
                return readEntry(readBuffer);

            case ENUM :
                return readEnum(readBuffer);

            case SET :
            	//seenReadableObjects handled internally
            	return readSet(readBuffer);

            case POJO :
            	//seenReadableObjects handled internally
            	return _serializer.deserialize(readBuffer, this);

            case BYTES :
                byte[] bytes = new byte[readLen(readBuffer)];
                readBuffer.readBytes(bytes);
                return bytes;
                
            case BOOLEANS :
            	boolean[] bools = new boolean[readLen(readBuffer)];
            	for(int i=0; i < bools.length; i++) {
            		bools[i] = readBoolean(readBuffer);
            	}
            	return bools;

            case SHORTS :
            	short[] shorts = new short[readLen(readBuffer)];
            	for(int i=0; i < shorts.length; i++) {
            		shorts[i] = (short) readNum(readBuffer);
            	}
            	return shorts;

            case CHARS :
            	char[] chars = new char[readLen(readBuffer)];
            	for(int i=0; i < chars.length; i++) {
            		chars[i] = (char) readNum(readBuffer);
            	}
            	return chars;

            case INTS :
            	int[] ints = new int[readLen(readBuffer)];
            	for(int i=0; i < ints.length; i++) {
            		ints[i] = (int) readNum(readBuffer);
            	}
            	return ints;

            case LONGS :
            	long[] longs = new long[readLen(readBuffer)];
            	for(int i=0; i < longs.length; i++) {
            		longs[i] = readNum(readBuffer);
            	}
            	return longs;

            case FLOATS :
            	float[] floats = new float[readLen(readBuffer)];
            	for(int i=0; i < floats.length; i++) {
            		floats[i] = readNum(readBuffer);
            	}
            	return floats;

            case DOUBLES :
            	double[] doubles = new double[readLen(readBuffer)];
            	for(int i=0; i < doubles.length; i++) {
            		doubles[i] = readNum(readBuffer);
            	}
            	return doubles;

            case CLASS :
                return _metaClasses.loadClass(readString(readBuffer));

            case META_CLASS :
                return _metaClasses.acquireMetaClass(readString(readBuffer));
            case POJO_REF :
            	return seenReadableObjects.get(readBuffer.readInt());
        }

        throw new UnsupportedOperationException("Tag " + stag + " not supported.");
    }

    private Map<Object, Object> readMap(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        int len = readLen(readBuffer);
        Map<Object, Object> map = len > 0
                        ? new LinkedHashMap<Object, Object>(len * 3 / 2)
                        : Collections.emptyMap();
        seenReadableObjects.add(map);
        for (int i = 0; i < len; i++) {
            map.put(readObject(readBuffer), readObject(readBuffer));
        }
        return map;
    }

    private Entry<Object, Object> readEntry(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        final Object key = readObject(readBuffer);
        final Object value = readObject(readBuffer);
        return new SimpleEntry<Object, Object>(key, value);
    }

    private List<Object> readList(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        int len = readLen(readBuffer);
        List<Object> list = len > 0 ? new ArrayList<Object>(len) : Collections.emptyList();
        seenReadableObjects.add(list);
        for (int i = 0; i < len; i++) {
            list.add(readObject(readBuffer));
        }
        return list;
    }

    private Set<Object> readSet(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        int len = readLen(readBuffer);
        Set<Object> set = len > 0 ? new LinkedHashSet<Object>(len * 3 / 2) : Collections.emptySet();
        seenReadableObjects.add(set);
        for (int i = 0; i < len; i++) {
            set.add(readObject(readBuffer));
        }
        return set;
    }

    public String readString(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        final Object o = readObject(readBuffer);
        if (o instanceof String) {
            return (String)o;
        }
        throw new StreamCorruptedException("Expected a String but got a " + (o == null ? "" : o.getClass())
                                           + " was " + o);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Enum readEnum(ByteBuf readBuffer) throws ClassNotFoundException, IOException {
        String enumStr = readString(readBuffer);
        int pos = enumStr.indexOf(' ');
        try {
            Class<Enum> enumClass = (Class<Enum>)_metaClasses.loadClass(enumStr.substring(0, pos));
            return Enum.valueOf(enumClass, enumStr.substring(pos + 1));
        }
        catch (ClassNotFoundException e) {
            throw new NotSerializableException(e.toString());
        }
    }

    private Object readSerializable0(ByteBuf readBuffer) throws IOException {
        int len = readLen(readBuffer);

        byte[] bytes = new byte[len];
        readBuffer.readBytes(bytes);
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
        Object ret;
        try {
            ret = ois.readObject();
        }
        catch (ClassNotFoundException e) {
            throw new NotSerializableException(e.toString());
        }
        ois.close();
        return ret;
    }

    public void writeObject(ByteBuf writeBuffer, Object object) throws IOException {
        if (object == null) {
            writeSTag(writeBuffer, SpecialTag.NULL);
            return;
        }

        Integer i = seenWritableObjects.get(object);
    	if(i != null) {
    		writeSTag(writeBuffer, SpecialTag.POJO_REF);
    		writeBuffer.writeInt(i);
    		return;
    	}
        
        if (_serializer.canSerialize(object)) {
    		seenWritableObjects.put(object, seenWritableObjects.size());
            writeSTag(writeBuffer, SpecialTag.POJO);
            _serializer.serialize(writeBuffer, this, object);
            return;
        }

        if (object instanceof Number) {
            if (object instanceof Byte) {
                writeSTag(writeBuffer, SpecialTag.SIGNED1);
                writeBuffer.writeByte((Byte)object);
                return;
            }
            if (object instanceof Short) {
                writeSTag(writeBuffer, SpecialTag.SIGNED2);
                writeBuffer.writeShort((Short)object);
                return;
            }
            if (object instanceof Integer) {
                int value = (Integer)object;
                if (value >= 0 && value <= Byte.MAX_VALUE) {
                    writeBuffer.writeByte((byte)value);
                }
                else {
                    writeSTag(writeBuffer, SpecialTag.SIGNED4);
                    writeBuffer.writeInt(value);
                }
                return;
            }
            if (object instanceof Long) {
                writeSTag(writeBuffer, SpecialTag.SIGNED8);
                writeBuffer.writeLong((Long)object);
                return;
            }
            if (object instanceof Float) {
                writeSTag(writeBuffer, SpecialTag.FLOAT4);
                writeBuffer.writeFloat((Float)object);
                return;
            }
            if (object instanceof Double) {
                writeSTag(writeBuffer, SpecialTag.FLOAT8);
                writeBuffer.writeDouble((Double)object);
                return;
            }

        }
        else if (object instanceof Collection<?>) {
        	seenWritableObjects.put(object, seenWritableObjects.size());
            if (object instanceof Set<?>) {
                writeCollection(writeBuffer, SpecialTag.SET, (Set<?>)object);
            }
            else {
                writeCollection(writeBuffer, SpecialTag.LIST, (Collection<?>)object);
            }
            return;
        }
        else if (object instanceof Map<?, ?>) {
        	seenWritableObjects.put(object, seenWritableObjects.size());
            writeMap(writeBuffer, (Map<?, ?>)object);
            return;
        }
        else if (object instanceof Entry<?, ?>) {
            writeEntry(writeBuffer, (Entry<?, ?>)object);
            return;
        }
        else if (object instanceof Enum<?>) {
            writeEnum(writeBuffer, (Enum<?>)object);
            return;
        }
        else if (object instanceof Boolean) {
            writeBoolean(writeBuffer, (Boolean)object);
            return;
        }
        else if (object instanceof String) {
        	seenWritableObjects.put(object, seenWritableObjects.size());
            writeString(writeBuffer, (String)object);
            return;
        }
        else if (object instanceof Character) {
            writeSTag(writeBuffer, SpecialTag.CHAR);
            writeBuffer.writeChar((Character)object);
            return;
        }
        else if (object instanceof Class<?>) {
            writeSTag(writeBuffer, SpecialTag.CLASS);
            writeTag(writeBuffer, ((Class<?>)object).getName());
            return;
        }
        else if (object instanceof MetaClass<?>) {
            writeSTag(writeBuffer, SpecialTag.META_CLASS);
            writeTag(writeBuffer, ((MetaClass<?>)object).nameWithParameters());
            return;
        }
        else if (object instanceof byte[]) {
            writeSTag(writeBuffer, SpecialTag.BYTES);
            byte[] bytes = (byte[])object;
            writeNum(writeBuffer, bytes.length);
            writeBuffer.writeBytes(bytes);
            return;
        } 
        else if (object instanceof boolean[]) {
        	writeSTag(writeBuffer, SpecialTag.BOOLEANS);
        	boolean[] bools = (boolean[])object;
        	writeNum(writeBuffer, bools.length);
        	for(boolean b : bools) {
        		writeBoolean(writeBuffer, b);
        	}
        	return;
        } 
        else if (object instanceof short[]) {
        	writeSTag(writeBuffer, SpecialTag.SHORTS);
        	short[] shorts = (short[])object;
        	writeNum(writeBuffer, shorts.length);
        	for(short s : shorts) {
        		writeNum(writeBuffer, s);
        	}
        	return;
        } 
        else if (object instanceof char[]) {
        	writeSTag(writeBuffer, SpecialTag.CHARS);
        	char[] chars = (char[])object;
        	writeNum(writeBuffer, chars.length);
        	for(char c : chars) {
        		writeNum(writeBuffer, c);
        	}
        	return;
        } 
        else if (object instanceof int[]) {
        	writeSTag(writeBuffer, SpecialTag.INTS);
        	int[] ints = (int[])object;
        	writeNum(writeBuffer, ints.length);
        	for(int j : ints) {
        		writeNum(writeBuffer, j);
        	}
        	return;
        } 
        else if (object instanceof long[]) {
        	writeSTag(writeBuffer, SpecialTag.LONGS);
        	long[] longs = (long[])object;
        	writeNum(writeBuffer, longs.length);
        	for(long l : longs) {
        		writeNum(writeBuffer, l);
        	}
        	return;
        } 
        else if (object instanceof float[]) {
        	writeSTag(writeBuffer, SpecialTag.FLOATS);
        	float[] floats = (float[])object;
        	writeNum(writeBuffer, floats.length);
        	for(float f : floats) {
        		writeDouble(writeBuffer, f);
        	}
        	return;
        } 
        else if (object instanceof double[]) {
        	writeSTag(writeBuffer, SpecialTag.DOUBLES);
        	double[] doubles = (double[])object;
        	writeNum(writeBuffer, doubles.length);
        	for(double d : doubles) {
        		writeDouble(writeBuffer, d);
        	}
        	return;
        } else if(object.getClass().isArray()) {
        	seenWritableObjects.put(object, seenWritableObjects.size());
        	writeArray(writeBuffer, Array.getLength(object), (Object[]) object);
        	return;
        }

        if (object instanceof Serializable) {
        	seenWritableObjects.put(object, seenWritableObjects.size());
            writeSTag(writeBuffer, SpecialTag.SERIALIZABLE);
            writeSerializable0(writeBuffer, object);
            return;
        }

        throw new NotSerializableException("Unable to serialize " + object.getClass());
    }

    private void writeMap(ByteBuf writeBuffer, Map<?, ?> map) throws IOException {
        writeSTag(writeBuffer, SpecialTag.MAP);
        writeNum(writeBuffer, map.size());
        for (Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String)
                writeTag(writeBuffer, (String)key);
            else
                writeObject(writeBuffer, key);
            writeObject(writeBuffer, entry.getValue());
        }
    }

    private void writeEntry(ByteBuf writeBuffer, Entry<?, ?> entry) throws IOException {
        writeSTag(writeBuffer, SpecialTag.ENTRY);

        Object key = entry.getKey();
        if (key instanceof String)
            writeTag(writeBuffer, (String)key);
        else
            writeObject(writeBuffer, key);
        writeObject(writeBuffer, entry.getValue());
    }

    private void writeCollection(ByteBuf writeBuffer, SpecialTag stag, Collection<?> collection)
        throws IOException {
        writeSTag(writeBuffer, stag);
        writeNum(writeBuffer, collection.size());
        for (Object o : collection)
                writeObject(writeBuffer, o);
    }

    private void writeEnum(ByteBuf writeBuffer, Enum<?> enumValue) {
        String name = enumValue.getDeclaringClass().getName();
        String enumStr = name + ' ' + enumValue;
        writeSTag(writeBuffer, SpecialTag.ENUM);
        writeTag(writeBuffer, enumStr);
    }

    private void writeSerializable0(ByteBuf writeBuffer, Object object) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(BYTES_SIZE);
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(object);
        oos.close();
        byte[] bytes = baos.toByteArray();
        writeNum(writeBuffer, bytes.length);
        writeBuffer.writeBytes(bytes);
    }

    private static void writeSTag(ByteBuf writeBuffer, SpecialTag stag) {
        writeBuffer.writeByte((byte)~stag.ordinal());
    }

    public void writeNum(ByteBuf writeBuffer, long value) {
        if (value >= 0 && value <= Byte.MAX_VALUE) {
            writeBuffer.writeByte((byte)value);
        }
        else if (value == (byte)value) {
            writeSTag(writeBuffer, SpecialTag.SIGNED1);
            writeBuffer.writeByte((byte)value);

        }
        else if (value == (short)value) {
            writeSTag(writeBuffer, SpecialTag.SIGNED2);
            writeBuffer.writeShort((short)value);

        }
        else if (value == (int)value) {
            writeSTag(writeBuffer, SpecialTag.SIGNED4);
            writeBuffer.writeInt((int)value);
        }
        else {
            writeBuffer.writeByte(SIGNED8_STAG);
            writeBuffer.writeLong(value);
        }
    }

    public static void writeDouble(ByteBuf writeBuffer, double value) {
        if (value == (byte)value) {
            if (value >= 0 && value <= Byte.MAX_VALUE) {
                writeBuffer.writeByte((byte)value);
            }
            else {
                writeSTag(writeBuffer, SpecialTag.SIGNED1);
                writeBuffer.writeByte((byte)value);
            }
        }
        else if (value == (short)value) {
            writeSTag(writeBuffer, SpecialTag.SIGNED2);
            writeBuffer.writeShort((short)value);
        }
        else if (value == (int)value) {
            writeSTag(writeBuffer, SpecialTag.SIGNED4);
            writeBuffer.writeInt((int)value);
        }
        else if (value == (float)value) {
            writeSTag(writeBuffer, SpecialTag.FLOAT4);
            writeBuffer.writeFloat((float)value);
        }
        else {
            writeSTag(writeBuffer, SpecialTag.FLOAT8);
            writeBuffer.writeDouble(value);
        }
    }

    private final Map<String, Integer> outTagMap = new LinkedHashMap<String, Integer>();
    private final List<String> inTagList = new ArrayList<String>();

    public void writeTag(ByteBuf writeBuffer, String tag) {
        writeSTag(writeBuffer, SpecialTag.TAG);
        final Integer num = outTagMap.get(tag);
        if (num == null) {
            int num2 = outTagMap.size();
            outTagMap.put(tag, num2);
            writeNum(writeBuffer, num2);
            writeString0(writeBuffer, tag);
        }
        else {
            writeNum(writeBuffer, num);
        }
    }

    private void writeString(ByteBuf writeBuffer, String text) {
        writeSTag(writeBuffer, SpecialTag.STRING);
        writeString0(writeBuffer, text);
    }

    private void writeString0(ByteBuf writeBuffer, CharSequence text) {
        int len = text.length();
        writeNum(writeBuffer, len);

        if (len == 0) {
            return;
        }

        boolean hichars = false;
        byte[] bytes = _outBytesArray;

        for (int off = 0; off < len; off += BYTES_SIZE) {
            int len2 = len - off < BYTES_SIZE ? len - off : BYTES_SIZE;
            for (int i = 0; i < len2; i++) {
                char ch = text.charAt(i + off);
                if (ch < 255) {
                    bytes[i] = (byte)ch;
                }
                else {
                    bytes[i] = (byte)255;
                    hichars = true;
                }
            }
            writeBuffer.writeBytes(bytes, 0, len2);
        }

        if (hichars) {
            for (int i = 0; i < len; i++) {
                char ch = text.charAt(i);
                if (ch >= 255) {
                    writeBuffer.writeChar(ch);
                }
            }
        }
    }

    private String readTag0(ByteBuf readBuffer) throws StreamCorruptedException {
        final long num = readNum(readBuffer);

        if (num < 0) {
            throw new StreamCorruptedException("Invalid tag num= " + num);
        }

        final int size = inTagList.size();
        if (num == size) {
            String ret = readString0(readBuffer);
            inTagList.add(ret);
            return ret;
        }
        else if (num < size) {
            return inTagList.get((int)num);
        }
        else {
            throw new StreamCorruptedException("Invalid tag num= " + num);
        }
    }

    private final byte[] inBytesArray = new byte[BYTES_SIZE];
    private final char[] inCharsArray = new char[BYTES_SIZE];

    private String readString0(ByteBuf readBuffer) throws StreamCorruptedException {
        int len = readLen(readBuffer);
        if (len == 0) {
            return "";
        }

        byte[] bytes = len <= BYTES_SIZE ? inBytesArray : new byte[len];
        readBuffer.readBytes(bytes, 0, len);

        char[] chars = len <= BYTES_SIZE ? inCharsArray : new char[len];
        boolean hichars = false;

        for (int i = 0; i < len; i++) {
            final char ch = (char)(bytes[i] & 0xFF);
            if (ch == 255) {
                hichars = true;
            }
            chars[i] = ch;
        }

        if (hichars) {
            for (int i = 0; i < len; i++) {
                if (chars[i] == 255) {
                    chars[i] = readBuffer.readChar();
                }
            }
        }

        return new String(chars, 0, len);
    }

    @SuppressWarnings("unchecked")
    public <Pojo, T> void readField(ByteBuf rb, MetaField<Pojo, T> field, Pojo pojo)
        throws ClassNotFoundException, IOException {
        final Class<?> type = field.getType();

        if (field.isPrimitive()) {
            if (type == boolean.class) {
                field.setBoolean(pojo, readBoolean(rb));
            }
            else if (type == byte.class || type == char.class || type == short.class || type == int.class
                     || type == long.class) {
                field.setNum(pojo, readNum(rb));
            }
            else if (type == float.class || type == double.class) {
                field.setDouble(pojo, readDouble(rb));
            }
            else {
                throw new NotSerializableException("Unknown primitive type " + type);
            }
        }
        else {
            T value = (T)readObject(rb);
            if (!(value == null || type.isAssignableFrom(value.getClass()))) {
                value = (T)Classes.parseAs(value, type);
            }
            field.set(pojo, value);
        }
    }

    public <Pojo, T> void writeField(ByteBuf wb, MetaField<Pojo, T> field, Pojo pojo) throws IOException {
        final Class<?> type = field.getType();

        if (field.isPrimitive()) {
            if (type == boolean.class) {
                writeBoolean(wb, field.getBoolean(pojo));
            }
            else if (type == byte.class || type == char.class || type == short.class || type == int.class
                     || type == long.class) {
                writeNum(wb, field.getNum(pojo));
            }
            else if (type == float.class || type == double.class) {
                writeDouble(wb, field.getDouble(pojo));
            }
            else {
                throw new NotSerializableException("Unknown primitive type " + type);
            }
        }
        else {
            T object = field.get(pojo);
            writeObject(wb, object);
        }
    }
    
    public void reset() {
    	inTagList.clear();
    	outTagMap.clear();
    	seenWritableObjects.clear();
    	seenReadableObjects.clear();
    }

    @Override
	public void registerPojo(Object o) {
		seenReadableObjects.add(o);
	}

    @Override
    public int getPojoIndex() {
    	return seenReadableObjects.size();
    }

    @Override
    public void registerPojo(int idx, Object o) {
    	seenReadableObjects.add(idx, o);
    }

	public static class Builder extends VanillaResource implements ObjectBuilder<WireFormat> {

        private final MetaClasses _metaClasses;
		private final PojoSerializer _serializer;

        public Builder(String name, MetaClasses metaclasses, PojoSerializer serializer) {
            super(name);
            _metaClasses = metaclasses;
			_serializer = serializer;
        }

        protected void finalize() throws Throwable {
            try {
                close();
            }
            finally {
                super.finalize();
            }
        }

        public WireFormat create() {
            checkedClosed();
            return new BinaryWireFormat(_metaClasses, _serializer);
        }
    }

    static class SimpleEntry<K, V> implements Entry<K, V> {
        private final K _key;
        private V _value;

        SimpleEntry(K key, V value) {
            _key = key;
            _value = value;
        }

        public K getKey() {
            return _key;
        }

        public V getValue() {
            return _value;
        }

        public V setValue(V value) {
            V prev = _value;
            _value = value;
            return prev;
        }
    }
}
