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

import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_NO_METHOD;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_RESPONSE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_DESERIALIZE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_FAILURE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_TO_SERIALIZE_SUCCESS;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.FAILURE_UNKNOWN;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.SUCCESS_RESPONSE;
import static io.netty.buffer.StringHelper.writeLengthPrefixedUtf8;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.osgi.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.serialize.Serializer;
import com.paremus.dosgi.net.wireformat.Protocol_V1;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;

public class ServiceInvoker {

	private static final Logger LOG = LoggerFactory.getLogger(ServiceInvoker.class);
	
	private interface ReturnHandler {
		void accept(Channel channel, int callId, Object returnValue);
	}
	
	private final byte[] header;
	
	private final Serializer serializer;

	private final Object service;
	
	private final IntObjectMap<Method> methodCache;
	
	private final EventExecutorGroup worker;
	
	private final Predicate<Object> isPromise;
	private final ReturnHandler handlePromiseReturn;
	
	public ServiceInvoker(UUID serviceId, Serializer serializer, Object service, 
			Map<Integer, Method> methodMappings, EventExecutorGroup serverWorkers) {
		//Always ensure we always have a map size
		this.methodCache = methodMappings.isEmpty() ? new EmptyMap<Method>() :
			new IntObjectHashMap<Method>( methodMappings.size() + 1);
		methodMappings.entrySet().stream()
			.forEach(e -> methodCache.put(e.getKey(), e.getValue()));

		this.worker = serverWorkers;
		
		Predicate<Object> promiseTest;
		ReturnHandler promiseReturnHandler;
		
		try {
			Class<?> promiseClass = service.getClass().getClassLoader().loadClass(Promise.class.getName());
			Method onResolve = promiseClass.getMethod("onResolve", Runnable.class);
			Method getValue = promiseClass.getMethod("getValue");
			promiseTest = promiseClass::isInstance;
			promiseReturnHandler = (ch,id,o) -> sendPromiseReturn(ch, id, o, onResolve, getValue);
		} catch (Exception e) {
			promiseTest = x -> false;
			promiseReturnHandler = (ch,id,o) -> sendInternalFailureResponse(ch, id, FAILURE_TO_SERIALIZE_SUCCESS, 
					new IllegalArgumentException(String.format("The return value %s could not be processed", o)));
		}
		
		this.isPromise = promiseTest;
		this.handlePromiseReturn = promiseReturnHandler;
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (DataOutputStream dos = new DataOutputStream(baos)) {
			dos.write(Protocol_V1.VERSION);
			for(int i = 0; i < Protocol_V1.SIZE_WIDTH_IN_BYTES; i++) {
				dos.write(0);
			}
			dos.writeByte(SUCCESS_RESPONSE);
			dos.writeLong(serviceId.getMostSignificantBits());
			dos.writeLong(serviceId.getLeastSignificantBits());
		} catch (IOException ioe) {
			LOG.error("Unable to create the header template for the RSA requests");
			throw new RuntimeException(ioe);
		}
		
		this.header = baos.toByteArray();
		this.serializer = serializer;
		this.service = service;
	}

	public void execute(ByteBuf buf, int callId) {
		worker.execute(() -> {
			Method m;
			Object[] args;
			try {
				m = methodCache.get(buf.readUnsignedShort());
				if(m == null) {
					return;
				}
				args = serializer.deserializeArgs(buf);
			} catch (Exception e) {
				LOG.warn("Unable to start a fire and forget task because the method and arguments could not be deserialized", 
						e);
				return;
			} finally {
				buf.release();
			}
			try {
				m.invoke(service, args);
			} catch (InvocationTargetException ite) {
				if(LOG.isDebugEnabled()) {
					LOG.debug("The fire and forget task calling method " + m.toGenericString() + 
						" on object " + service + "failed", ite.getTargetException());
				}
			} catch (Exception e) {
				LOG.warn("The fire and forget task calling method " + m.toGenericString() + 
						" on object " + service + "encountered a serious error", e);
			}
		});
	}

	private void sendReturn(Channel channel, int callId, byte type, Object o) {
		ByteBuf buf = channel.alloc().ioBuffer()
				.writeBytes(header)
				.writeInt(callId)
				.setByte(4, type);
		try {
			serializer.serializeReturn(buf, o);
			channel.writeAndFlush(buf, channel.voidPromise());
		} catch (IOException e1) {
			buf.release();
			sendInternalFailureResponse(channel, callId, type == SUCCESS_RESPONSE ? 
					FAILURE_TO_SERIALIZE_SUCCESS : FAILURE_TO_SERIALIZE_FAILURE, e1);
		}
	}

