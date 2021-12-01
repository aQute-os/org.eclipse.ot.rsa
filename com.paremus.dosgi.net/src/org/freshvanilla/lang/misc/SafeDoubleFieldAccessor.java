package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeDoubleFieldAccessor implements FieldAccessor<Double> {
    private final Field f;

    SafeDoubleFieldAccessor(Field f) {
        this.f = f;
    }

    public <Pojo> Double getField(Pojo pojo) {
    	try {
			return (Double) f.get(pojo);
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

    public <Pojo> void setField(Pojo pojo, Double object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (double)(flag ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (double)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (double)value);
    }
}