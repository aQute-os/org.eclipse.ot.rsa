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
package org.osgi.frameworkx;

import java.util.HashMap;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;

public class FrameworkUtil {

	private static final Map<Class<?>, Bundle>	mapping	= new HashMap<>();

	private static final Map<String, Filter>	filters	= new HashMap<>();

	public static void clear() {
		mapping.clear();
		filters.clear();
	}

	public static void registerBundleFor(Class<?> clazz, Bundle bundle) {
		mapping.put(clazz, bundle);
	}

	public static Bundle getBundle(Class<?> clazz) {
		return mapping.get(clazz);
	}

	public static Filter createFilter(String filter) throws InvalidSyntaxException {
		Filter f = filters.get(filter);
		if (f == null) {
			throw new InvalidSyntaxException("No filter defined for string", filter);
		}
		return f;
	}
}
