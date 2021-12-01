package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeShortFieldAccessor implements FieldAccessor<Short> {
    private final Field f;

    SafeShortFieldAccessor(Field f) {
        this.f = f;
    }

    public <Pojo> Short getField(Pojo pojo) {
    	try {
			return (Short) f.get(pojo);
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

    public <Pojo> void setField(Pojo pojo, Short object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (short)(flag ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (short)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (short)value);
    }
}