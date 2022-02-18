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
package com.paremus.dosgi.scoping.discovery;

import java.util.Set;

/**
 * The {@link ScopeManager} is used to add or remove local scopes from
 * this discovery node
 */
public interface ScopeManager {

	/**
	 * Get the currently active scopes
	 *
	 * @return the current scopes
	 */
	public Set<String> getCurrentScopes();

	/**
	 * Add a scope
	 * @param name The scope to add
	 */
	public void addLocalScope(String name);

	/**
	 * Remove a scope
	 * @param name The scope to remove
	 */
	public void removeLocalScope(String name);

	/**
	 * Get the base scopes which apply to this discovery and
	 * cannot be removed
	 *
	 * @return the current scopes
	 */
	public Set<String> getBaseScopes();
}
