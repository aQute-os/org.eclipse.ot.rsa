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
package com.paremus.netty.tls;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.util.concurrent.Future;

/**
 * This interface represents a DTLS Handler which is capable of being
 * a client or server, and that can maintain a separate DTLS session
 * per address that it communicates with.
 */
public interface DTLSHandler extends ChannelHandler {

    /**
     * Get the handshake future for this handler
     * 
     * @return a future representing the state of the current handshake,
     * or null if no handshake or connection is ongoing
     */
    public Future<Channel> handshakeFuture();

    /**
     * Get the close future for this handler
     * 
     * @return a future representing the state of the current connection,
     * or null if no connection is ongoing
     */
    public Future<Void> closeFuture();
    
    /**
     * Get the address of the remote peer which this Handler is for
     * 
     * @return the remote address, or null if this hander is not yet connected
     */
    public SocketAddress getRemotePeerAddress();
}
