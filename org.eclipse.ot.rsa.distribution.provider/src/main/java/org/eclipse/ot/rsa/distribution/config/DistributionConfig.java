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
package org.eclipse.ot.rsa.distribution.config;

import org.eclipse.ot.rsa.constants.RSAConstants;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(pid = DistributionConfig.PID)
public @interface DistributionConfig {

	String PID = RSAConstants.DISTRIBUTION_PROVIDER_PID;

	int client_worker_threads() default 8;

	int client_io_threads() default 4;

	int client_task_queue_depth() default 1024;

	int server_worker_threads() default 8;

	int server_io_threads() default 4;

	int server_task_queue_depth() default 1024;

	boolean share_io_threads() default true;

	boolean share_worker_threads() default true;

	boolean allow_insecure_transports() default false;

	String[] client_protocols() default {
		"TCP;nodelay=true", "TCP_CLIENT_AUTH;nodelay=true;connect.timeout=3000"
	};

	String[] server_protocols() default {
		"TCP;nodelay=true", "TCP_CLIENT_AUTH;nodelay=true"
	};

	String server_bind_address() default "0.0.0.0";

	int client_default_timeout() default 30000;

	String encoding_scheme_target() default "";

}
