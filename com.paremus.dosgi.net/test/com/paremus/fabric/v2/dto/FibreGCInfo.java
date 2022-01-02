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
package com.paremus.fabric.v2.dto;

import com.paremus.dto.api.struct;
import com.paremus.entire.attributes.api.AD.Unit;
import com.paremus.entire.attributes.api.ADA;

public class FibreGCInfo extends struct {
	private static final long	serialVersionUID	= 1L;
	

	/**
	 * @formatter:off
	 */
	@ADA(
			description = "Garbage Collector name")
																				public String				name;
	@ADA(
			description = "Type of collector")
																				public String				type;
	@ADA(
		name="Time",
		description = "Milliseconds spent over the last minute "
				+ "collecting garbage",
		unit = Unit.ms)
																				public long					timeLatch;
	/**
	 * @formatter:on
	 */
}
