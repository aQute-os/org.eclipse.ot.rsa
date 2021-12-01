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
package org.osgi.framework;

import java.util.HashMap;
import java.util.Map;

public class FrameworkUtil2 {

	private static final Map<Class<?>, Bundle> mapping = new HashMap<>();
	
	public static void clear() {
		mapping.clear();
	}
	
	public static void registerBundleFor(Class<?> clazz, Bundle bundle) {
		mapping.put(clazz, bundle);
	}
	
	public static Bundle getBundle(Class<?> clazz) {
		return mapping.get(clazz);
	}

}
