package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeFloatFieldAccessor implements FieldAccessor<Float> {
    private final Field f;

    SafeFloatFieldAccessor(Field f) {
        this.f = f;
    }

    public <Pojo> Float getField(Pojo pojo) {
    	try {
			return (Float) f.get(pojo);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
        return getField(pojo) != 0;
    }

    public <Pojo> long getNum(Pojo pojo) {
    	return getField(pojo).longValue();
    }

    public <Pojo> double getDouble(Pojo pojo) {
    	return getField(pojo);
    }

    public <Pojo> void setField(Pojo pojo, Float object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (float)(flag ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (float)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (float)value);
    }
}