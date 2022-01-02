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

public class FibreFileSystem extends struct {
	private static final long	serialVersionUID	= 1L;
	public String				device;
	public String				mount;
	public String				type;
	public boolean				nodata;
	public long					spaceTotal;
	public long					spaceUsed;

	/**
	 * @formatter:off
	 */
	@ADA(
		description = "Number of bytes written per second",
		unit = Unit.bytes_s)
	public long					writtenAvg;
	@ADA(
		description = "Number of bytes read per second",
		unit = Unit.bytes_s)
	public long					readAvg;
	/**
	 * @formatter:on
	 */
}

