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
package org.eclipse.ot.rsa.distribution.provider.config;

public enum Protocol {

	TCP("ptcp", false), TCP_TLS("ptcps", true), TCP_CLIENT_AUTH("ptcpca", true);

	private final boolean secure;
	private final String uriScheme;

	private Protocol(String uriScheme, boolean secure) {
		this.uriScheme = uriScheme;
		this.secure = secure;
	}

	public boolean isSecure() {
		return secure;
	}

	public String getUriScheme() {
		return uriScheme;
	}

}
