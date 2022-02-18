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
package com.paremus.entire.attributes.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.paremus.entire.attributes.api.AD.Unit;

/**
 * An annotation that can be used by the {@link ADBuilder} to construct an
 * {@link AD}. It basically contains the same fields.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD })
public @interface ADA {
	/**
	 * Human readable name for the attribute
	 */
	String name() default "";

	/**
	 * This is a required attribute before an update if true.
	 */
	boolean required() default false;

	/**
	 * A short concise description of the attribute useful for tooltips
	 */
	String description() default "";

	/**
	 * The name of a viewer in the GUI. Viewers can be registered with the en$mo
	 * service.
	 */
	String viewer() default "";

	/**
	 * A builder that can modify the {@link AD} so that it works well when in
	 * the GUI. This class must extend Viewer so that its build method can be
	 * called.
	 */
	Class<? extends Viewer> builder() default Viewer.class;

	/**
	 * The unit that must be used for this attribute
	 */
	Unit unit() default Unit.unit;

	/**
	 * If edited, this is the default value.
	 */
	String deflt() default "";

	/**
	 * A validating pattern using regular expressions.
	 */
	String pattern() default "";

	/**
	 * A set of read permissions required to read this attribute depending on
	 * the authorization system in place. No permissions set means no extra
	 * permission check for this attribute.
	 */
	String[] read() default {};

	/**
	 * A set of edit permissions required to read this attribute depending on
	 * the authorization system in place. No permissions set means no extra
	 * permission check for this attribute.
	 */
	String[] edit() default {};

	/**
	 * A minimum required difference before a delta is reported. If this field
	 * is set to -1 (a threshold must always be positive) then this field will
	 * never be the cause of a delta.
	 */
	double threshold() default 0D;

	/**
	 * If the attribute drops below the {@link #low()} value then an alarm
	 * should be generated.
	 */
	double low() default 0;

	/**
	 * If the attribute goes above the {@link #high()} value then an alarm
	 * should be generated.
	 */
	double high() default 0;

	/**
	 * The maximum feasible value, this can be used for scaling since an
	 * attribute can never cross this value. The zero value means no max.
	 */
	double max() default Double.MAX_VALUE;

	/**
	 * The minimum feasible value, this can be used for scaling since an
	 * attribute can never cross this value. The zero means no min.
	 */
	double min() default -Double.MAX_VALUE;

	/**
	 * Sort order for the UI. The attributes are ordered by their priority (high
	 * first) before they are displayed.
	 */
	int priority() default 0;

	/**
	 * Groups to which this attributes belongs to. It is up to the GUI how to interpret this.
	 */
	String[] groups() default {};
	/**
	 * Diff non-nummeric values. If this flag is false, then the field is not diffed
	 */
	boolean diff() default false;

	/**
	 * A placeholder for inputs
	 */
	String placeholder() default "";
}
