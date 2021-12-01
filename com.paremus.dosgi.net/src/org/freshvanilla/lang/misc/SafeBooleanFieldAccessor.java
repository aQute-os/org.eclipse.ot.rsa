package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeBooleanFieldAccessor implements FieldAccessor<Boolean> {
    private final Field f;

    SafeBooleanFieldAccessor(Field f) {
        this.f = f;
    }

    public <Pojo> Boolean getField(Pojo pojo) {
        try {
			return (Boolean) f.get(pojo);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
    	try {
			return f.getBoolean(pojo);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
    }

    public <Pojo> long getNum(Pojo pojo) {
			return getBoolean(pojo) ? 1 : 0;
    }

    public <Pojo> double getDouble(Pojo pojo) {
			return getBoolean(pojo) ? 1 : 0;
    }

    public <Pojo> void setField(Pojo pojo, Boolean object) {
        try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
    	setField(pojo, flag);
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, value != 0);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, value != 0);
    }
}