package org.freshvanilla.lang.misc;

class UnsafeLongFieldAccessor implements FieldAccessor<Long> {
    private final long offset;

    UnsafeLongFieldAccessor(long offset) {
        this.offset = offset;
    }

    public <Pojo> Long getField(Pojo pojo) {
        return Unsafe.unsafe.getLong(pojo, offset);
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getLong(pojo, offset) != 0;
    }

    public <Pojo> long getNum(Pojo pojo) {
        return Unsafe.unsafe.getLong(pojo, offset);
    }

    public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getLong(pojo, offset);
    }

    public <Pojo> void setField(Pojo pojo, Long value) {
        Unsafe.unsafe.putLong(pojo, offset, value);
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putLong(pojo, offset, value ? 1L : 0L);
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putLong(pojo, offset, value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putLong(pojo, offset, (long)value);
    }
}