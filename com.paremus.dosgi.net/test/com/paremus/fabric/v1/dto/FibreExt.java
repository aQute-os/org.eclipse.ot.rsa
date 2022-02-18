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
package com.paremus.fabric.v1.dto;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.paremus.dto.api.struct;
import com.paremus.entire.attributes.api.AD;
import com.paremus.entire.attributes.api.AD.Unit;
import com.paremus.entire.attributes.api.ADA;
import com.paremus.entire.viewers.api.Bars;
import com.paremus.entire.viewers.api.Summary;

/**
 * A FibreExt is an extended Fibre (it extends it). The Fibre was extended
 * because most of the cool stuff was hidden in the attributes. These are now
 * converted so that we can easily use them in the GUI.
 */
public class FibreExt extends struct {
	private static final long	serialVersionUID	= 1L;
	public final static String	DEBUG		= "debug";
	public final static String	HIDE		= "hide";
	public final static String	INFO		= "info";
	public final static String	ENVIRONMENT	= "environment";

	public static class Storage extends struct {
		private static final long	serialVersionUID	= 1L;
		public long	unallocated;
	}

	@ADA(
		description = "The name of the fibre",
		groups = HIDE)
	public String				name;

	@ADA(
		description = "The uri of the fibre",
		groups = INFO)
	public URI					fibreUri;

	// TODO ??
	@ADA(
		description = "The link of the fibre",
		groups = INFO)
	public URI					link;

	@ADA(
		description = "Available Java VM heap memory",
		builder = Bars.class,
		groups = ENVIRONMENT,
		diff = true)
	public Memory				heap		= new Memory();

	@ADA(
		description = "Available Java VM non-heap memory",
		builder = Bars.class,
		groups = ENVIRONMENT,
		diff = true)
	public Memory				heapNone	= new Memory();

	// TODO ??
	@ADA(
		groups = INFO,
		description = "")
	public long					featureUpdateNumber;

	@ADA(
		name = "JVM",
		description = "Identification of the VM",
		groups = ENVIRONMENT,
		builder = Summary.class)
	public Asset				jvm			= new Asset();

	// TODO ??

	@ADA(
			groups = DEBUG,
			description = "General Properties",
			diff = true)
	public List<Storage>		storage		= list();

	@ADA(
		groups = HIDE,
		description = "General Properties",
		diff = true)
	public Map<String, Object>	properties	= new HashMap<>();

	/**
	 * Average CPU load
	 */
	@ADA(
		name = "Average CPU Load",
		description = "Average load of the CPU",
		unit = Unit.percentage,
		groups = ENVIRONMENT,
		threshold = 5)
	public int					loadAverage;

	/**
	 * Current CPU load
	 */
	@ADA(
		description = "Current CPU load %", // TODO how does this relate to # of
											// cores?
		name = "Current CPU Load",
		unit = Unit.percentage,
		groups = ENVIRONMENT,
		threshold = 5)
	public int					loadCurrent;

	@ADA(
		name = "Memory Used",
		description = "Percentage of used memory",
		unit = Unit.percentage,
		groups = ENVIRONMENT,
		threshold = 1000000L)
	public int					memoryUsed;

	@ADA(
		name = "Cores",
		groups = ENVIRONMENT,
		description = "Number of available cores")
	public int					cores;

	@ADA(
		name = "OS",
		description = "Name and version of the OS",
		groups = ENVIRONMENT,
		builder = Summary.class,
		priority = 1000)
	public Asset				os			= new Asset();

	@ADA(
		name = "Architecture",
		groups = ENVIRONMENT,
		description = "The hardware architecture of the machine")
	public String				arch;

	@ADA(
		description = "The number of seconds the system has been up",
		unit = Unit.ms,
		groups = ENVIRONMENT,
		threshold = 60000)
	public double				uptime;

	/**
	 * A structure to maintain memory information.
	 */
	public static class Memory {

		@ADA(
			description = "Initially allocated memory",
			builder = Bars.Marker.class,
			unit = AD.Unit.bytes)
		public double	init;

		@ADA(
			description = "Memory currently used",
			builder = Bars.Value.class,
			unit = Unit.bytes,
			threshold = 10000000)
		public double	used;

		@ADA(
			description = "Maximum allowed memory",
			builder = Bars.Marker.class,
			unit = Unit.bytes)
		public double	max;

		@ADA(
			name = "comm.",
			description = "Committed memory, means it is not in used but allocated from the OS",
			builder = Bars.Marker.class,
			unit = Unit.bytes,
			threshold = 1000000)
		public double	committed;
	}
}
