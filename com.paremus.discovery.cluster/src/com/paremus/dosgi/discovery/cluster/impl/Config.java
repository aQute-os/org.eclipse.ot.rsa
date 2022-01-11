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
package com.paremus.dosgi.discovery.cluster.impl;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(factoryPid="com.paremus.dosgi.discovery.gossip")
public @interface Config {
	@AttributeDefinition(max="65535", min="0")
	int port() default 0;

	String bind_address() default "0.0.0.0"; 

	long rebroadcast_interval() default 15000;
	
	String root_cluster();
	
	String[] target_clusters() default {};
	
	String[] additional_filters() default {};
	
	String local_id_filter_extension() default "";
	
	String[] base_scopes() default {};

}
