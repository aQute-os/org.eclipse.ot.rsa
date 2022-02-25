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
package org.eclipse.ot.rsa.cluster.api;

/**
 * The action associated with a cluster event
 */
public enum Action {
	/**
	 * The member is being added
	 */
	ADDED,
	/**
	 * The properties stored by the member have
	 * been updated
	 */
	UPDATED,
	/**
	 * The member is being removed
	 */
	REMOVED;
}
