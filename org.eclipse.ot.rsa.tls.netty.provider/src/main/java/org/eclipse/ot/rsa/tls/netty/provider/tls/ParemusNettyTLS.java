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
package org.eclipse.ot.rsa.tls.netty.provider.tls;

import io.netty.handler.ssl.SslHandler;

/**
 * Provides access to configured Netty Handlers providing TLS security
 */
public interface ParemusNettyTLS {

	/**
	 * Get a Handler which provides bi-directional DTLS support
	 * <p>
	 * As it is based on TLS, DTLS does have a "client" and "server" in the
	 * protocol. UDP, however, is often not a server type model, so this handler
	 * hides away the client/server nature of DTLS in a single handler
	 *
	 * @return A handler that can be applied to give DTLS support
	 */
	MultiplexingDTLSHandler getDTLSHandler();

	/**
	 * Get a handler which provides a single DTLS connection to a remote
	 * endpoint
	 * 
	 * @return A client-side DTLS handler
	 */
	DTLSClientHandler getDTLSClientHandler();

	/**
	 * Get a handler which provides a single server-side DTLS connection
	 *
	 * @return A server-side DTLS handler
	 */
	DTLSHandler getDTLSServerHandler();

	/**
	 * Get a Handler which provides client TLS support
	 *
	 * @return A client handler that can be applied to give TLS support
	 */
	SslHandler getTLSClientHandler();

	/**
	 * Get a Handler which provides server TLS support
	 *
	 * @return A client handler that can be applied to give TLS support
	 */
	SslHandler getTLSServerHandler();

	/**
	 * Does this TLS provider have a certificate, if true then this provider
	 * will have non-null server capabilities, and support client authentication
	 *
	 * @return true if this TLS factory has a certificate that it can provide
	 */
	boolean hasCertificate();

	/**
	 * Does this TLS provider have a trust domain, if true then this provider
	 * will have non-null client capabilities. The {@link #hasCertificate()}
	 * method must be queried to determine whether client authentication is
	 * supported.
	 *
	 * @return true if this TLS factory has a trust store that it can use for
	 *         client support
	 */
	boolean hasTrust();

}
