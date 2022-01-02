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
import com.paremus.entire.attributes.api.ADA;

public class FibreThreadPoolInfo extends struct {
	private static final long	serialVersionUID	= 1L;
	public String				name;
	@ADA(
		description = "Number of threads currently active")
	public int					active;
	public int					max;
	public long					queueDepth;
	@ADA(
		description = "Number of threads created in the last minute")
	public long					createdAvg;
	public int					min;

}

