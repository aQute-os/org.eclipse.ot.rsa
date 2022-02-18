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

public enum Status {
	UNKNOWN, VALID, WARNING, ERROR, CRITICAL, DISABLED, DELETED;

	public Status escalate(Status next) {
		if (next.compareTo(this) > 0)
			return next;
		else
			return this;
	}

	public Status ifOverrides(Status status) {
		if (this.compareTo(status) >= 0)
			return this;
		else
			return status;
	}
}
