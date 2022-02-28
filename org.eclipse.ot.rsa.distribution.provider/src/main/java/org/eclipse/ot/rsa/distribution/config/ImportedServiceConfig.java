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

public @interface ImportedServiceConfig {

	/**
	 * Method name aligned with
	 * {@link RSAConstants#DISTRIBUTION_CONFIGURATION_TYPE}
	 *
	 */
	String[] org_eclipse_ot_rsa_distribution_config() default {};

	String[] org_eclipse_ot_rsa_distribution_config_methods() default {};

	String org_eclipse_ot_rsa_distribution_config_serialization() default "";


	int org_eclipse_ot_rsa_distribution_timeout() default -1;

	long osgi_basic_timeout() default -1;


}
