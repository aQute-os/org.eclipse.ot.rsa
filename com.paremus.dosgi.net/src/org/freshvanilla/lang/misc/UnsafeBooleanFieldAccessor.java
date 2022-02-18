package org.freshvanilla.lang.misc;

class UnsafeBooleanFieldAccessor implements FieldAccessor<Boolean> {
    private final long offset;

    UnsafeBooleanFieldAccessor(long offset) {
        this.offset = offset;
    }

	@Override
	public <Pojo> Boolean getField(Pojo pojo) {
        return Unsafe.unsafe.getBoolean(pojo, offset);
    }

    @Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getBoolean(pojo, offset);
    }

    @Override
	public <Pojo> long getNum(Pojo pojo) {
        return Unsafe.unsafe.getBoolean(pojo, offset) ? 1 : 0;
    }

    @Override
	public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getBoolean(pojo, offset) ? 1 : 0;
    }

    @Override
	public <Pojo> void setField(Pojo pojo, Boolean object) {
        Unsafe.unsafe.putBoolean(pojo, offset, object);
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        Unsafe.unsafe.putBoolean(pojo, offset, flag);
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putBoolean(pojo, offset, value != 0);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putBoolean(pojo, offset, value != 0);
    }
}