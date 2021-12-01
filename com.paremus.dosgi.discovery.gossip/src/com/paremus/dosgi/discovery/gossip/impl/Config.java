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
package com.paremus.dosgi.discovery.gossip.impl;

import java.util.List;

import aQute.bnd.annotation.metatype.Meta.AD;

public interface Config {
	@AD(deflt="0", max="65535", min="0", required=false)
	int port();

	@AD(deflt="15000", required=false)
	long rebroadcast_interval();
	
	@AD
	String root_cluster();
	
	@AD(required=false)
	List<String> additional_filters();
}