	private void sendPromiseReturn(Channel ch, int id, Object o, Method onResolve, Method getValue) {
		try {
			onResolve.invoke(o, (Runnable) () -> {
				try {
					sendReturn(ch, id, SUCCESS_RESPONSE, getValue.invoke(o));
				} catch (InvocationTargetException ite) {
					Throwable t = ite.getCause();
					sendReturn(ch, id, FAILURE_RESPONSE, t instanceof InvocationTargetException ?
							t.getCause() : t);
				} catch (Exception e) {
					
				}
			});
		} catch (InvocationTargetException ite) {
			sendReturn(ch, id, FAILURE_RESPONSE, ite.getCause());
		} catch (Exception e) {
			sendReturn(ch, id, FAILURE_RESPONSE, e);
		}
	}
	

	void sendInternalFailureResponse(Channel channel, int callId, byte type, Exception e) {
		ByteBuf buf = channel.alloc().ioBuffer(1024);
		try {
			buf.writeBytes(header)
					.writeInt(callId)
					.setByte(4, type);
			if(e != null) {
				try {
					String message = String.valueOf(e.getMessage());
					writeLengthPrefixedUtf8(buf, message.length() > 256 ? 
							new StringBuilder().append(message, 0, 256).append("...") : message);
				} catch (Exception e2) {
					buf.writerIndex(25)
						.writeShort(0);
				}
			}
			channel.writeAndFlush(buf, channel.voidPromise());
		} catch (Exception ex) {
			buf.release();
			LOG.error("The remote service " + service + " is totally unable to respond to a request.", ex);
		}
	}

	public Future<?> call(Channel channel, ByteBuf buf, int callId) {
		
		return worker.submit(() -> {
			Method m;
			Object[] args;
			try {
				m = methodCache.get(buf.readUnsignedShort());
				if(m == null) {
					sendInternalFailureResponse(channel, callId, FAILURE_NO_METHOD, null);
					return;
				}
				try {
					args = serializer.deserializeArgs(buf);
				} catch (Exception e) {
					LOG.warn("Unable to deserialize the method and arguments for a remote call", e);
					sendInternalFailureResponse(channel, callId, FAILURE_TO_DESERIALIZE, e);
					return;
				}
			} catch (Exception e) {
				sendInternalFailureResponse(channel, callId, FAILURE_UNKNOWN, null);
				return;
			} finally {
				buf.release();
			}
			
			try {
				Object toReturn = m.invoke(service, args);
				
				if(isPromise.test(toReturn)) {
					handlePromiseReturn.accept(channel, callId, toReturn);
				} else {
					sendReturn(channel, callId, SUCCESS_RESPONSE, toReturn);
				}
			} catch (InvocationTargetException ite) {
				if(LOG.isDebugEnabled()) {
					LOG.debug("The remote call " + m.toGenericString() + 
						" on object " + service + "failed", ite.getTargetException());
				}
				sendReturn(channel, callId, FAILURE_RESPONSE, ite.getCause());
			} catch (Exception e) {
				LOG.warn("The remote call " + m.toGenericString() + 
						" on object " + service + "encountered a serious error", e);
				sendReturn(channel, callId, FAILURE_RESPONSE, e);
			}
		});
	}
	
	public static class EmptyMap<T> implements IntObjectMap<T> {

		@Override
		public void clear() {}

		@Override
		public boolean containsKey(int x) { 
			return false;
		}

		@Override
		public boolean containsKey(Object key) {
			return false;
		}

		@Override
		public boolean containsValue(Object value) {
			return false;
		}

		@Override
		public T get(int arg0) {
			return null;
		}

		@Override
		public T get(Object key) {
			return null;
		}

		@Override
		public boolean isEmpty() {
			return true;
		}

		@Override
		public T put(int arg0, T arg1) {
			throw new UnsupportedOperationException("This map is always empty");
		}

		@Override
		public T put(Integer key, T value) {
			throw new UnsupportedOperationException("This map is always empty");
		}

		@Override
		public T remove(int arg0) {
			return null;
		}

		@Override
		public T remove(Object key) {
			return null;
		}

		@Override
		public int size() {
			return 0;
		}

		@Override
		public Collection<T> values() {
			return Collections.emptySet();
		}

		@Override
		public void putAll(Map<? extends Integer, ? extends T> m) {
			throw new UnsupportedOperationException("This map is always empty");
		}

		@Override
		public Set<Integer> keySet() {
			return Collections.emptySet();
		}

		@Override
		public Set<java.util.Map.Entry<Integer, T>> entrySet() {
			return Collections.emptySet();
		}

		@Override
		public Iterable<io.netty.util.collection.IntObjectMap.PrimitiveEntry<T>> entries() {
			return Collections.emptySet();
		}
	}
}
