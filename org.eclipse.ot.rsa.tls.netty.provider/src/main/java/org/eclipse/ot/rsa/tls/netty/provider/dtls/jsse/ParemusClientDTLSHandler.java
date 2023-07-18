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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import org.bouncycastle.tls.ContentType;
import org.bouncycastle.tls.HandshakeType;
import org.bouncycastle.tls.ProtocolVersion;
import org.eclipse.ot.rsa.tls.netty.provider.dtls.adapter.DtlsEngine;
import org.eclipse.ot.rsa.tls.netty.provider.tls.DTLSClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;

public class ParemusClientDTLSHandler extends ParemusBaseDTLSHandler implements DTLSClientHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ParemusClientDTLSHandler.class);

	public ParemusClientDTLSHandler(DtlsEngine engine) {
		super(engine);
	}

	public ParemusClientDTLSHandler(DtlsEngine engine, ChannelHandlerContext ctx, InetSocketAddress remotePeer) {
		super(engine, ctx, remotePeer);
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		if (remotePeer != null) {
			beginHandShake(ctx);
		}
	}

	@Override
	public Future<Channel> handshake(SocketAddress socketAddress) {
		ChannelHandlerContext ctx = this.ctx;
		if (ctx == null) {
			LOG.error("This handler has not been added to a Channel");
		}
		if (remotePeer == null) {
			remotePeer = (InetSocketAddress) socketAddress;
		} else if (!remotePeer.equals(socketAddress) && ctx != null) {
			LOG.error("This handler is already bound to {}", remotePeer);
			return ctx.executor()
				.newFailedFuture(new IllegalStateException("This handler is already bound to " + remotePeer));
		}
		beginHandShake(ctx);

		return handshakeFuture();
	}

	@Override
	protected boolean shouldRegisterForRetransmission(ByteBuf msg) {
		if (!isHandshakeMessage(msg)) {
			return false;
		}

		switch (getHandshakeMessageType(msg)) {
			case HandshakeType.client_hello :
			case HandshakeType.certificate :
			case HandshakeType.client_key_exchange :
			case HandshakeType.certificate_verify :
			case HandshakeType.finished :
				return true;
		}
		return false;
	}

	@Override
	protected void clearRetransmitBuffer(ByteBuf received, List<ByteBuf> currentlyRetransmitting) {
		int index = received.readerIndex();
		int epoch = received.getUnsignedShort(index + 3);
		if (isHandshakeMessage(received)) {
			switch (getHandshakeMessageType(received)) {
				case HandshakeType.server_hello :
				case HandshakeType.hello_verify_request :
				case HandshakeType.certificate :
				case HandshakeType.certificate_request :
				case HandshakeType.certificate_verify :
				case HandshakeType.server_key_exchange :
				case HandshakeType.server_hello_done :
					removeMessages(currentlyRetransmitting, epoch, HandshakeType.client_hello);
					break;
				case HandshakeType.finished :
					ProtocolVersion version = getProtocolVersion(received, index);
					if (version.isEqualOrEarlierVersionOf(ProtocolVersion.DTLSv12)) {
						removeMessages(currentlyRetransmitting, epoch);
					} else {
						removeMessages(currentlyRetransmitting, epoch, HandshakeType.client_hello);
					}
					break;
			}
		} else if (received.getUnsignedByte(index) == 25) {
			// This is an ACK
			// TODO log that we don't handle ACKS properly at the moment
			removeMessages(currentlyRetransmitting, epoch - 1);
		} else if (received.getUnsignedByte(index) == ContentType.application_data) {
			removeMessages(currentlyRetransmitting, epoch);
		}

	}
}
