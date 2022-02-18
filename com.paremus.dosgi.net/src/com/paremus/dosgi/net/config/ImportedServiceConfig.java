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

public @interface ImportedServiceConfig {
	String[] com_paremus_dosgi_net() default {};

	SerializationType com_paremus_dosgi_net_serialization() default FAST_BINARY;

	int com_paremus_dosgi_net_timeout() default -1;

	long osgi_basic_timeout() default -1;

	String[] com_paremus_dosgi_net_methods() default {};

}
