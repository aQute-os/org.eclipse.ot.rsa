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
package org.eclipse.ot.rsa.distribution.provider.client;

public class MissingMethodException extends Exception {

	private static final long serialVersionUID = -5390658105164314276L;

	public MissingMethodException(String name) {
		super("The remote service did not have a method " + name
			+ " it is possible that two incompatible versions of the API are being used");
	}
}
