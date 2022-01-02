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
package com.paremus.dosgi.net.config;

import static com.paremus.dosgi.net.serialize.SerializationType.FAST_BINARY;

import com.paremus.dosgi.net.serialize.SerializationType;

public @interface ExportedServiceConfig {
	
	String[] objectClass() default {};
	
	String[] service_exported_interfaces() default {};
	
	String[] service_exported_configs() default {};
	
	String[] service_exported_intents() default {};
	
	String[] service_exported_intents_extra() default {};
	
	String[] com_paremus_dosgi_net_transports() default {};

	String[] service_intents() default {};
	
	SerializationType com_paremus_dosgi_net_serialization() default FAST_BINARY;
}
