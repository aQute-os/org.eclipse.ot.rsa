package org.freshvanilla.lang.misc;

class UnsafeDoubleFieldAccessor implements FieldAccessor<Double> {
    private final long offset;

    UnsafeDoubleFieldAccessor(long offset) {
        this.offset = offset;
    }

    public <Pojo> Double getField(Pojo pojo) {
        return Unsafe.unsafe.getDouble(pojo, offset);
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getDouble(pojo, offset) != 0;
    }

    public <Pojo> long getNum(Pojo pojo) {
        return (long)Unsafe.unsafe.getDouble(pojo, offset);
    }

    public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getDouble(pojo, offset);
    }

    public <Pojo> void setField(Pojo pojo, Double value) {
        Unsafe.unsafe.putDouble(pojo, offset, value);
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putDouble(pojo, offset, value ? 1.0 : 0.0);
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putDouble(pojo, offset, value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putDouble(pojo, offset, value);
    }
}