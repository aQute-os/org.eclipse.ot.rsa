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

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;

/**
 * This interface represents a DTLS Handler which is capable of being a client
 * or server, and that can maintain a separate DTLS session per address that it
 * communicates with.
 */
public interface DTLSClientHandler extends DTLSHandler {

	/**
	 * Begin a handshake with the supplied remote address. Note that a handshake
	 * will be implicitly started if the channel is connected to a remote peer.
	 *
	 * @param socketAddress The address to handshake with
	 * @return Either:
	 *         <ul>
	 *         <li>A Future representing the state of the initial handshake</li>
	 *         <li>A failed Future if the handshake has already started with a
	 *         different address</li>
	 *         </ul>
	 */
	Future<Channel> handshake(SocketAddress socketAddress);
}
