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
package org.eclipse.ot.rsa.distribution.test.entire.attributes.api;

import org.eclipse.ot.rsa.distribution.test.entire.viewers.api.Bars;
import org.eclipse.ot.rsa.distribution.test.entire.viewers.api.Summary;

/**
 * Annotations cannot handle recursion ... So an {@link ADA} is used for the
 * specific attributes of an attribute and the Sub annotation allows one to go
 * one level deeper. See {@link Bars} and {@link Summary} how this can be used.
 */
public @interface Sub {
	ADA[] value();
}
