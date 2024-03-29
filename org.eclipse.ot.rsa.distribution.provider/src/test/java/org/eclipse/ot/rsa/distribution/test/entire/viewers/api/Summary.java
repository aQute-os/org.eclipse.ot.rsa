/*-
 * #%L
 * org.eclipse.ot.rsa.distribution.provider
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
package org.eclipse.ot.rsa.distribution.test.entire.viewers.api;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

import org.eclipse.ot.rsa.distribution.test.entire.attributes.api.AD;
import org.eclipse.ot.rsa.distribution.test.entire.attributes.api.AD.BasicType;
import org.eclipse.ot.rsa.distribution.test.entire.attributes.api.ADBuilder;
import org.eclipse.ot.rsa.distribution.test.entire.attributes.api.Viewer;

/**
 * Represents a struct as a summary of the fields.
 */
public class Summary extends Viewer {

	@Override
	public <T extends AD> T build(T def, Type type) throws Exception {
		if (!(type instanceof Class))
			throw new IllegalArgumentException("Summaries require a simple struct type");

		Class<?> clazz = (Class<?>) type;

		def.basicType = BasicType.struct;
		def.viewer = "summary";
		Field[] fields = clazz.getFields();
		def.sub = new AD[fields.length];
		for (int i = 0; i < fields.length; i++) {
			def.sub[i] = new ADBuilder(fields[i]).data();
		}
		return def;
	}
}
