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
import com.paremus.entire.viewers.api.Bars;

/**
 * A structure to maintain memory information.
 */
public class FibreMemoryInfo extends struct {
	private static final long	serialVersionUID	= 1L;

	@ADA(
		description = "Initially allocated memory",
		builder = Bars.Marker.class,
		unit = Unit.bytes)
	public long					init;

	@ADA(
		description = "Memory currently used",
		builder = Bars.Value.class,
		unit = Unit.bytes)
	public long					used;

	@ADA(
		description = "Maximum allowed memory",
		builder = Bars.Marker.class,
		unit = Unit.bytes)
	public long					max;

	@ADA(
		name = "comm.",
		description = "Committed memory, means it is not in used but allocated from the OS",
		builder = Bars.Marker.class,
		unit = Unit.bytes)
	public long					committed;
}
