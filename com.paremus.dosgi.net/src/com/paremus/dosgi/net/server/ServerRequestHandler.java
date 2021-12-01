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
package com.paremus.dosgi.net.server;

import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CANCEL;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_SERVICE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_SERVER_OVERLOADED;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.VERSION;
import static java.util.Optional.ofNullable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.config.ProtocolScheme;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Future;

@Sharable
class ServerRequestHandler extends ChannelInboundHandlerAdapter {

	private static final Logger LOG = LoggerFactory.getLogger(ServerRequestHandler.class); 
	
	private final ProtocolScheme transport;
	
	private final ConcurrentMap<UUID, ConcurrentMap<Integer, Future<?>>> pendingCalls
		= new ConcurrentHashMap<>();
	
	private final ConcurrentHashMap<UUID, ServiceInvoker> registeredServices 
		= new ConcurrentHashMap<>();
	
	public ServerRequestHandler(ProtocolScheme transport) {
		super();
		this.transport = transport;
	}
	
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		ByteBuf buf = ((ByteBuf) msg);
		try {
			byte callType = buf.readByte();
			UUID serviceId = new UUID(buf.readLong(), buf.readLong());
			Integer callId = new Integer(buf.readInt());
			
			ServiceInvoker invoker;
			switch(callType) {
				case CALL_WITH_RETURN :
					invoker = registeredServices.get(serviceId);
					if(invoker != null) {
						buf.retain();
						try {
							Future<?> invocation = invoker.call(ctx.channel(), buf, callId);
							ConcurrentMap<Integer, Future<?>> callsForService = pendingCalls.get(serviceId);
							callsForService.put(callId, invocation);
							invocation.addListener(f -> callsForService.remove(callId));
						} catch(RejectedExecutionException ree) {
							buf.release();
							LOG.warn("The RSA distribution provider is overloaded and rejecting calls", ree);
							invoker.sendInternalFailureResponse(ctx.channel(), callId, 
									FAILURE_SERVER_OVERLOADED, ree);
						}
					} else {
						LOG.warn("The RSA distribution provider does not have a service {} registered with transport {};{}", 
								new Object[] {serviceId, transport.getProtocol(), transport.getConfigurationString()});
						ctx.channel().writeAndFlush(
								ctx.alloc().ioBuffer(32)
									.writeByte(VERSION)
									.writeMedium(0)
									.writeByte(FAILURE_NO_SERVICE)
									.writeLong(serviceId.getMostSignificantBits())
									.writeLong(serviceId.getLeastSignificantBits())
									.writeInt(callId));
					}
					break;
				case CALL_WITHOUT_RETURN :
					invoker = registeredServices.get(serviceId);
					if(invoker != null) {
						buf.retain();
						try {
							invoker.execute(buf, callId);
						} catch(RejectedExecutionException ree) {
							buf.release();
							LOG.warn("The RSA distribution provider is overloaded and rejecting calls", ree);
						}
					} else {
						LOG.warn("The RSA distribution provider does not have a service {} registered with transport {};{}", 
								new Object[] {serviceId, transport.getProtocol(), transport.getConfigurationString()});
					}
					break;
				case CANCEL :
					ConcurrentMap<Integer, Future<?>> map = pendingCalls.get(serviceId);
					if(map != null) {
						Future<?> work = map.remove(callId);
						if(work != null) work.cancel(buf.readBoolean());
					}
					break;
				default :
					LOG.warn("The RSA distribution provider received an unknown request type for service {} and is ignoring it",
							serviceId);
			}
		} finally {
			buf.release();
		}
	}
	
	public void registerService(UUID id, ServiceInvoker invoker) {
		pendingCalls.putIfAbsent(id, new ConcurrentHashMap<Integer, Future<?>>(2048));
		registeredServices.put(id, invoker);
	}

	public void unregisterService(UUID id) {
		registeredServices.remove(id);
		ofNullable(pendingCalls.remove(id))
			.map(Map::values)
			.map(Collection::stream)
			.ifPresent(s -> s.forEach(f -> f.cancel(true)));
	}
}