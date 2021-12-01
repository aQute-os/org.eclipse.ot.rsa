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

import java.net.URI;
import java.util.List;

import com.paremus.dosgi.net.serialize.SerializationType;

import aQute.bnd.annotation.metatype.Meta.AD;

public interface ImportedServiceConfig {
	@AD
	List<URI> com_paremus_dosgi_net();

	@AD(required=false, deflt="FAST_BINARY")
	SerializationType com_paremus_dosgi_net_serialization();

	@AD(required=false, deflt="-1")
	int com_paremus_dosgi_net_timeout();

	@AD
	List<String> com_paremus_dosgi_net_methods();
	
}
