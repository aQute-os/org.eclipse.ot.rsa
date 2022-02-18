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

import static com.paremus.dosgi.net.client.ClientMessageType.STREAMING_RESPONSE_OPEN;
import static org.osgi.framework.ServiceException.REMOTE;

import java.util.UUID;
import java.util.function.Consumer;

import org.osgi.framework.ServiceException;

import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class BeginStreamingInvocation extends AbstractClientInvocationWithResult {

	private final EventExecutor executor;
	private final Consumer<Object> dataConsumer;
	private final Consumer<Exception> closeConsumer;

	private final Future<?> close;


	public BeginStreamingInvocation(UUID serviceId, int callId, Serializer serializer,
			EventExecutor executor, Consumer<Object> dataConsumer,
			Consumer<Exception> closeConsumer, Future<?> close) {
		super(STREAMING_RESPONSE_OPEN, serviceId, callId, serializer);
		this.executor = executor;
		this.dataConsumer = dataConsumer;
		this.closeConsumer = closeConsumer;
		this.close = close;
	}

	public Consumer<Object> getDataConsumer() {
		return dataConsumer;
	}

	public Consumer<Exception> getCloseConsumer() {
		return closeConsumer;
	}

	@Override
	public long getTimeout() {
		return 0;
	}

	@Override
	public void fail(Throwable t) {
		if(executor.inEventLoop()) {
			internalError(t);
		} else {
			try {
				executor.execute(() -> internalError(t));
			} catch (Exception e) {
				streamError(t, e);
			}
		}
	}

	private void internalError(Throwable t) {
		Exception toSend = t == null ? null : t instanceof Exception ? (Exception) t :
			new ServiceException("An incompatible Exception type was receieved", ServiceException.REMOTE, t);
		closeConsumer.accept(toSend);
	}

	private void streamError(Throwable t, Exception e) {
		e.addSuppressed(t);
		closeConsumer.accept(new ServiceException("An error occurred with the data stream",
				ServiceException.REMOTE, e));
	}

	@Override
	public void fail(ByteBuf b) throws Exception {

		Throwable o;
		try {
			o = (Throwable) getSerializer().deserializeReturn(b);
		} catch (Exception e) {
			o = new ServiceException(
					"Failed to deserialize the remote exception value", ServiceException.REMOTE, e);
		}

		fail(o);
	}

	@Override
	public void data(ByteBuf b) throws Exception {
		try {
			internalData(getSerializer().deserializeReturn(b));
		} catch (Exception e) {
			internalError(new ServiceException(
					"Failed to deserialize the remote return value", ServiceException.REMOTE, e));
			return;
		}
	}

	private void internalData(Object o) {
		if(executor.inEventLoop()) {
			dataConsumer.accept(o);
		} else {
			try {
				executor.execute(() -> dataConsumer.accept(o));
			} catch (Exception e) {
				internalError(new ServiceException(
						"Failed to deserialize the remote return value", ServiceException.REMOTE, e));
			}
		}
	}

	@Override
	public void addCompletionListener(GenericFutureListener<Future<Object>> listener) {
		close.addListener(listener);
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) {
		writeHeader(buffer);
		writeLength(buffer);
		promise.addListener(f -> {
				if(!f.isSuccess()) {
					closeConsumer.accept(new ServiceException("Unable to open the remote stream " +
							getServiceId() + " due to a communications failure" , REMOTE, f.cause()));
				}
			});
	}
}
