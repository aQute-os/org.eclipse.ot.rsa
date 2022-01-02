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
package com.paremus.dto.api;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import aQute.lib.converter.Converter;
import aQute.lib.json.JSONCodec;

/**
 * A utility class that makes it look like Java got structs. It allows fast and
 * efficient handling of field based classes.
 * 
 */
public class struct implements Serializable {
	private static final long	serialVersionUID	= 1L;
	transient static JSONCodec	codec				= new JSONCodec();

	/**
	 * Marks a field as a primary key. Only fields with primary keys are used in
	 * the equals comparison and hash code.
	 */
	public @interface Primary {
	}

	/**
	 * Utility to set a list field.
	 */
	protected <T> List<T> list() {
		return new ArrayList<T>();
	}

	/**
	 * Utility to set a set field.
	 */
	protected <T> Set<T> set() {
		return new LinkedHashSet<T>();
	}

	/**
	 * Utility to set a map field.
	 */
	protected <K, V> Map<K, V> map() {
		return new LinkedHashMap<K, V>();
	}

	/**
	 * Used to sort the names since the order in the class files is undefined.
	 */
	transient private static Comparator<Field>	fieldComparator	= new Comparator<Field>() {

																	@Override
																	public int compare(Field a, Field b) {
																		return a.getName().compareTo(b.getName());
																	}
																};

	/**
	 * A structure to keep our reflection data more efficient than the VM can do
	 * it
	 */

	static class Def {
		final Field[]	fields;
		final Field[]	primary;
		final Class<?>	clazz;

		/*
		 * Construct a Def from a class, will look at the fields,
		 */
		Def(Class<?> c) {
			this.clazz = c;

			List<Field> fields = new ArrayList<Field>();
			for (Field f : c.getFields()) {

				if (Modifier.isStatic(f.getModifiers()))
					continue;

				fields.add(f);
			}
			this.fields = fields.toArray(new Field[fields.size()]);

			Arrays.sort(this.fields, fieldComparator);

			List<Field> primary = new ArrayList<Field>();
			for (Field f : fields) {

				if (Modifier.isStatic(f.getModifiers()))
					continue;

				if (f.getAnnotation(Primary.class) != null)
					primary.add(f);
			}
			if (primary.isEmpty()) {
				this.primary = null;
				return;
			} else {
				this.primary = primary.toArray(new Field[primary.size()]);
			}
		}

		public Field getField(String key) {
			int lo = 0;
			int hi = fields.length - 1;
			while (lo <= hi) {
				// Key is in a[lo..hi] or not present.
				int mid = lo + (hi - lo) / 2;
				int cmp = key.compareTo(fields[mid].getName());
				if (cmp < 0)
					hi = mid - 1;
				else if (cmp > 0)
					lo = mid + 1;
				else
					return fields[mid];
			}
			return null;
		}

		/**
		 * Calculate a hash code for this struct. If no primary keys are set, we
		 * use the whole object
		 * 
		 * @param target
		 *            the target to calc the hashcode for
		 * @return
		 */
		int hashCode(Object target) {
			int hashCode = 0;

			Field fields[] = this.primary;
			if (fields == null)
				fields = this.fields;

			for (Field f : fields) {
				Object value;
				try {
					value = f.get(target);
					if (value == null)
						hashCode ^= 0xAA554422;
					else
						hashCode ^= value.hashCode();
				} catch (Exception e) {
					// cannot happen
					e.printStackTrace();
				}
			}
			return hashCode;
		}

		/**
		 * Calculate the equals for two objects.
		 * 
		 * @param local
		 * @param other
		 * @return
		 */
		boolean equals(Object local, Object other) {
			Field fields[] = this.primary;
			if (fields == null)
				fields = this.fields;

			for (Field f : fields) {
				try {
					Object lv = f.get(local);
					Object ov = f.get(other);
					if (lv != ov) {
						if (lv == null)
							return false;

						if (!lv.equals(ov))
							return false;
					}
				} catch (Exception e) {
					// cannot happen
					e.printStackTrace();
				}
			}
			return true;
		}

