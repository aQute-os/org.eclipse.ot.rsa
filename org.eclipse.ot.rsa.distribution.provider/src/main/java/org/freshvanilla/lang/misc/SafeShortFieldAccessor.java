package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeShortFieldAccessor implements FieldAccessor<Short> {
    private final Field f;

    SafeShortFieldAccessor(Field f) {
        this.f = f;
    }

    @Override
	public <Pojo> Short getField(Pojo pojo) {
    	try {
			return (Short) f.get(pojo);
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
	public <Pojo> void setField(Pojo pojo, Short object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (short)(flag ? 1 : 0));
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (short)value);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (short)value);
    }
}