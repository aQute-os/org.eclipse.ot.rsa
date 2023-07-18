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
package org.eclipse.ot.rsa.distribution.test.fabric.v2.dto;

import org.eclipse.ot.rsa.distribution.test.dto.api.struct;
import org.eclipse.ot.rsa.distribution.test.entire.attributes.api.ADA;

/**
 * Identifies an asset, like a machine, a CPU, a VM, etc. An asset has a name, a
 * version, and a vendor.
 * 	@formatter:off
 */
public class Asset extends struct {
	private static final long	serialVersionUID	= 1L;

	@ADA(
		name = " ",
		description = "Name of this asset"
		)
	public String	name;

	@ADA(
		name = "Version",
		description = "Version of this asset"
		)																		public String	version;

	@ADA(
		name = "Vendor",
		description = "Vendor of this asset"
		)																		public String	vendor;

	@ADA(
		name = "# ",
		description = "Serial number of this asset"

		)																		public String	serial;
}
