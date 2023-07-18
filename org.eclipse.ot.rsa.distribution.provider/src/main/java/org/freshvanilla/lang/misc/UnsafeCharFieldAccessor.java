package org.freshvanilla.lang.misc;

class UnsafeCharFieldAccessor implements FieldAccessor<Character> {
	private final long offset;

	UnsafeCharFieldAccessor(long offset) {
		this.offset = offset;
	}

	@Override
	public <Pojo> Character getField(Pojo pojo) {
		return Unsafe.unsafe.getChar(pojo, offset);
	}

	@Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
		return Unsafe.unsafe.getChar(pojo, offset) != 0;
	}

	@Override
	public <Pojo> long getNum(Pojo pojo) {
		return Unsafe.unsafe.getChar(pojo, offset);
	}

	@Override
	public <Pojo> double getDouble(Pojo pojo) {
		return Unsafe.unsafe.getChar(pojo, offset);
	}

	@Override
	public <Pojo> void setField(Pojo pojo, Character value) {
		Unsafe.unsafe.putChar(pojo, offset, value);
	}

	@Override
	public <Pojo> void setBoolean(Pojo pojo, boolean value) {
		Unsafe.unsafe.putChar(pojo, offset, (char) (value ? 1 : 0));
	}

	@Override
	public <Pojo> void setNum(Pojo pojo, long value) {
		Unsafe.unsafe.putChar(pojo, offset, (char) value);
	}

	@Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
		Unsafe.unsafe.putChar(pojo, offset, (char) value);
	}
}
