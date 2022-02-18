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
import com.paremus.entire.attributes.api.AD;
import com.paremus.entire.attributes.api.ADA;

public class FibreNetworkInterface extends struct {
	private static final long	serialVersionUID	= 1L;
	public String				name;
	public String				type;
	public String				address;
	public String				netmask;
	public String				macAddress;
	public boolean				active;

	/**
	 * @formatter:off
	 */
	@ADA(
		description = "Link speed",
		unit = AD.Unit.bytes_s)
	public long					speedMax;

	@ADA(
		description = "Number of bytes written per second",
		unit = AD.Unit.bytes_s)
	public long					writtenAvg;
	@ADA(
		description = "Number of bytes read per second",
		unit = AD.Unit.bytes_s)
	public long					readAvg;
	public long					writeAvg;
	public long					readErrorsAvg;
	public long					writeErrorsAvg;
	/**
	 * @formatter:on
	 */
}

