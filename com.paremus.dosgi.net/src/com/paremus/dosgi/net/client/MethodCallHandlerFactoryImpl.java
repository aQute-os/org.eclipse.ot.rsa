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
package com.paremus.dosgi.net.client;

import static com.paremus.dosgi.net.wireformat.Protocol_V1.CANCEL;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_METHOD;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_SERVICE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_RESPONSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_DESERIALIZE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.SIZE_WIDTH_IN_BYTES;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.SUCCESS_RESPONSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.VERSION;
import static io.netty.buffer.StringHelper.readLengthPrefixedUtf8;
import static java.util.Optional.ofNullable;
import static org.osgi.framework.ServiceException.REMOTE;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceException;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.impl.ImportRegistrationImpl;
import com.paremus.dosgi.net.proxy.MethodCallHandler;
import com.paremus.dosgi.net.proxy.MethodCallHandler.CallType;
import com.paremus.dosgi.net.proxy.MethodCallHandlerFactory;
import com.paremus.dosgi.net.serialize.Serializer;
import com.paremus.dosgi.net.serialize.SerializerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class MethodCallHandlerFactoryImpl implements MethodCallHandlerFactory {

	private static final Logger LOG = LoggerFactory.getLogger(MethodCallHandlerFactory.class);
	
	private static final Object[] EMPTY_ARGS = new Object[0];
	
	private final byte[] template;
	
	private final AtomicInteger invocationId = new AtomicInteger();

	private final ConcurrentMap<Integer, PendingCall> pending = new ConcurrentHashMap<>();
	
	private final ClientConnectionManager clientConnectionManager;
	
	private final UUID endpointId;

	private final Channel channel;
	
	private final ByteBufAllocator allocator;
	
	private final SerializerFactory serializerFactory;

	private final Timer timer;

	private final Collection<ImportRegistrationImpl> ir = new HashSet<>();
	
	private final IntObjectMap<String> methodNames;

	private boolean closed;
	
	public MethodCallHandlerFactoryImpl(Channel channel, ByteBufAllocator allocator, 
			UUID serviceId, SerializerFactory serializerFactory, Map<Integer, String> methodMappings, 
			ClientConnectionManager clientConnectionManager, Timer timer) {
		this.channel = channel;
		this.allocator = allocator;
		this.endpointId = serviceId;
		this.serializerFactory = serializerFactory;
		this.clientConnectionManager = clientConnectionManager;
		this.timer = timer;
		
		this.methodNames = new IntObjectHashMap<String>();
		methodMappings.entrySet().stream()
			.forEach(e -> methodNames.put(e.getKey(), e.getValue()));
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		try(DataOutputStream dos = new DataOutputStream(baos)) {
			dos.writeByte(VERSION);
			for(int i = 0; i < SIZE_WIDTH_IN_BYTES; i++) {
				dos.writeByte(0);
			}
			dos.writeByte(0);
			dos.writeLong(serviceId.getMostSignificantBits());
			dos.writeLong(serviceId.getLeastSignificantBits());
		} catch (IOException e) {
			throw new RuntimeException("Unable to generate the call template", e);
		}
		template = baos.toByteArray();
	}

	@Override
	public MethodCallHandler create(Bundle classSpace) {
		Serializer s = serializerFactory.create(classSpace);
		return (t,m,a,l) -> call(s, t, m, a, l);
	}

	public Promise<?> call(Serializer serializer, CallType type, int methodId, Object[] args,
			int timeout) {
	
		int invocationId = this.invocationId.getAndIncrement();
		
		final FuturePromise<Object> fp;
		GenericFutureListener<Future<Object>> listener;
		
		switch(type) {
			case WITH_RETURN :
				fp = new FuturePromise<Object>(channel.eventLoop(), 
							b -> {
								ofNullable(pending.remove(invocationId))
									.ifPresent(pc -> pc.pendingTimeout.cancel());
								cancelCall(invocationId, b);
							});
				
				Timeout pendingTimeout = timer.newTimeout(t -> {
					pending.remove(invocationId);
					fp.fail(new ServiceException("There was no response from the remote service " + endpointId +
							" when calling " + methodNames.get(methodId), REMOTE, 
							new TimeoutException("The invocation timed out with no response.")));
					cancelCall(invocationId, true);
				}, timeout, TimeUnit.MILLISECONDS);
				
				pending.put(invocationId, new PendingCall(fp, serializer, pendingTimeout, methodId));
				
				listener = f -> ofNullable(f.cause())
						.ifPresent(c -> {
							ofNullable(pending.remove(invocationId))
								.ifPresent(pc -> pc.pendingTimeout.cancel());
							fp.fail(new ServiceException("Unable to invoke " + methodNames.get(methodId) +
									" on service " + endpointId + " due to a communications failure" , REMOTE, c));
						});
				break;
			case FIRE_AND_FORGET :
				fp = null;
				listener = f -> {
					if(!f.isSuccess()) {
						LOG.warn("The fire and forget invocation for service {}  method {} failed to send",
								endpointId, methodNames.get(methodId));
//						LOG.warn("The fire and forget invocation for service {}  method {} failed to send",
//								endpointId, methodNames.get(methodId), f.cause());
					}
				};
				break;
			default :
				throw new IllegalArgumentException(type.name());
		}
		
		ByteBuf ioBuffer = allocator.ioBuffer();
		try {
			writeHeader(ioBuffer, type.getCommand(), invocationId);
			ioBuffer.writeShort(methodId);
			serializer.serializeArgs(ioBuffer, args == null ? EMPTY_ARGS : args);
			channel.writeAndFlush(ioBuffer).addListener(listener);
		} catch (IOException ioe) {
			ioBuffer.release();
			pending.remove(invocationId);
			fp.fail(new ServiceException("Unable to invoke " + methodNames.get(methodId) +
					" on service " + endpointId + " due to a communications failure" , REMOTE, ioe));
		}
		return fp;
	}

	private void cancelCall(int invocationId, boolean interrupt) {
		ByteBuf cancelBuf = allocator.ioBuffer(32);
		writeHeader(cancelBuf, CANCEL, invocationId);
		channel.writeAndFlush(cancelBuf.writeBoolean(interrupt), channel.voidPromise());
	}
	
	private void writeHeader(ByteBuf buffer, int callType, int id) {
		int index = buffer.writerIndex();
		buffer.writeBytes(template)
			.setByte(index + 4, callType)
			.writeInt(id);
	}
	
	void response(int id, byte type, ByteBuf buffer) {
		PendingCall pc = pending.remove(id);
		if(pc != null) {
			pc.pendingTimeout.cancel();
			try {
				switch(type) {
					case SUCCESS_RESPONSE :
						pc.promise.resolve(pc.serializer.deserializeReturn(buffer));
						break;
					case FAILURE_RESPONSE :
						pc.promise.fail((Throwable) pc.serializer.deserializeReturn(buffer));
						break;
					case FAILURE_NO_SERVICE :
						pc.promise.fail(new ServiceException("The service could not be found", REMOTE, 
								new MissingServiceException()));
						ir.stream()
							.forEach(ImportRegistration::close);
						break;
					case FAILURE_NO_METHOD :
						pc.promise.fail(new ServiceException("The service method could not be found", REMOTE, 
								new MissingMethodException(methodNames.get(pc.methodId))));
						ir.stream()
							.forEach(ImportRegistration::close);
						break;
					case FAILURE_TO_DESERIALIZE:
						pc.promise.fail(new ServiceException("The remote invocation failed because the server could not deserialise the method arguments", REMOTE, 
								new IllegalArgumentException(readLengthPrefixedUtf8(buffer))));
						break;
					case FAILURE_TO_SERIALIZE_SUCCESS:
						pc.promise.fail(new ServiceException("The remote invocation succeeded but the server could not serialise the method return value", REMOTE, 
								new IllegalArgumentException(readLengthPrefixedUtf8(buffer))));
						break;
					case FAILURE_TO_SERIALIZE_FAILURE:
						pc.promise.fail(new ServiceException("The remote invocation failed and the server could not serialise the failure reason", REMOTE, 
								new IllegalArgumentException(readLengthPrefixedUtf8(buffer))));
						break;
					default :
						LOG.error("There was a serious error trying to interpret a remote invocation response for service {} method {}. The response code {} was unrecognised.", 
								new Object[] {endpointId, methodNames.get(pc.methodId), type});
						pc.promise.fail(new UnknownResponseTypeException(type));
				}
			} catch (Exception e) {
				LOG.error("There was a serious error trying to interpret a remote invocation response for service " 
						+ endpointId + " method " + methodNames.get(pc.methodId), e);
				pc.promise.fail(e);
				return;
			}
		} else {
			LOG.info("A remote invocation response was receieved that did not match a known request - it is possible that the request timed out.");
		}
	}
	
	@Override
	public void close(ImportRegistrationImpl impl) {
		boolean terminate = false;
		synchronized (this) {
			ir.remove(impl);
			if(ir.isEmpty()) {
				closed = true;
				terminate = true;
			}
		}
		if(terminate) {
			clientConnectionManager.notifyClosing(endpointId, this);
		}
	}
	
	private void closePending() {
		pending.values().forEach(pc -> {
			pc.pendingTimeout.cancel();
			
			ServiceException failure = new ServiceException("Unable to invoke " + 
					methodNames.get(pc.methodId) + " on service " + endpointId + 
					" due to a communications failure", REMOTE, 
					new IllegalStateException("The communications channel is closed"));
			pc.promise.fail(failure);
		});
	}

	Channel getChannel() {
		return channel;
	}

	@Override
	public void addImportRegistration(ImportRegistrationImpl irImpl) {
		boolean fail = false;
		synchronized (this) {
			if(closed) {
				fail = true;
			} else {
				ir.add(irImpl);
			}
		}
		if(fail) {
			throw new ServiceException("The handler for the import has been asynchronously closed", ServiceException.REMOTE);
		}
	}
	
	void failAll(Exception e) {
		List<ImportRegistrationImpl> toFail;
		synchronized (this) {
			if(closed) {
				return;
			}
			closed = true;
			toFail = ir.stream().collect(Collectors.toList());
		}
		toFail.stream().forEach(i -> i.asyncFail(e));
		closePending();
	}
 }
