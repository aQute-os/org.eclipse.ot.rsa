/*
 Copyright 2008-2011 the original author or authors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.freshvanilla.lang;

public interface MetaField<D, T> {
    public String getName();

    public boolean isPrimitive();

    public Class<T> getType();

    public void set(D pojo, T value);

    public T get(D pojo);

    public void setBoolean(D pojo, boolean flag);

    public boolean getBoolean(D pojo);

    public void setNum(D pojo, long value);

    public long getNum(D pojo);

    public void setDouble(D pojo, double value);

    public double getDouble(D pojo);
}
