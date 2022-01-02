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

import static com.paremus.dosgi.net.server.ServerMessageType.NO_SERVICE;
import static com.paremus.dosgi.net.server.ServerMessageType.UNKNOWN_ERROR;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.CANCEL;
import static com.paremus.dosgi.net.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_CLOSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_DATA;
import static com.paremus.dosgi.net.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_FAILURE;
import static com.paremus.dosgi.net.wireformat.Protocol_V2.CLIENT_BACK_PRESSURE;
import static com.paremus.dosgi.net.wireformat.Protocol_V2.CLIENT_CLOSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V2.CLIENT_OPEN;
import static java.util.Optional.ofNullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.config.ProtocolScheme;
import com.paremus.dosgi.net.message.AbstractRSAMessage.CacheKey;
import com.paremus.dosgi.net.pushstream.PushStreamFactory.DataStream;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

@Sharable
class ServerRequestHandler extends ChannelInboundHandlerAdapter {

	private static final Logger LOG = LoggerFactory.getLogger(ServerRequestHandler.class); 
	
	private final ProtocolScheme transport;
	
	private final ConcurrentHashMap<UUID, ServiceInvoker> registeredServices 
		= new ConcurrentHashMap<>();
	
	private final ConcurrentHashMap<CacheKey, DataStream> registeredStreams
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
			int callId = buf.readInt();
			
			switch(callType) {
				case CALL_WITH_RETURN :
				case CALL_WITHOUT_RETURN:
				case CANCEL:
				case ASYNC_METHOD_PARAM_DATA :
				case ASYNC_METHOD_PARAM_FAILURE :
					invokerAction(ctx, buf, callType, serviceId, callId);
					break;
				case CLIENT_OPEN:
				case CLIENT_BACK_PRESSURE:
				case CLIENT_CLOSE:
					streamAction(ctx, buf, callType, serviceId, callId);
					break;
				default :
					LOG.warn("The RSA distribution provider received an unknown request type {} for service {} and is ignoring it",
							callType, serviceId);
					ctx.write(new ServerErrorMessageResponse(UNKNOWN_ERROR, serviceId, callId,
							"An unknown request type was received for service " + serviceId), ctx.voidPromise());
					
			}
			
		} finally {
			buf.release();
		}
	}

	private void invokerAction(ChannelHandlerContext ctx, ByteBuf buf, byte callType, UUID serviceId, int callId) {
		ServiceInvoker invoker = registeredServices.get(serviceId);
		
		if(invoker != null) {
			callInvoker(ctx, buf, callType, serviceId, callId, invoker);
		} else {
			missingInvoker(ctx, callType, callId, serviceId);
		}
	}

	private void callInvoker(ChannelHandlerContext ctx, ByteBuf buf, byte callType, UUID serviceId, int callId,
			ServiceInvoker invoker) {
		switch(callType) {
			case CALL_WITH_RETURN :
				invoker.call(ctx.channel(), buf, callId);
				break;
			case CALL_WITHOUT_RETURN :
				invoker.call(null, buf, callId);
				break;
			case CANCEL :
				invoker.cancel(callId, buf.readBoolean());
				break;
			case ASYNC_METHOD_PARAM_DATA :
			case ASYNC_METHOD_PARAM_FAILURE :
				invoker.asyncParam(ctx.channel(), callType, callId, buf.readUnsignedByte(), buf);
				break;
//			case ASYNC_METHOD_PARAM_CLOSE :
//				invoker.asyncParamClose(callId, buf.readUnsignedByte());
//				break;
			default :
				LOG.warn("The RSA distribution provider received an unknown request type {} for service {} and is ignoring it",
						callType, serviceId);
		}
	}
	
	private void missingInvoker(ChannelHandlerContext ctx, byte callType, int callId, UUID serviceId) {
		switch(callType) {
			case CALL_WITH_RETURN :
				LOG.warn("The RSA distribution provider does not have a service {} registered with transport {};{}", 
						new Object[] {serviceId, transport.getProtocol(), transport.getConfigurationString()});
				ctx.channel().writeAndFlush(new ServerErrorResponse(NO_SERVICE, serviceId, callId), ctx.voidPromise());
				break;
			case CALL_WITHOUT_RETURN :
			case CANCEL :
			case ASYNC_METHOD_PARAM_DATA :
			case ASYNC_METHOD_PARAM_CLOSE :
			case ASYNC_METHOD_PARAM_FAILURE :
				LOG.warn("The RSA distribution provider does not have a service {} registered with transport {};{}", 
						new Object[] {serviceId, transport.getProtocol(), transport.getConfigurationString()});
				break;
			default :
				LOG.warn("The RSA distribution provider received an unknown request type for service {} and is ignoring it",
					serviceId);
		}
	}

	private void streamAction(ChannelHandlerContext ctx, ByteBuf buf, byte callType, UUID serviceId, int callId) {
		CacheKey key = new CacheKey(serviceId, callId);
		DataStream dataStream = registeredStreams.get(key);
		
		if(dataStream != null) {
			switch(callType) {
				case CLIENT_OPEN:
					dataStream.open();
					break;
				case CLIENT_BACK_PRESSURE:
					dataStream.asyncBackPressure(buf.readLong());
					break;
				case CLIENT_CLOSE:
					dataStream.close();
				break;
			}
		} else if (callType != CLIENT_CLOSE) {
			ctx.writeAndFlush(new ServerErrorMessageResponse(UNKNOWN_ERROR,
					serviceId, callId, "The streaming response could not be found"), 
					ctx.voidPromise());
		}
	}

	public void registerService(UUID id, ServiceInvoker invoker) {
		registeredServices.put(id, invoker);
	}

	public void unregisterService(UUID id, Channel channel) {
		ofNullable(registeredServices.remove(id))
			.ifPresent(si -> si.close(channel));
	}

	public void registerStream(Channel ch, UUID id, int callId, DataStream stream) {
		CacheKey key = new CacheKey(id, callId);
		registeredStreams.put(key, stream);
		stream.closeFuture().addListener(f -> {
				registeredStreams.remove(key);
				if(!f.isSuccess()) {
					ch.writeAndFlush(new ServerErrorMessageResponse(UNKNOWN_ERROR, id, callId, 
						"No connection made to the stream before the timeout was reached"), ch.voidPromise());
				}
			});
	}
}
