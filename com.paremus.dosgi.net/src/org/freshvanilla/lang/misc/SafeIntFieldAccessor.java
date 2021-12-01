package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeIntFieldAccessor implements FieldAccessor<Integer> {
    private final Field f;

    SafeIntFieldAccessor(Field f) {
        this.f = f;
    }

    public <Pojo> Integer getField(Pojo pojo) {
    	try {
			return (Integer) f.get(pojo);
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

    public <Pojo> void setField(Pojo pojo, Integer object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (int)(flag ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (int)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (int)value);
    }
}