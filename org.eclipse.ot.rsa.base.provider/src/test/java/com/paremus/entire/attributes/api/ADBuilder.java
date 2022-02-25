/**
 * Copyright (c) 2012 - 2021 Paremus Ltd., Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * 		Paremus Ltd. - initial API and implementation
 *      Data In Motion
 */
package com.paremus.entire.attributes.api;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.paremus.dto.api.struct;
import com.paremus.entire.attributes.api.AD.Unit;

/**
 * Build an {@link AD} from its field. This allows us to use the return type and
 * any annotations.
 *
 * TODO Use javax.validation annotations as well?
 */
public class ADBuilder {
	static AD	object;
	static AD[]	any;
	static {
		object = new AD();
		object.basicType = AD.BasicType.any;
		any = new AD[] { object };
	}
	final AD	ad	= new AD();

	public AD data() {
		return ad;
	}

	public ADBuilder() {
	}

	public ADBuilder name(String name) {
		ad.name = name;
		return this;
	}

	/*
	 * Build an AD from a data type
	 *
	 * @param type the given data type.
	 */
	private ADBuilder(Type type) throws Exception {
		type(type);
	}

	/**
	 * Use a field to provide the AD from the field type and any annotations.
	 *
	 * @param field
	 *            the field to create an AD for.
	 */
	public ADBuilder(Field field) throws Exception {
		ad.id = field.getName();
		ad.name = unCamel(field.getName());
		type(field.getGenericType());
		ADA annotation = field.getAnnotation(ADA.class);
		if (annotation != null) {
			annotation(annotation);
			if (annotation.builder() != Viewer.class) {
				Viewer v = annotation.builder().getConstructor(new Class[0]).newInstance(new Object[0]);
				v.build(ad, field.getGenericType());
			}
		}
		Sub sub = field.getAnnotation(Sub.class);
		if (sub != null) {
			ad.sub = new AD[sub.value().length];
			for (int i = 0; i < ad.sub.length; i++) {
				ADBuilder adb = new ADBuilder();
				adb.annotation(sub.value()[i]);
				ad.sub[i] = adb.ad;
			}
		}
	}

	/**
	 * Provide an annotation to fill the AD from.,
	 *
	 * @param annotation
	 *            the given annotation
	 * @return A Builder so that call can be chained.
	 */
	public ADBuilder annotation(ADA annotation) throws Exception {
		if (annotation == null)
			return this;

		ad.required = annotation.required();

		if (!annotation.name().equals(""))
			ad.name = annotation.name();

		if (!annotation.placeholder().equals(""))
			ad.placeholder = annotation.placeholder();

		if (!annotation.deflt().isEmpty())
			ad.deflt = annotation.deflt();

		if (!annotation.description().isEmpty())
			ad.description = annotation.description();

		ad.high = annotation.high();
		ad.low = annotation.low();
		ad.min = annotation.min();
		ad.max = annotation.max();
		ad.diff = annotation.diff();
		ad.threshold = annotation.threshold();
		if (ad.threshold > 0)
			ad.diff = true;

		if (!annotation.viewer().isEmpty())
			ad.viewer = annotation.viewer();

		if (!annotation.name().isEmpty())
			ad.name = annotation.name();

		if (!annotation.pattern().isEmpty())
			ad.pattern = annotation.pattern();

		if (annotation.priority() != 0)
			ad.priority = annotation.priority();

		if (annotation.read().length != 0)
			ad.read = annotation.read();

		if (annotation.edit().length > 0)
			ad.edit = annotation.edit();

		ad.groups = annotation.groups();
		ad.unit = annotation.unit();

		if (ad.viewer == null) {
			switch (ad.unit) {
			case ampere:
			case bytes:
			case candela:
			case coulomb:
			case farad:
			case gray:
			case hertz:
			case joule:
			case kat:
			case kelvin:
			case kg:
			case load:
			case lux:
			case m2:
			case m3:
			case m_s:
			case m_s2:
			case meter:
			case mol:
			case ms:
			case newton:
			case none:
			case ohm:
			case pascal:
			case rad:
			case seconds:
			case siemens:
			case sievert:
			case tesla:
			case unit:
			case volt:
			case watt:
			case weber:
				break;
			case time:
			case date:
				ad.viewer = ad.unit.name();
				break;
			case percentage:
				ad.viewer = Unit.percentage.toString();
				break;
			default:

			}
		}
		return this;
	}

