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

import java.util.List;

import aQute.bnd.annotation.metatype.Meta.AD;

public interface Config {
	
	@AD(required=false, deflt="4")
	int client_io_threads();
	@AD(required=false, deflt="8")
	int client_worker_threads();

	@AD(required=false, deflt="8")
	int server_worker_threads();
	@AD(required=false, deflt="16")
	int server_io_threads();
	@AD(required=false, deflt="1024")
	int server_task_queue_depth();
	
	@AD(required=false, deflt="false")
	boolean allow_insecure_transports();
	
	@AD(required=false, deflt="TCP;nodelay=true|TCP_CLIENT_AUTH;nodelay=true;connect.timeout=3000")
	List<ProtocolScheme> client_protocols();
	
	@AD(required=false, deflt="TCP;nodelay=true|TCP_CLIENT_AUTH;nodelay=true")
	List<ProtocolScheme> server_protocols();
	
	@AD(required=false, deflt="0.0.0.0")
	String server_bind_address();

	@AD(required=false, deflt="30000")
	int client_default_timeout();
}