package org.freshvanilla.lang.misc;

class UnsafeShortFieldAccessor implements FieldAccessor<Short> {
    private final long offset;

    UnsafeShortFieldAccessor(long offset) {
        this.offset = offset;
    }

    public <Pojo> Short getField(Pojo pojo) {
        return Unsafe.unsafe.getShort(pojo, offset);
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getShort(pojo, offset) != 0;
    }

    public <Pojo> long getNum(Pojo pojo) {
        return Unsafe.unsafe.getShort(pojo, offset);
    }

    public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getShort(pojo, offset);
    }

    public <Pojo> void setField(Pojo pojo, Short value) {
        Unsafe.unsafe.putShort(pojo, offset, value);
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putShort(pojo, offset, (short)(value ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putShort(pojo, offset, (short)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putShort(pojo, offset, (short)value);
    }
}