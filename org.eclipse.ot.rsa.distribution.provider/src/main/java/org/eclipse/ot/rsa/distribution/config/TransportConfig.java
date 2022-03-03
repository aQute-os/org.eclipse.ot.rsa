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


@ObjectClassDefinition(factoryPid = TransportConfig.PID)
public @interface TransportConfig {
	String PID = RSAConstants.DISTRIBUTION_TRANSPORT_PID;
	boolean allow_insecure_transports() default true;

	String[] client_protocols() default {
		"TCP;nodelay=true"
	};

	String[] server_protocols() default {
		"TCP;nodelay=true"
	};

	String server_bind_address() default "0.0.0.0";

	int client_default_timeout() default 30000;

	String encoding_scheme_target() default "";

	String endpoint_export_target() default "";

	String endpoint_import_target() default "";

	String endpoint_marker() default "";

	String[] additional_intents() default {};
}
