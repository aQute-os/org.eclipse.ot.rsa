package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeByteFieldAccessor implements FieldAccessor<Byte> {
    private final Field f;

    SafeByteFieldAccessor(Field f) {
        this.f = f;
    }

    public <Pojo> Byte getField(Pojo pojo) {
    	try {
			return (Byte) f.get(pojo);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
        return getField(pojo) != 0;
    }

    public <Pojo> long getNum(Pojo pojo) {
    	return getField(pojo);
    }

    public <Pojo> double getDouble(Pojo pojo) {
    	return getField(pojo);
    }

    public <Pojo> void setField(Pojo pojo, Byte object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (byte)(flag ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (byte)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (byte)value);
    }
}