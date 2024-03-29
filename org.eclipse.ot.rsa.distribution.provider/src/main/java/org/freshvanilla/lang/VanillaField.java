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

import java.lang.reflect.Field;

import org.freshvanilla.lang.misc.AccessUtils;
import org.freshvanilla.lang.misc.FieldAccessor;

public class VanillaField<D, T> implements MetaField<D, T> {
	private final FieldAccessor<T>	_accessor;
	private final boolean			_primitive;
	private final Class<T>			_type;
	private final String			_name;

	@SuppressWarnings("unchecked")
	public VanillaField(Field field) {
		this(field.getName(), AccessUtils.getFieldAccessor(field), (Class<T>) field.getType());
	}

	VanillaField(String name, FieldAccessor<T> accessor, Class<T> type) {
		_name = name;
		_accessor = accessor;
		_type = type;
		_primitive = MetaClasses.isPrimitive(type);
	}

	@Override
	public String getName() {
		return _name;
	}

	@Override
	public void set(D pojo, T value) {
		_accessor.setField(pojo, value);
	}

	@Override
	public T get(D pojo) {
		return _accessor.getField(pojo);
	}

	@Override
	public boolean isPrimitive() {
		return _primitive;
	}

	@Override
	public Class<T> getType() {
		return _type;
	}

	@Override
	public void setBoolean(D pojo, boolean flag) {
		_accessor.setBoolean(pojo, flag);
	}

	@Override
	public boolean getBoolean(D pojo) {
		return _accessor.getBoolean(pojo);
	}

	@Override
	public void setNum(D pojo, long value) {
		_accessor.setNum(pojo, value);
	}

	@Override
	public long getNum(D pojo) {
		return _accessor.getNum(pojo);
	}

	@Override
	public void setDouble(D pojo, double value) {
		_accessor.setDouble(pojo, value);
	}

	@Override
	public double getDouble(D pojo) {
		return _accessor.getDouble(pojo);
	}

	@Override
	public String toString() {
		return _name + ':' + _type.getName();
	}
}
