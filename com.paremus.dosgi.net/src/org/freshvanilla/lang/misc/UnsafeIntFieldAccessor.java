package org.freshvanilla.lang.misc;

class UnsafeIntFieldAccessor implements FieldAccessor<Integer> {
    private final long offset;

    UnsafeIntFieldAccessor(long offset) {
        this.offset = offset;
    }

    public <Pojo> Integer getField(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset);
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset) != 0;
    }

    public <Pojo> long getNum(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset);
    }

    public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getInt(pojo, offset);
    }

    public <Pojo> void setField(Pojo pojo, Integer value) {
        Unsafe.unsafe.putInt(pojo, offset, value);
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putInt(pojo, offset, value ? 1 : 0);
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putInt(pojo, offset, (int)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putInt(pojo, offset, (int)value);
    }
}