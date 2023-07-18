package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

class SafeDoubleFieldAccessor implements FieldAccessor<Double> {
	private final Field f;

	SafeDoubleFieldAccessor(Field f) {
		this.f = f;
	}

	@Override
	public <Pojo> Double getField(Pojo pojo) {
		try {
			return (Double) f.get(pojo);
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
		return getField(pojo).longValue();
	}

	@Override
	public <Pojo> double getDouble(Pojo pojo) {
		return getField(pojo);
	}

	@Override
	public <Pojo> void setField(Pojo pojo, Double object) {
		try {
			f.set(pojo, object);
		} catch (Exception e) {
			throw new RuntimeException();
		}
	}

	@Override
	public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
		setField(pojo, (double) (flag ? 1 : 0));
	}

	@Override
	public <Pojo> void setNum(Pojo pojo, long value) {
		setField(pojo, (double) value);
	}

	@Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
		setField(pojo, value);
	}
}
