package org.freshvanilla.lang.misc;

class UnsafeObjectFieldAccessor implements FieldAccessor<Object> {
    private final long offset;

    UnsafeObjectFieldAccessor(long offset) {
        this.offset = offset;
    }

    @Override
	public <Pojo> Object getField(Pojo pojo) {
        return Unsafe.unsafe.getObject(pojo, offset);
    }

    @Override
	public <Pojo> boolean getBoolean(Pojo pojo) {
        return Boolean.TRUE.equals(Unsafe.unsafe.getObject(pojo, offset));
    }

    @Override
	public <Pojo> long getNum(Pojo pojo) {
        Object obj = Unsafe.unsafe.getObject(pojo, offset);
        if (obj instanceof Number) return ((Number)obj).longValue();
        throw new AssertionError("Cannot convert " + obj + " to long.");
    }

    @Override
	public <Pojo> double getDouble(Pojo pojo) {
        Object obj = Unsafe.unsafe.getObject(pojo, offset);
        if (obj instanceof Number) return ((Number)obj).doubleValue();
        throw new AssertionError("Cannot convert " + obj + " to double.");
    }

    @Override
	public <Pojo> void setField(Pojo pojo, Object value) {
        Unsafe.unsafe.putObject(pojo, offset, value);
    }

    @Override
	public <Pojo> void setBoolean(Pojo pojo, boolean value) {
        Unsafe.unsafe.putObject(pojo, offset, value);
    }

    @Override
	public <Pojo> void setNum(Pojo pojo, long value) {
        Unsafe.unsafe.putObject(pojo, offset, value);
    }

    @Override
	public <Pojo> void setDouble(Pojo pojo, double value) {
        Unsafe.unsafe.putObject(pojo, offset, value);
    }
}