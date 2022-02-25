package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeObjectFieldAccessor implements FieldAccessor<Object> {
    private final Field f;

    SafeObjectFieldAccessor(Field f) {
        this.f = f;
    }

    @Override
	public <Pojo> Object getField(Pojo pojo) {
    	try {
			return f.get(pojo);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
    }

    @Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
        return Boolean.TRUE.equals(getField(pojo));
    }

    @Override
	public <Pojo> long getNum(Pojo pojo) {
        Object obj = getField(pojo);
        if (obj instanceof Number) return ((Number)obj).longValue();
        throw new AssertionError("Cannot convert " + obj + " to long.");
    }

    @Override
	public <Pojo> double getDouble(Pojo pojo) {
        Object obj = getField(pojo);
        if (obj instanceof Number) return ((Number)obj).doubleValue();
        throw new AssertionError("Cannot convert " + obj + " to double.");
    }

    @Override
	public <Pojo> void setField(Pojo pojo, Object value) {
    	try {
        	f.set(pojo, value);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean value) {
    	setField(pojo, value);
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, value);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, value);
    }
}