package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeCharFieldAccessor implements FieldAccessor<Character> {
    private final Field f;

    SafeCharFieldAccessor(Field f) {
        this.f = f;
    }

    public <Pojo> Character getField(Pojo pojo) {
    	try {
			return (Character) f.get(pojo);
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

    public <Pojo> void setField(Pojo pojo, Character object) {
    	try {
        	f.set(pojo, object);
        } catch (Exception e) {
        	throw new RuntimeException();
        }
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        setField(pojo, (char)(flag ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
    	setField(pojo, (char)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
    	setField(pojo, (char)value);
    }
}