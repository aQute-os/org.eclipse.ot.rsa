package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeByteFieldAccessor implements FieldAccessor<Byte> {
    private final Field f;

    SafeByteFieldAccessor(Field f) {
        this.f = f;
    }

    @Override
	public <Pojo> Byte getField(Pojo pojo) {
    	try {
			return (Byte) f.get(pojo);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
    }

    @Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
        return getField(pojo) != 0;
    }

    @Override
	public <Pojo> long getNum(Pojo pojo) {
    	return getField(pojo);
    }

    @Override
	public <Pojo> double getDouble(Pojo pojo) {
    	return getField(pojo);
    }

    @Override
	public <Pojo> void setField(Pojo pojo, Byte object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (byte)(flag ? 1 : 0));
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (byte)value);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (byte)value);
    }
}