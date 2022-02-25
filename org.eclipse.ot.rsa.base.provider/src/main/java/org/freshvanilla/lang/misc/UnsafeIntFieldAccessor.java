package org.freshvanilla.lang.misc;

class UnsafeIntFieldAccessor implements FieldAccessor<Integer> {
    private final long offset;

    UnsafeIntFieldAccessor(long offset) {
        this.offset = offset;
    }

    @Override
	public <Pojo> Integer getField(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset);
    }

    @Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset) != 0;
    }

    @Override
	public <Pojo> long getNum(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset);
    }

    @Override
	public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset);
    }

    @Override
	public <Pojo> void setField(Pojo pojo, Integer value) {
        Unsafe.unsafe.putInt(pojo, offset, value);
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putInt(pojo, offset, value ? 1 : 0);
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putInt(pojo, offset, (int)value);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putInt(pojo, offset, (int)value);
    }
}