	/**
	 * Use the data type to construct reasonable values.
	 *
	 * @param type
	 * @return an ADBuilder so calls can be chained
	 * @throws Exception
	 */
	public ADBuilder type(Type type) throws Exception {
		if (type instanceof Class) {
			Class<?> clazz = (Class<?>) type;

			try {
				ad.viewer = (String) clazz.getField("VIEWER").get(null);
			} catch (Exception e) {
				// Ignore
			}

			if (clazz == Object.class) {
				ad.basicType = AD.BasicType.any;
				return this;
			}

			if (clazz == byte.class || clazz == Byte.class) {
				ad.min = Byte.MIN_VALUE;
				ad.max = Byte.MAX_VALUE;
				ad.basicType = AD.BasicType.number;
				return this;
			} else if (clazz == short.class || clazz == Short.class) {
				ad.min = Short.MIN_VALUE;
				ad.max = Short.MAX_VALUE;
				ad.basicType = AD.BasicType.number;
				return this;
			} else if (clazz == char.class || clazz == Character.class) {
				ad.min = 0;
				ad.max = 65536;
				ad.basicType = AD.BasicType.number;
				return this;
			} else if (clazz == int.class || clazz == Integer.class) {
				ad.min = Integer.MIN_VALUE;
				ad.max = Integer.MAX_VALUE;
				ad.basicType = AD.BasicType.number;
				return this;
			} else if (clazz == long.class || clazz == Long.class) {
				ad.min = Long.MIN_VALUE;
				ad.max = Long.MAX_VALUE;
				ad.basicType = AD.BasicType.number;
				return this;
			} else if (clazz == float.class || clazz == Float.class) {
				ad.min = Float.MIN_VALUE;
				ad.max = Float.MAX_VALUE;
				ad.basicType = AD.BasicType.number;
				return this;
			} else if (clazz == double.class || clazz == Double.class) {
				ad.min = Double.MIN_VALUE;
				ad.max = Double.MAX_VALUE;
				ad.basicType = AD.BasicType.number;
				return this;
			} else if (clazz == boolean.class || clazz == Boolean.class) {
				ad.basicType = AD.BasicType.bool;
				return this;
			}

			if (Number.class.isAssignableFrom(clazz) || clazz == Character.class) {
				ad.basicType = AD.BasicType.number;
				ad.viewer = "number";
				return this;
			}

			if (clazz == String.class) {
				ad.basicType = AD.BasicType.string;
				return this;
			}

			if (Iterable.class.isAssignableFrom(clazz)) {
				ad.basicType = AD.BasicType.list;
				ad.sub = any;
				ad.viewer = "list";
				return this;
			}

			if (Map.class.isAssignableFrom(clazz)) {
				ad.basicType = AD.BasicType.map;
				ad.sub = new AD[] { object, object };
				ad.viewer = "map";
				return this;
			}

			if (Enum.class.isAssignableFrom(clazz)) {
				Enum<?>[] enumConstants = (Enum<?>[]) clazz.getEnumConstants();
				ad.basicType = AD.BasicType.string;
				ad.viewer = "enum";
				ad.options = new HashMap<>();
				for (Enum<?> e : enumConstants) {
					ad.options.put(e.name(), e.toString());
				}
				return this;
			}

			if (struct.class.isAssignableFrom(clazz)) {
				List<Field> fields = new ArrayList<>();
				for (Field f : clazz.getFields()) {
					if (Modifier.isStatic(f.getModifiers()))
						continue;
					fields.add(f);
				}

				if (fields.size() > 0) {
					ad.basicType = AD.BasicType.struct;
					ad.sub = new AD[fields.size()];
					for (int i = 0; i < fields.size(); i++) {
						Field f = fields.get(i);
						ad.sub[i] = new ADBuilder(f).data();
					}
					return this;
				}
			}

			ad.basicType = AD.BasicType.string;
			if (clazz == URI.class || clazz == URL.class)
				ad.viewer = "uri";

			return this;
		}

		if (type instanceof ParameterizedType) {
			Type[] actualTypeArguments = ((ParameterizedType) type).getActualTypeArguments();
			Type rawType = ((ParameterizedType) type).getRawType();
			if (rawType instanceof Class && Collection.class.isAssignableFrom((Class<?>) rawType)) {
				if (!(actualTypeArguments[0] instanceof Class))
					throw new IllegalArgumentException("An iterable must not have a generized type");

				ad.basicType = AD.BasicType.list;
				ad.sub = new AD[] { new ADBuilder(actualTypeArguments[0]).ad };
				ad.sub = any;
				return this;
			} else if (rawType instanceof Class && Map.class.isAssignableFrom((Class<?>) rawType)) {
				ad.basicType = AD.BasicType.map;
				ad.sub = new AD[] { object, object };
				return this;
			}
		}
		throw new IllegalArgumentException(
				"A attributedef requires a primitive, a wrapper, a map, an iterable, or a struct (object with public fields");
	}

	/**
	 * Turn a camelized method name into a readable name. Used as default.
	 *
	 * @param id
	 *            the id of the attribute
	 * @return a good looking name for readability
	 */
	private String unCamel(String id) {
		StringBuilder sb = new StringBuilder();
		sb.append(Character.toUpperCase(id.charAt(0)));

		int i = 1;
		char c;
		while (i < id.length()) {

			while (i < id.length() && Character.isUpperCase(c = id.charAt(i))) {
				sb.append(c);
				i++;
			}
			if (i >= id.length())
				break;

			while (i < id.length() && Character.isLowerCase(c = id.charAt(i))) {
				sb.append(c);
				i++;
			}

			if (i >= id.length())
				break;

			if (!Character.isUpperCase(id.charAt(i))) {
				i++;
			}
			sb.append(' ');
		}
		return sb.toString();
	}

}
