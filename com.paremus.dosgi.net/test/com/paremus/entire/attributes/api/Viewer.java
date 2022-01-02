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

import java.lang.reflect.Type;

/**
 * A Viewer represents the server side of a JS viewer. It can be used in the
 * {@link ADA} annotation. When specified in this annotation, the
 * {@link ADBuilder} will instantiate the given class and call
 * {@link #build(AD, Type)} on it. This can then set the different fields of the
 * {@link AD}.
 * 
 */
public abstract class Viewer {

	/**
	 * Method called during building of the {@link AD}
	 * 
	 * @param def the attribute descriptor being build 
	 * @param type the data type
	 * @return returns def
	 */
	public abstract <T extends AD> T build(T def, Type type) throws Exception;

}
