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
package com.paremus.dosgi.discovery.scoped;

public interface Constants {

	public static final String PAREMUS_SCOPES_ATTRIBUTE = "com.paremus.dosgi.scope";
	public static final String PAREMUS_CLUSTERS_ATTRIBUTE = "com.paremus.dosgi.target.clusters";
	public static final String PAREMUS_TARGETTED_ATTRIBUTE = "com.paremus.dosgi.target.scopes";
	public static final String PAREMUS_TARGETTED_EXTRA_ATTRIBUTE = "com.paremus.dosgi.target.scopes.extra";
	public static final String PAREMUS_CLUSTERS_EXTRA_ATTRIBUTE = "com.paremus.dosgi.target.clusters.extra";
	
	public static final String PAREMUS_SCOPE_UNIVERSAL = "universal";
	public static final String PAREMUS_SCOPE_GLOBAL = "global";
	public static final String PAREMUS_SCOPE_TARGETTED = "targetted";
	
	public static final String PAREMUS_ORIGIN_SCOPE = "com.paremus.dosgi.origin.scope";
	public static final String PAREMUS_ORIGIN_ROOT = "com.paremus.dosgi.origin.id";
}