		/**
		 * Assuming that we do not have lots of keys, the binary search is very
		 * fast.
		 * 
		 * @param key
		 *            the name of the method
		 * @return the field with the given name
		 */
		public Field field(String key) {
			int lo = 0;
			int hi = fields.length - 1;
			while (lo <= hi) {
				// Key is in a[lo..hi] or not present.
				int mid = lo + (hi - lo) / 2;
				int cmp = key.compareTo(fields[mid].getName());
				if (cmp < 0)
					hi = mid - 1;
				else if (cmp > 0)
					lo = mid + 1;
				else
					return fields[mid];
			}
			return null;
		}

	}

	/*
	 * Stores the def fields.
	 */
	private transient static ConcurrentHashMap<Class<?>, Def>	defs	= new ConcurrentHashMap<Class<?>, struct.Def>();

	protected Def def() {
		return def(getClass());
	}

	private static Def def(Class<?> c) {
		Def def = defs.get(c);
		if (def != null)
			return def;

		// this can potentially happen multiple
		// times but that is not worth the optimization
		def = new Def(c);
		defs.put(c, def);

		return def;
	}

	/**
	 * Should never be created directly, has no meaning
	 */
	protected struct() {
	}

	/**
	 * Defined to use extra values. This is used by the bnd JSONCodec to store
	 * values not available in a struct
	 */
	public transient Map<String, Object>	__extra;

	/**
	 * Create a toStrng that resembles JSON but is shortened if it gets too
	 * large
	 */
	@Override
	public String toString() {
		try {
			String s = codec.enc().put(this).toString();
			if (s.length() > 300) {
				s = s.substring(0, 150) + "..." + s.substring(s.length() - 150);
			}
			return s;
		} catch (Exception e) {
			return e.toString();
		}
	}

	/*
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return def().hashCode();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object other) {
		if (other == null)
			return false;
		if (this == other)
			return true;

		if (other.getClass() != this.getClass())
			return false;

		return def().equals(this, other);
	}

	/*
	 * Compare 2 objects and report true if they are different.
	 */
	public static <T> String diff(T older, T newer) {
		if (older == newer || (older != null && older.equals(newer)))
			return null;

		if (older != null && newer != null) {

			Class<?> oc = older.getClass();
			Class<?> nc = newer.getClass();
			if (oc != nc)
				return "different classes " + oc + ":" + nc;

			if (older instanceof Collection<?>) {
				Collection<?> co = (Collection<?>) older;
				Collection<?> cn = (Collection<?>) newer;
				if (co.size() != cn.size()) {
					return "#" + co.size() + ":" + cn.size();
				}

				Iterator<?> io = co.iterator();
				Iterator<?> in = cn.iterator();
				while (io.hasNext()) {
					Object ioo = io.next();
					Object ino = in.next();
					String diff = diff(ioo, ino);
					if (diff != null)
						return "[" + diff + "]";
				}
				return null;
			}

			if (older instanceof Map<?, ?>) {
				Map<?, ?> co = (Map<?, ?>) older;
				Map<?, ?> cn = (Map<?, ?>) newer;
				if (co.size() != cn.size())
					return "#" + co.size() + ":" + cn.size();

				Set<?> keys = new HashSet<Object>(co.keySet());
				keys.removeAll(cn.keySet());
				if (!keys.isEmpty())
					return "+" + keys;

				for (Map.Entry<?, ?> e : co.entrySet()) {
					Object no = cn.get(e.getKey());
					if (no == null)
						return "-" + e.getKey();

					String diff = diff(e.getValue(), no);
					if (diff != null)
						return "{" + diff + "}";
				}
			}

			Field[] fields = older.getClass().getFields();
			if (fields.length > 0) {
				for (Field of : older.getClass().getFields()) {
					try {
						Field nf = nc.getField(of.getName());
						String diff = diff(of.get(older), nf.get(newer));
						if (diff != null)
							return nf.getName() + "=" + diff;
					} catch (Exception e) {
						return e.toString();
					}
				}
				return null;
			}
		}
		return older + ":" + newer;
	}

	/**
	 * Utility to copy fields from one struct into another, they do not have to
	 * be the same type.
	 * 
	 * @param other
	 *            the struct containing the values
	 */
	public void copyFrom(struct other) throws Exception {
		Def def = def();
		for (Field from : other.def().fields) {
			Field to = def.field(from.getName());

			if (to != null) {
				to.set(this, from.get(other));
			}
		}
	}

