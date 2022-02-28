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
package org.eclipse.ot.rsa.distribution.provider.client;

import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.FIRE_AND_FORGET;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.WITH_RETURN;
import static org.osgi.framework.ServiceException.REMOTE;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.eclipse.ot.rsa.distribution.provider.serialize.CompletedPromise;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.CompletedPromise.State;
import org.osgi.framework.ServiceException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

public class ClientInvocation extends AbstractClientInvocationWithResult {

	private static final Object[] EMPTY_ARGS = new Object[0];

	private final int methodId;

	private final Object[] args;

	private final int[] promiseArgs;

	private final int[] completableFutureArgs;

	private final Function<Object, Future<Object>> toNettyPromiseAdapter;

	private final Promise<Object> result;

	private final AtomicLong timeout;

	private final String methodName;

	public ClientInvocation(boolean withReturn, UUID serviceId, int methodId, int callId,
			Object[] args, int[] promiseArgs, int[] completableFutureArgs,
			Serializer serializer, Function<Object, Future<Object>> toNettyPromiseAdapter,
			Promise<Object> result, AtomicLong timeout, String methodName) {
		super(withReturn ? WITH_RETURN : FIRE_AND_FORGET, serviceId, callId, serializer);

		this.methodId = methodId;
		this.args = args == null ? EMPTY_ARGS : args;
		this.promiseArgs = promiseArgs;
		this.completableFutureArgs = completableFutureArgs;
		this.toNettyPromiseAdapter = toNettyPromiseAdapter;
		this.result = result;
		this.timeout = timeout;
		this.methodName = methodName;
	}

	public ClientInvocation fromTemplate(boolean withReturn, int callId, Object[] args, Promise<Object> result) {
		return new ClientInvocation(withReturn, getServiceId(), getMethodId(), callId, args,
				promiseArgs, completableFutureArgs, getSerializer(), toNettyPromiseAdapter,
				result, timeout, methodName);
	}

	public final Promise<Object> getResult() {
		return result;
	}

	public final int getMethodId() {
		return methodId;
	}

	public final Object[] getArgs() {
		return args;
	}

	public final int[] getPromiseArgs() {
		return promiseArgs;
	}

	public final int[] getCompletionStageArgs() {
		return completableFutureArgs;
	}

	public final Function<Object, Future<Object>> getToNettyPromiseAdapter() {
		return toNettyPromiseAdapter;
	}

	@Override
	public final long getTimeout() {
		return timeout.get();
	}

	public final String getMethodName() {
		return methodName;
	}

	@Override
	public void fail(Throwable e) {
		result.tryFailure(e);
	}

	@Override
	public void fail(ByteBuf b) throws Exception {

		Throwable o;
		try {
			o = (Throwable) getSerializer().deserializeReturn(b);
		} catch (Exception e) {
			o = new ServiceException(
					"Failed to deserialize the remote return value", ServiceException.REMOTE, e);
		}

		fail(o);
	}

	@Override
	public void data(ByteBuf b) throws Exception {

		Object o;
		try {
			o = getSerializer().deserializeReturn(b);
		} catch (Exception e) {
			result.tryFailure(new ServiceException(
					"Failed to deserialize the remote return value", ServiceException.REMOTE, e));
			return;
		}

		result.trySuccess(o);

	}

	@Override
	public void addCompletionListener(GenericFutureListener<Future<Object>> listener) {
		result.addListener(listener);
	}

	@Override
	public void write(ByteBuf buffer, ChannelPromise promise) throws IOException {
		writeHeader(buffer);
		buffer.writeShort(methodId);

		Object[] args = getTransformedArgs(promise);

		getSerializer().serializeArgs(buffer, args);

		writeLength(buffer);

		promise.addListener(f -> {
				if(f.isSuccess()) {
					result.addListener(r -> {
						if(r.isCancelled()) {
							Channel channel = ((ChannelPromise)f).channel();
							channel.writeAndFlush(new InvocationCancellation(
								getServiceId(), getCallId(), true), channel.voidPromise());
						}
					});
				} else {
					result.tryFailure(new ServiceException("Unable to invoke the remote service " +
							getServiceId() + " due to a communications failure" , REMOTE, f.cause()));
				}
			});
	}

	private Object[] getTransformedArgs(ChannelPromise promise) {

		for(int i : completableFutureArgs) {
			Future<Object> adaptedArg = adaptCompletionStage((CompletionStage<?>)args[i]);
			args[i] = transformAsyncArg(promise, adaptedArg, i);
		}

		for(int i : promiseArgs) {
			Future<Object> adaptedArg = toNettyPromiseAdapter.apply(args[i]);
			args[i] = transformAsyncArg(promise, adaptedArg, i);
		}

		return args;
	}

	private Future<Object> adaptCompletionStage(CompletionStage<?> cf) {

		Promise<Object> p = ImmediateEventExecutor.INSTANCE.newPromise();

		cf.whenComplete((s,t) -> {
				if(t != null) {
					p.setFailure(t);
				} else {
					p.setSuccess(s);
				}
			});

		return p;
	}

	private Object transformAsyncArg(ChannelPromise promise,
			Future<Object> arg, int i) {
		Object toReturn = null;
		if(arg.isDone()) {
			toReturn = fastPath(arg);
		} else {
			promise.addListener(x -> {
					// TODO log this, and only run on success!
					if(x.isSuccess()) {
						arg.addListener(f -> {
							boolean success = f.isSuccess();
							promise.channel().writeAndFlush(new AsyncArgumentCompletion(success, this,
									i, success ? f.getNow() : f.cause()));
						});
					}
				});
		}
		return toReturn;
	}

	private Object fastPath(Future<Object> arg) {
		CompletedPromise cp = new CompletedPromise();
		if(arg.isSuccess()) {
			cp.state = State.SUCCEEDED;
			cp.value = arg.getNow();
		} else {
			cp.state = State.FAILED;
			cp.value = arg.cause();
		}
		return cp;
	}
}
