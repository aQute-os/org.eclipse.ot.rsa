/*-
 * #%L
 * com.paremus.dosgi.net
 * %%
 * Copyright (C) 2016 - 2019 Paremus Ltd
 * %%
 * Licensed under the Fair Source License, Version 0.9 (the "License");
 * 
 * See the NOTICE.txt file distributed with this work for additional 
 * information regarding copyright ownership. You may not use this file 
 * except in compliance with the License. For usage restrictions see the 
 * LICENSE.txt file distributed with this work
 * #L%
 */
package com.paremus.entire.viewers.api;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

import com.paremus.entire.attributes.api.AD;
import com.paremus.entire.attributes.api.ADBuilder;
import com.paremus.entire.attributes.api.Viewer;
import com.paremus.entire.attributes.api.AD.BasicType;

/**
 * Represents a struct as a stacked bar. A field in the struct can be set as a
 * marker (which will be a vertical line at that value) or as a value (which will be shown as a solid bar).
 * <pre>
 * 	public class B {
 * 	  @ADA(builder=Bars.Marker.class)
 *    public int m1;
 * 	  @ADA(builder=Bars.Marker.class)
 *    public int m1;
 * 	  @ADA(builder=Bars.Value.class)
 *    public int b1;
 * 	  @ADA(builder=Bars.Value.class)
 *    public int b2;
 *  }
 *  public class C {
 *    @ADA(builder=Bars.class)
 *    public B b = new B();
 *  } 
 * </pre>
 */
public class Bars extends Viewer {

	/**
	 * Builder class for a marker. 
	 */
	public static class Marker extends Viewer {
		@Override
		public <T extends AD> T build(T def, Type type) {
			def.viewer = "marker";
			return def;
		}
	};

	/**
	 * Builder class for a value/bar. 
	 */
	public static class Value extends Viewer {
		@Override
		public <T extends AD> T build(T def, Type type) {
			def.viewer = "value";
			return def;
		}
	}

	/**
	 * Build the AD
	 */
	@Override
	public <T extends AD> T build(T def, Type type) throws Exception {
		def.basicType = BasicType.struct;
		def.viewer = "stacked";
		Field[] fields = ((Class<?>) type).getFields();
		def.sub = new AD[fields.length];
		for (int i = 0; i < fields.length; i++) {
			// TODO static fields
			def.sub[i] = new ADBuilder(fields[i]).data();
		}
		return def;
	}
}
