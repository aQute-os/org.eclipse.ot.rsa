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

/**
 * Constants used by the Discovery Layer
 */
public interface Constants {

	/**
	 * This key is used to define the scope at which this service should be made
	 * available. Allowed values are:
	 * <ul>
	 *   <li>{@link #PAREMUS_SCOPE_UNIVERSAL} - Advertise this service everywhere</li>
	 *   <li>{@link #PAREMUS_SCOPE_GLOBAL} - Advertise this service to all members within the default local group</li>
	 *   <li>{@link #PAREMUS_SCOPE_TARGETTED} - Advertise this service to members that have the relevant scope </li>
	 * </ul>
	 * 
	 * The default value is {@link #PAREMUS_SCOPE_GLOBAL}
	 */
	public static final String PAREMUS_SCOPES_ATTRIBUTE = "com.paremus.dosgi.scope";

	/** 
	 * This key is used to define the target scopes for a 
	 * {@link #PAREMUS_SCOPE_TARGETTED} service. Usually set automatically
	 * by the topology manager.
	 **/
	public static final String PAREMUS_TARGETTED_ATTRIBUTE = "com.paremus.dosgi.target.scopes";

	/** 
	 * This key is used to define additional target scopes for a 
	 * {@link #PAREMUS_SCOPE_TARGETTED} service. Usually set on a service to 
	 * augment the default target scopes specified by 
	 * {@link #PAREMUS_TARGETTED_ATTRIBUTE}
	 **/
	public static final String PAREMUS_TARGETTED_EXTRA_ATTRIBUTE = "com.paremus.dosgi.target.scopes.extra";
	
	/**
	 * Used to expose a service to all possible connected nodes
	 */
	public static final String PAREMUS_SCOPE_UNIVERSAL = "universal";
	
	/**
	 * Used to expose a service to all nodes in the local group
	 */
	public static final String PAREMUS_SCOPE_GLOBAL = "global";

	/**
	  * Used to expose a service to nodes with the named scopes
	  */
	public static final String PAREMUS_SCOPE_TARGETTED = "targetted";
	
	/**
	 * The UUID of the "origin" framework if different from the Framework UUID in
	 * the the Endpoint Description. This should be set by the topology manager
	 * when exporting a service that has a secondary id (e.g. it is being exposed
	 * via OSGi, or is coming from another framework).
	 */
	public static final String PAREMUS_ORIGIN_ROOT = "com.paremus.dosgi.origin.id";
}
