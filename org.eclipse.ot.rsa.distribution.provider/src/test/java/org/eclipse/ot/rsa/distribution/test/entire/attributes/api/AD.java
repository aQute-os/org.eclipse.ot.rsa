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
package org.eclipse.ot.rsa.distribution.test.entire.attributes.api;

import java.util.Map;

import org.eclipse.ot.rsa.distribution.test.dto.api.struct;

/**
 * And Attribute Descriptor (AD) describes an attribute so that the user
 * interface can be automatically generated.
 */
public class AD extends struct {
	private static final long	serialVersionUID	= 1L;

	/*
	 * The basic data type as used in JSON
	 */
	public enum BasicType {
		number, string, list, struct, map, bool, any;
	}

	/*
	 * Units
	 */
	public enum Unit {
		none, unit, percentage, bytes, bytes_s, ms, seconds, time, date, hertz, watt, meter, load, candela, sievert, kg, kelvin, mol, ampere, m_s, m_s2, m2, m3, newton, pascal, joule, coulomb, volt, farad, ohm, siemens, weber, tesla, lux, gray, kat, rad;
	}

	@ADA(
		description = "The identity of this Attribute Descriptor")
	public String				id;

	@ADA(
		description = "The name of this Attribute Descriptor, is automatically calculated by uncameling when not explicitly set")
	public String				name;

	@ADA(
		description = "The description, should provide a concise description of this attribute. Will in general be set as tooltip.")
	public String				description;

	@ADA(
		description = "Basic type of the attribute. Maps to the fundamental types in JSON: number, list, string, struct, map, bool, and any")
	public BasicType			basicType;

	@ADA(
		description = "The viewer that is responsible for displaying this attribute. In general, viewers provide extra visualization over the default viewers for the basic types.")
	public String				viewer;

	@ADA(
		description = "The unit if this attribute is nummeric. Units are all SI units and some variations.")
	public Unit					unit;

	@ADA(
		description = "Default value for editing when no value is available.")
	public String				deflt;

	@ADA(
		description = "Required permissions to read this attribute. If no permissions required, all can read this attribute")
	public String[]				read;
	@ADA(
		description = "Required permissions to edit this attribute. If no permissions required, all can edit this attribute")
	public String[]				edit;
	@ADA(
		description = "A validation pattern based on Java Regular Expressions")
	public String				pattern;

	@ADA(
		description = "The minimum threshold required to report a differnce. If the threshold is negative, this attribute never reports a difference.")
	public double				threshold;

	@ADA(
		description = "If the nummeric value drops below the low value, an alert is reported. if low is 0, it is ignored")
	public double				low;
	@ADA(
		description = "If the nummeric value goes over the high value, an alert is reported. if high is 0, it is ignored")
	public double				high;

	@ADA(
		description = "Maximum, never to exceed number. This is not an alert, this is a number to scale visual elements.")
	public double				max;

	@ADA(
		description = "Minimum, never to exceed number. This is not an alert, this is a number to scale visual elements.")
	public double				min;

	@ADA(
		description = "Showing the set of allowed values. The key is the associated enum value, and the value is the human readable (toString) value.")
	public Map<String, String>	options;

	@ADA(
		description = "For lists there is an additional AD for its members, for structs there is one for each member field.")
	public AD					sub[];

	@ADA(
		description = "Used for display priority. Higher numbers are listed earlier")
	public int					priority;
	@ADA(
		description = "Groups for which this attribute is a member. No groups means common membership. Groups are used to split objects in tabs, etc.")
	public String[]				groups;

	@ADA(
			description="Diff non-nummeric values. By default, no non-nummeric values are not compared since they are usually static")
	public boolean diff=false;

	@ADA(
			description="Is a mandatory input field")
	public boolean	required;

	@ADA(
			description="Placeholder for input fields")
	public String	placeholder;

}