	public static class StructMap implements Map<String, Object> {
		final Def		def;
		final struct	s;
		boolean			text	= false;

		StructMap(struct s) {
			this.s = s;
			this.def = s.def();
		}

		@Override
		public int size() {
			return def.fields.length;
		}

		@Override
		public boolean isEmpty() {
			return def.fields.length == 0;
		}

		@Override
		public boolean containsKey(Object key) {
			return get(key) == null;
		}

		@Override
		public boolean containsValue(Object value) {
			return values().contains(value);
		}

		@Override
		public Object get(Object key) {
			if (key instanceof String) {
				try {
					return convert(struct.get((String) key, s));
				} catch (Exception e) {
					// ignore
				}
			}
			return null;
		}

		@Override
		public Object put(String key, Object value) {
			try {
				return set(key, s, value);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public Object remove(Object key) {
			throw new UnsupportedOperationException(
					"This map is backed by a struct and can therefore not remove fields");
		}

		@Override
		public void putAll(Map<? extends String, ? extends Object> m) {
			for ( java.util.Map.Entry<? extends String, ? extends Object> e : m.entrySet()) {
				put(e.getKey(), e.getValue());
			}
		}

		@Override
		public void clear() {
			throw new UnsupportedOperationException("This map is backed by a struct and can therefore notbe cleared");
		}

		@Override
		public Set<String> keySet() {
			Set<String> set = new LinkedHashSet<String>();
			for (Field f : def.fields)
				set.add(f.getName());

			return set;
		}

		@Override
		public Collection<Object> values() {
			List<Object> values = new ArrayList<Object>();
			for (Field f : def.fields)
				try {
					values.add(convert(f.get(s)));
				} catch (Exception e) {
					// ignore
				}
			return values;
		}

		@Override
		public Set<java.util.Map.Entry<String, Object>> entrySet() {
			Set<Map.Entry<String, Object>> set = new LinkedHashSet<java.util.Map.Entry<String, Object>>();
			for (final Field f : def.fields)
				try {
					set.add(new Map.Entry<String, Object>() {

						@Override
						public String getKey() {
							return f.getName();
						}

						@Override
						public Object getValue() {
							try {
								return convert(f.get(s));
							} catch (Exception e) {
								// Ignore
								return null;
							}
						}

						@Override
						public Object setValue(Object value) {
							try {
								Object old = f.get(s);
								f.set(s, Converter.cnv(f.getGenericType(), value));
								return old;
							} catch (Exception e) {
								throw new RuntimeException(e);
							}
						}
					});
				} catch (Exception e) {
					// ignore
				}
			return set;
		}

		public String toString() {
			return s.toString();
		}

		public boolean equals(Object other) {
			if (other instanceof Map) {
				@SuppressWarnings({ "rawtypes" })
				Set<?> entrySet = ((Map) other).entrySet();
				return entrySet().equals(entrySet);
			}
			return false;
		}

		public int hashCode() {
			return entrySet().hashCode();
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		public Map<String, String> asText() {
			text = true;
			Map map = this;
			return map;
		}

		Object convert(Object o) {
			if (o == null)
				return null;
			if (text)
				return o.toString();
			else
				return o;
		}
	}

	public static Map<String, Object> asMap(struct s) {
		if (s == null)
			return Collections.emptyMap();

		return new StructMap(s);
	}

	public static Map<String, String> asStringMap(struct s) {
		return new StructMap(s).asText();
	}

	/**
	 * Provide path based access to an object. struct and maps are accessed by
	 * names, arrays and list by index, and collections can use the *, which
	 * will allow selecting a field in the list, e.g. foo.*.bar will get the bar
	 * fields of all objects in the foo collection.
	 * 
	 * @param n
	 *            where to start in the path
	 * @param path
	 *            the path
	 * @param o
	 *            the object to traverse
	 * @return an object or null if not found
	 */
	@SuppressWarnings("unchecked")
	public static Object get(int n, String path[], Object ox) throws Exception {
		Object rover = ox;
		for (; n < path.length && rover != null; n++) {

			String index = path[n];

			if (rover instanceof struct) {

				struct s = (struct) rover;
				Field field = s.def().field(index);
				if (field == null)
					return null;
				else
					rover = field.get(rover);

			} else if (rover instanceof Map) {

				rover = ((Map<?, ?>) rover).get(index);

			} else if (index.matches("[0-9]+")) {

				int num = Integer.parseInt(index);
				if (rover instanceof List) {
					rover = ((List<Object>) rover).get(num);
				} else if (rover.getClass().isArray()) {
					rover = Array.get(rover, num);
				} else
					return null;

			} else if (index.equals("*")) {

				List<Object> list = new ArrayList<Object>();

				if (rover instanceof Iterable) {
					for (Object m : (Iterable<?>) rover) {
						list.add(get(n + 1, path, m));
					}
					return list;
				} else if (rover.getClass().isArray()) {
					for (int i = 0; i < Array.getLength(rover); i++) {
						list.add(get(n + 1, path, Array.get(rover, i)));
					}
					return list;
				} else
					return null;

			} else
				return null;
		}

		if (rover == null) {
			return null;
		}

		return rover;
	}
	/**
	 * Provide path based access to an object. struct and maps are accessed by
	 * names, arrays and list by index, and collections can use the *, which
	 * will allow setting a field in the list, e.g. foo.*.bar will set the bar
	 * fields of all objects in the foo collection.
	 * 
	 * @param n
	 *            where to start in the path
	 * @param path
	 *            the path
	 * @param o
	 *            the object to traverse
	 * @return an object or null if not found
	 */
	@SuppressWarnings({ "unchecked", "rawtypes"})
	public static Object set(int n, String path[], Object ox, Object vx) throws Exception {
		Object rover = ox;
		Type type = Object.class;
		
		
		for (; n < path.length - 1 && rover != null; n++) {

			String index = path[n];
			
			if (rover instanceof struct) {

				struct s = (struct) rover;
				Field field = s.def().field(index);
				if (field == null)
					throw new IllegalArgumentException("Parent not found for " + Arrays.toString(path) + " for object " + ox);

				rover = field.get(rover);
				type = field.getGenericType();
				
			} else if (rover instanceof Map) {
				rover = ((Map<?, ?>) rover).get(index);
				if ( type instanceof ParameterizedType) {
					ParameterizedType ptype = (ParameterizedType) type;
					type = ptype.getActualTypeArguments()[1];
				} else
					type = Object.class;
			} else if (index.matches("[0-9]+")) {

				int num = Integer.parseInt(index);
				if (rover instanceof List) {
					rover = ((List<Object>) rover).get(num);
					if ( type instanceof ParameterizedType) {
						ParameterizedType ptype = (ParameterizedType) type;
						type = ptype.getActualTypeArguments()[0];
					} else
						type = Object.class;
					
				} else if (rover.getClass().isArray()) {
					rover = Array.get(rover, num);
					if ( type instanceof GenericArrayType) {
						ParameterizedType ptype = (ParameterizedType) type;
						type = ptype.getActualTypeArguments()[0];
					} else
						type = rover.getClass().getComponentType();
				} else
					throw new IllegalArgumentException("Parent not found for " + Arrays.toString(path) + " for object " + ox);

			} else if (index.equals("*")) {

				if (rover instanceof Iterable) {
					
					if ( type instanceof ParameterizedType) {
						ParameterizedType ptype = (ParameterizedType) type;
						type = ptype.getActualTypeArguments()[0];
					} else
						type = Object.class;
					
					List<Object> result = new ArrayList<Object>();;
					for (Object m : (Iterable<?>) rover) {
						result.add(set(n + 1, path, m, vx));
					}
					return result;
				} else if (rover.getClass().isArray()) {
					
					if ( type instanceof GenericArrayType) {
						ParameterizedType ptype = (ParameterizedType) type;
						type = ptype.getActualTypeArguments()[0];
					} else
						type = rover.getClass().getComponentType();
					
					List<Object> result = new ArrayList<Object>();;
					for (int i = 0; i < Array.getLength(rover); i++) {
						set(n + 1, path, Array.get(rover, i), vx);
					}
					return result;
				} else
					throw new IllegalArgumentException("Use of * only works on iterables or arrays");

			} else
				throw new IllegalArgumentException("Do not understand " + index);
		}

		if (rover == null) {
			throw new IllegalArgumentException("Parent not found for " + Arrays.toString(path) + " for object " + ox);
		}
		
		String segment = path[path.length-1];
		
		if ( rover instanceof Map) {
			Map mr = (Map) rover;
			if ( type instanceof ParameterizedType) {
				type = ((ParameterizedType) type).getActualTypeArguments()[1];
				vx = Converter.cnv(type, vx);
			} else
				type = Object.class;
			
			return mr.put(segment, vx);
		} else if ( rover instanceof Collection) {
			if ( type instanceof ParameterizedType) {
				type = ((ParameterizedType) type).getActualTypeArguments()[0];
				vx = Converter.cnv(type, vx);
			} else
				type = Object.class;

			
			if (!segment.matches("-|[\\d]+|\\+")) 
				throw new IllegalArgumentException("A collection but index is not +|-|digit");
			
			if ( segment.equals("+")) {	
				((Collection<Object>)rover).add(vx);
				return null;
			}				
			else if ( segment.equals("-"))	{
				if (((Collection<Object>)rover).remove(vx))
					return vx;
				else
					return null;
			}
			else if ( rover instanceof List) {
				int index = Integer.parseInt(segment);
				List<Object> list = (List<Object>) rover;
				Object old = index < list.size() ? list.get(index) : null;
				while ( list.size() <= index)
					list.add(null);
				
				list.set(index, vx);
				return old;
			} else {
				throw new IllegalArgumentException("A collection indexed with a number but collection does not implement List");
			}
		} else if ( rover.getClass().isArray()) {
			if (!segment.matches("[\\d]+")) 
				throw new IllegalArgumentException("An array but index is not digit");
			
			int index = Integer.parseInt(segment);
			
			if ( type instanceof GenericArrayType) {
				type = ((GenericArrayType) type).getGenericComponentType();
				vx = Converter.cnv(type, vx);
			} else
				type = Object.class;
			
			Object old = Array.get(rover, index);
			Array.set(rover, index, vx);
			return old;
		} else if ( rover instanceof struct) {
			struct s = (struct) rover;
			Field f = s.def().field(segment);
			Object old = f.get(rover);
			f.set(rover,  Converter.cnv(f.getGenericType(), vx));
			return old;
		}
		
		throw new IllegalArgumentException("Cannot set with path " + Arrays.toString(path) + " " + ox + " " + vx);
	}
	public static Object get(String path, Object o) throws Exception {
		return get(0, path.split("\\."), o);
	}

	public static Object set(String path, Object o, Object v) throws Exception {
		return set(0, path.split("\\."), o, v);
	}
	/**
	 * Create a deep copy of the given object. This is not completely complete.
	 * Recursively copied are struct, non-primitive array, collection, and map.
	 * Primitive arrays and other objects are considered immutable in practice.
	 * 
	 * @param object
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	static public <T> T copy(T object) throws Exception {
		try {
			if (object == null || object instanceof String || object instanceof Number || object instanceof URI
					|| object instanceof URL)
				return object;

			if (object instanceof struct) {

				struct s = (struct) object;
				Def def = s.def();
				Object newInstance = s.getClass().newInstance();
				for (Field f : def.fields) {
					Object o = copy(f.get(s));
					f.set(newInstance, o);
				}
				return (T) newInstance;

			} else if (object instanceof Collection) {
				
				Collection<Object> c = (Collection<Object>) object.getClass().newInstance();
				for (Object member : ((Collection<?>) object)) {
					c.add(copy(member));
				}
				return (T) c;

			} else if (object instanceof Map) {

				Map<Object, Object> map = (Map<Object, Object>) object.getClass().newInstance();
				for (Entry<?, ?> member : ((Map<?, ?>) object).entrySet()) {
					map.put(copy(member.getKey()), copy(member.getValue()));
				}
				return (T) map;

			} else if (object.getClass().isArray()) {

				if (object.getClass().getComponentType().isPrimitive()) {
					return object; // TODO Assuming primitive arrays are not
									// copied.
									// Good idea?
				}

				int n = Array.getLength(object);
				Object out = Array.newInstance(object.getClass().getComponentType(), n);
				System.arraycopy(object, 0, out, 0, n);
				return (T) out;

			}

			//
			// Assume it is not mutable
			//

			return object;
		} catch (Exception e) {
			return object;
		}
	}


	



}

