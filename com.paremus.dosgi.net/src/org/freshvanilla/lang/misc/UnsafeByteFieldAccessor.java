package org.freshvanilla.lang.misc;

class UnsafeByteFieldAccessor implements FieldAccessor<Byte> {
    private final long offset;

    UnsafeByteFieldAccessor(long offset) {
        this.offset = offset;
    }

    @Override
	public <Pojo> Byte getField(Pojo pojo) {
        return Unsafe.unsafe.getByte(pojo, offset);
    }

    @Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getByte(pojo, offset) != 0;
    }

    @Override
	public <Pojo> long getNum(Pojo pojo) {
        return Unsafe.unsafe.getByte(pojo, offset);
    }

    @Override
	public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getByte(pojo, offset);
    }

    @Override
	public <Pojo> void setField(Pojo pojo, Byte object) {
        Unsafe.unsafe.putByte(pojo, offset, object);
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean flag) {
        Unsafe.unsafe.putByte(pojo, offset, (byte)(flag ? 1 : 0));
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putByte(pojo, offset, (byte)value);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putByte(pojo, offset, (byte)value);
    }
}