package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeIntFieldAccessor implements FieldAccessor<Integer> {
	private final Field f;

	SafeIntFieldAccessor(Field f) {
		this.f = f;
	}

	@Override
	public <Pojo> Integer getField(Pojo pojo) {
		try {
			return (Integer) f.get(pojo);
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
	public <Pojo> void setField(Pojo pojo, Integer object) {
		try {
			f.set(pojo, object);
		} catch (Exception e) {
			throw new RuntimeException();
		}
	}

	@Override
	public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
		setField(pojo, flag ? 1 : 0);
	}

	@Override
	public <Pojo> void setNum(Pojo pojo, long value) {
		setField(pojo, (int) value);
	}

	@Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
		setField(pojo, (int) value);
	}
}
