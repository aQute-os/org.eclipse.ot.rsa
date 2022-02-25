package org.freshvanilla.lang.misc;

public interface FieldAccessor<T> {
    public <Pojo> T getField(Pojo pojo);

    public <Pojo> boolean getBoolean(Pojo pojo);

    public <Pojo> long getNum(Pojo pojo);

    public <Pojo> double getDouble(Pojo pojo);

    public <Pojo> void setField(Pojo pojo, T value);

    public <Pojo> void setBoolean(Pojo pojo, boolean value);

    public <Pojo> void setNum(Pojo pojo, long value);

    public <Pojo> void setDouble(Pojo pojo, double value);
}