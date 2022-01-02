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
import java.util.Collection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.util.concurrent.Future;

/**
 * This interface represents a DTLS Handler which is capable of being
 * a client or server, and that can maintain a separate DTLS session
 * per address that it communicates with.
 */
public interface MultiplexingDTLSHandler extends ChannelHandler {

    /**
     * Get the handshake future for a specific remote participant
     * 
     * @param socketAddress the participant to query
     * @return a future representing the state of the current handshake,
     * or null if no handshake or connection is ongoing
     */
    public Future<Channel> handshakeFuture(SocketAddress socketAddress);

    /**
     * Start a handshake with the supplied remote participant
     * 
     * @param socketAddress the remote participant
     * @return a future representing the handshake
     */
    public Future<Channel> handshake(SocketAddress socketAddress);
    
    /**
     * Disconnect the DTLS session with the supplied remote participant
     * @param socketAddress the remote participant
     * @return a future representing the disconnection
     */
    public Future<Void> disconnect(SocketAddress socketAddress);
    
    /**
     * Get a collection of all the remote participants with which there
     * are ongoing handshakes and DTLS sessions
     * @return A collection of remote participants
     */
    public Collection<? extends SocketAddress> activeAndPending();
    
}
