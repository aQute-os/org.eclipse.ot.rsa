package org.freshvanilla.lang.misc;

class UnsafeFloatFieldAccessor implements FieldAccessor<Float> {
    private final long offset;

    UnsafeFloatFieldAccessor(long offset) {
        this.offset = offset;
    }

    @Override
	public <Pojo> Float getField(Pojo pojo) {
        return Unsafe.unsafe.getFloat(pojo, offset);
    }

    @Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getFloat(pojo, offset) != 0;
    }

    @Override
	public <Pojo> long getNum(Pojo pojo) {
        return (long)Unsafe.unsafe.getFloat(pojo, offset);
    }

    @Override
	public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getFloat(pojo, offset);
    }

    @Override
	public <Pojo> void setField(Pojo pojo, Float value) {
        Unsafe.unsafe.putFloat(pojo, offset, value);
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putFloat(pojo, offset, value ? 1.0f : 0.0f);
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putFloat(pojo, offset, value);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putFloat(pojo, offset, (float)value);
    }
}