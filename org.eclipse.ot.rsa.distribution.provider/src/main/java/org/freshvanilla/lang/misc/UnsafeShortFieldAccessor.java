package org.freshvanilla.lang.misc;

class UnsafeShortFieldAccessor implements FieldAccessor<Short> {
	private final long offset;

	UnsafeShortFieldAccessor(long offset) {
		this.offset = offset;
	}

	@Override
	public <Pojo> Short getField(Pojo pojo) {
		return Unsafe.unsafe.getShort(pojo, offset);
	}

	@Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
		return Unsafe.unsafe.getShort(pojo, offset) != 0;
	}

	@Override
	public <Pojo> long getNum(Pojo pojo) {
		return Unsafe.unsafe.getShort(pojo, offset);
	}

	@Override
	public <Pojo> double getDouble(Pojo pojo) {
		return Unsafe.unsafe.getShort(pojo, offset);
	}

	@Override
	public <Pojo> void setField(Pojo pojo, Short value) {
		Unsafe.unsafe.putShort(pojo, offset, value);
	}

	@Override
	public <Pojo> void setBoolean(Pojo pojo, boolean value) {
		Unsafe.unsafe.putShort(pojo, offset, (short) (value ? 1 : 0));
	}

	@Override
	public <Pojo> void setNum(Pojo pojo, long value) {
		Unsafe.unsafe.putShort(pojo, offset, (short) value);
	}

	@Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
		Unsafe.unsafe.putShort(pojo, offset, (short) value);
	}
}
