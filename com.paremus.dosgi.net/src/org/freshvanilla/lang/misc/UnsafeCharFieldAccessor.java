package org.freshvanilla.lang.misc;

class UnsafeCharFieldAccessor implements FieldAccessor<Character> {
    private final long offset;

    UnsafeCharFieldAccessor(long offset) {
        this.offset = offset;
    }

    public <Pojo> Character getField(Pojo pojo) {
        return Unsafe.unsafe.getChar(pojo, offset);
    }

    public <Pojo> boolean getBoolean(Pojo pojo) {
        return Unsafe.unsafe.getChar(pojo, offset) != 0;
    }

    public <Pojo> long getNum(Pojo pojo) {
        return Unsafe.unsafe.getChar(pojo, offset);
    }

    public <Pojo> double getDouble(Pojo pojo) {
        return Unsafe.unsafe.getChar(pojo, offset);
    }

    public <Pojo> void setField(Pojo pojo, Character value) {
        Unsafe.unsafe.putChar(pojo, offset, value);
    }

    public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putChar(pojo, offset, (char)(value ? 1 : 0));
    }

    public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putChar(pojo, offset, (char)value);
    }

    public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putChar(pojo, offset, (char)value);
    }
}