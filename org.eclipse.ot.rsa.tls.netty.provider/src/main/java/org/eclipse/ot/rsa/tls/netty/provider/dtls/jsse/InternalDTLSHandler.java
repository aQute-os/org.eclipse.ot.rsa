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
package org.eclipse.ot.rsa.tls.netty.provider.dtls.jsse;

import org.eclipse.ot.rsa.tls.netty.provider.tls.DTLSHandler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.util.concurrent.Future;

public interface InternalDTLSHandler extends DTLSHandler, ChannelInboundHandler, ChannelOutboundHandler {

    public Future<Void> close(ChannelHandlerContext ctx, boolean sendData);

}
