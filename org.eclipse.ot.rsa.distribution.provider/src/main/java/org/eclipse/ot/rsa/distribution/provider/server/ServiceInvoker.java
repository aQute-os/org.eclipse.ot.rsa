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
package org.eclipse.ot.rsa.distribution.provider.server;

import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.ARGS_SERIALIZATION_ERROR;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.ASYNC_PARAM_ERROR;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.NO_METHOD;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.SERVER_OVERLOADED;
import static org.eclipse.ot.rsa.distribution.provider.server.ServerMessageType.UNKNOWN_ERROR;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage;
import org.eclipse.ot.rsa.distribution.provider.promise.PromiseFactory;
import org.eclipse.ot.rsa.distribution.provider.pushstream.PushStreamFactory;
import org.eclipse.ot.rsa.distribution.provider.pushstream.PushStreamFactory.DataStreamFactory;
import org.eclipse.ot.rsa.distribution.provider.serialize.CompletedPromise;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;
import org.osgi.framework.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

public class ServiceInvoker {

	private static final Logger LOG = LoggerFactory.getLogger(ServiceInvoker.class);

	private interface ArgsHandler {
		ArgumentResolver[] process(Object[] rawArgs);
	}

	private interface ArgumentProcessor {
		ArgumentResolver process(Object[] rawArgs, int index);
	}

	private interface ArgumentResolver {
		void resolve(boolean succcess, Object result);
	}

	private static class RemoteInvocation {
		public final ArgumentResolver[]	resolvers;
		public final Future<?>			runningTask;
		public final Timeout			timeout;

		public RemoteInvocation(ArgumentResolver[] resolvers, Future<?> runningTask, Timeout timeout) {
			this.resolvers = resolvers;
			this.runningTask = runningTask;
			this.timeout = timeout;
		}
	}

	private static final ArgumentResolver[]					EMPTY_RESOLVER_ARRAY		= new ArgumentResolver[0];
	private static final ArgsHandler						DEFAULT_ARGS_HANDLER		= o -> EMPTY_RESOLVER_ARRAY;

	private final RemotingProvider							remotingProvider;
	private final Serializer								serializer;

	private final Object									service;

	private final Method[]									methodCache;
	private final ReturnHandler[]							returnHandlers;

	private final EventExecutorGroup						worker;
	private final Timer										timer;

	private final UUID										serviceId;
	private final ArgsHandler[]								argsHandlers;

	private final Function<EventExecutor, Promise<Object>>	nettyPromiseCreator;
	private final Function<Object, Future<Object>>			toNettyFutureAdapter;
	private final Function<Future<?>, Object>				fromNettyFutureAdapter;

	private final DataStreamFactory							pushStreamConnector;
	private final DataStreamFactory							pushEventSourceConnector;

	private final Future<?>									completeAction;

	private final IntObjectMap<RemoteInvocation>			runningRemoteInvocations	= new IntObjectHashMap<>();

	public ServiceInvoker(RemotingProvider rp, UUID serviceId, Serializer serializer, Object service, Method[] methods,
		EventExecutorGroup serverWorkers, Timer timer) {

		this.remotingProvider = rp;
		this.serviceId = serviceId;
		this.serializer = serializer;
		this.service = service;
		this.methodCache = Arrays.copyOf(methods, methods.length);
		this.worker = serverWorkers;
		this.timer = timer;
		completeAction = serverWorkers.next()
			.newSucceededFuture(null);

		Function<EventExecutor, Promise<Object>> nettyPromiseCreator = null;
		Function<Object, Future<Object>> toNettyFutureAdapter = null;
		Function<Future<?>, Object> fromNettyFutureAdapter = null;

		ClassLoader serviceClassLoader = service.getClass()
			.getClassLoader();
		if (serviceClassLoader == null) {
			serviceClassLoader = ClassLoader.getSystemClassLoader();
		}

		try {
			Class<?> promise = serviceClassLoader.loadClass("org.osgi.util.promise.Promise");
			nettyPromiseCreator = PromiseFactory.nettyWithOSGi(promise, timer);
			toNettyFutureAdapter = PromiseFactory.osgiToNetty(promise);
			fromNettyFutureAdapter = PromiseFactory.nettyToOSGi(promise, serverWorkers);
		} catch (NoClassDefFoundError | Exception e) {
			LOG.debug("Unable to integrate with promises for the remote service {}", serviceId);
		}

		this.nettyPromiseCreator = nettyPromiseCreator;
		this.toNettyFutureAdapter = toNettyFutureAdapter;
		this.fromNettyFutureAdapter = fromNettyFutureAdapter;

		DataStreamFactory pushStreamConnector = null;

		try {
			Class<?> pushStream = serviceClassLoader.loadClass("org.osgi.util.pushstream.PushStream");
			pushStreamConnector = PushStreamFactory.pushStreamConnector(pushStream, timer);
		} catch (NoClassDefFoundError | Exception e) {
			LOG.debug(
				"Unable to integrate with push streams for the remote service {}. See the debug log for more details",
				serviceId);
		}

		this.pushStreamConnector = pushStreamConnector;

		DataStreamFactory pushEventSourceConnector = null;

		try {
			Class<?> pushEventSource = serviceClassLoader.loadClass("org.osgi.util.pushstream.PushEventSource");
			pushEventSourceConnector = PushStreamFactory.pushEventSourceConnector(pushEventSource, timer);
		} catch (Exception e) {
			LOG.debug("Unable to integrate with push event sources for the remote service {}", serviceId);
		}

		this.pushEventSourceConnector = pushEventSourceConnector;

		this.returnHandlers = new ReturnHandler[methodCache.length];
		this.argsHandlers = new ArgsHandler[methodCache.length];

		setupReturnHandlers(methods);
		setupArgsHandlers(methods, fromNettyFutureAdapter);
	}

	private void setupReturnHandlers(Method[] methods) {
		ReturnHandler basicReturnHandler = new BasicReturnHandler(serviceId, serializer, completeAction);
		ReturnHandler futureReturnHandler = new JavaFutureReturnHandler(serviceId, serializer, completeAction, worker);
		ReturnHandler completionStageReturnHandler = new JavaCompletionStageReturnHandler(serviceId, serializer,
			completeAction, worker);
		ReturnHandler promiseReturnHandler = new PromiseReturnHandler(serviceId, serializer, completeAction,
			toNettyFutureAdapter);

		ReturnHandler pushStreamReturnHandler = new StreamReturnHandler(serviceId, serializer, completeAction,
			remotingProvider, pushStreamConnector);

		ReturnHandler pushEventSourceReturnHandler = new StreamReturnHandler(serviceId, serializer, completeAction,
			remotingProvider, pushEventSourceConnector);

		Arrays.setAll(returnHandlers, i -> {

			ReturnHandler handler = basicReturnHandler;

			Class<?> returnType = methods[i].getReturnType();

			if (PromiseFactory.isPromise(returnType)) {
				handler = promiseReturnHandler;
			} else if (PushStreamFactory.isPushStream(returnType)) {
				handler = pushStreamReturnHandler;
			} else if (PushStreamFactory.isPushEventSource(returnType)) {
				handler = pushEventSourceReturnHandler;
			} else if (CompletionStage.class.isAssignableFrom(returnType)) {
				handler = completionStageReturnHandler;
			} else if (java.util.concurrent.Future.class.isAssignableFrom(returnType)) {
				handler = futureReturnHandler;
			}

			return handler;
		});
	}

	private void setupArgsHandlers(Method[] methods, Function<Future<?>, Object> fromNettyFutureAdapter) {
		ArgumentProcessor futureArgHandler = this::handleFutureArg;

		ArgumentProcessor promiseArgHandler = this::handlePromiseArg;

		for (int i = 0; i < methods.length; i++) {

			Class<?>[] parameters = methods[i].getParameterTypes();

			boolean needsHandler = false;
			ArgumentProcessor[] processors = new ArgumentProcessor[parameters.length];

			for (int j = 0; j < parameters.length; j++) {
				Class<?> parameterType = parameters[j];

				ArgumentProcessor ap = null;

				if (PromiseFactory.isPromise(parameterType)) {
					ap = promiseArgHandler;
				} else if (CompletableFuture.class.equals(parameterType) || CompletionStage.class.equals(parameterType)
					|| java.util.concurrent.Future.class.equals(parameterType)) {
					ap = futureArgHandler;
				}

				if (ap != null) {
					processors[j] = ap;
					needsHandler = true;
				}
			}

			argsHandlers[i] = needsHandler ? o -> {
				ArgumentResolver[] toReturn = new ArgumentResolver[processors.length];
				for (int k = 0; k < processors.length; k++) {
					toReturn[k] = processors[k] == null ? null : processors[k].process(o, k);
				}
				return toReturn;
			} : DEFAULT_ARGS_HANDLER;
		}
	}

	private ArgumentResolver handleFutureArg(Object[] o, int index) {

		ArgumentResolver ar;

		CompletableFuture<Object> cf = new CompletableFuture<>();

		if (o[index] instanceof CompletedPromise) {
			CompletedPromise cp = (CompletedPromise) o[index];
			switch (cp.state) {
				case FAILED :
					cf.completeExceptionally((Throwable) cp.value);
					break;
				case SUCCEEDED :
					cf.complete(cp.value);
					break;
				default :
					throw new IllegalArgumentException(cp.state.name());
			}
			ar = null;
		} else {
			ar = (s, v) -> {
				if (!cf.isDone()) {
					if (s) {
						cf.complete(v);
					} else {
						cf.completeExceptionally((Throwable) v);
					}
				}
			};
		}
		o[index] = cf;
		return ar;
	}

	private ArgumentResolver handlePromiseArg(Object[] o, int index) {

		ArgumentResolver ar;

		Promise<Object> p = nettyPromiseCreator.apply(worker.next());

		if (o[index] instanceof CompletedPromise) {
			CompletedPromise cp = (CompletedPromise) o[index];
			switch (cp.state) {
				case FAILED :
					p.setFailure((Throwable) cp.value);
					break;
				case SUCCEEDED :
					p.setSuccess(cp.value);
					break;
				default :
					throw new IllegalArgumentException(cp.state.name());
			}
			ar = null;
		} else {
			ar = (s, v) -> {
				if (!p.isDone()) {
					if (s) {
						p.setSuccess(v);
					} else {
						p.setFailure((Throwable) v);
					}
				}
			};
		}
		o[index] = fromNettyFutureAdapter.apply(p);
		return ar;
	}

	private void sendInternalFailureResponse(Channel channel, int callId, ServerMessageType type, Exception e) {
		if (channel != null) {
			try {
				AbstractRSAMessage<ServerMessageType> message;
				if (e != null) {
					String error = String.valueOf(e.getMessage());
					error = error.length() > 256 ? new StringBuilder(260).append(error, 0, 256)
						.append("...")
						.toString() : error;
					message = new ServerErrorMessageResponse(type, serviceId, callId, error);
				} else {
					message = new ServerErrorResponse(type, serviceId, callId);
				}
				channel.writeAndFlush(message, channel.voidPromise());
			} catch (Exception ex) {
				LOG.error("The remote service " + service + " is totally unable to respond to a request.", ex);
			}
		}
	}

	public void call(Channel channel, ByteBuf buf, int callId) {
		Method m;
		ReturnHandler returnHandler;
		Object[] args;
		ArgumentResolver[] resolvers;

		try {
			ArgsHandler argsPostProcessor;
			try {
				int idx = buf.readUnsignedShort();
				m = methodCache[idx];
				returnHandler = returnHandlers[idx];
				argsPostProcessor = argsHandlers[idx];
			} catch (ArrayIndexOutOfBoundsException aioobe) {
				sendInternalFailureResponse(channel, callId, NO_METHOD, null);
				return;
			}

			try {
				args = serializer.deserializeArgs(buf);
				resolvers = argsPostProcessor.process(args);
			} catch (Exception e) {
				LOG.warn("Unable to deserialize the method and arguments for a remote call", e);
				sendInternalFailureResponse(channel, callId, ARGS_SERIALIZATION_ERROR, e);
				return;
			}
		} catch (Exception e) {
			LOG.warn("An unknown error occurred setting up a remote call for service {}", serviceId, e);
			sendInternalFailureResponse(channel, callId, UNKNOWN_ERROR, e);
			return;
		}
		doCall(channel, callId, m, returnHandler, args, resolvers);
	}

	private void doCall(Channel channel, int callId, Method m, ReturnHandler returnHandler, Object[] args,
		ArgumentResolver[] resolvers) {
		try {
			Future<Future<?>> f = worker.submit(() -> invokeAndRespond(channel, callId, m, args, returnHandler));
			// TODO Use the real timeout
			Timeout t = timer.newTimeout(x -> timeoutAction(resolvers, f), 30, TimeUnit.SECONDS);
			RemoteInvocation ri = new RemoteInvocation(resolvers, f, t);
			synchronized (runningRemoteInvocations) {
				runningRemoteInvocations.put(callId, ri);
			}
			f.addListener(g -> {
				Future<?> gate;
				if (g.isSuccess()) {
					gate = (Future<?>) g.getNow();
				} else {
					gate = g;
				}
				if (gate.isDone()) {
					onCallCompletion(gate, callId, ri);
				} else {
					gate.addListener(c -> onCallCompletion(c, callId, ri));
				}
			});
		} catch (RejectedExecutionException ree) {
			LOG.warn("The RSA distribution provider is overloaded and rejecting calls", ree);
			sendInternalFailureResponse(channel, callId, SERVER_OVERLOADED, ree);
		}
	}

	private void onCallCompletion(Future<?> completedFuture, int callId, RemoteInvocation ri) {
		ri.timeout.cancel();
		synchronized (runningRemoteInvocations) {
			runningRemoteInvocations.remove(callId, ri);
		}
		if (!completedFuture.isCancelled()) {
			ServiceException exception = new ServiceException(
				"The asynchronous argument was not resolved before the remote call completed", ServiceException.REMOTE,
				new IllegalStateException());
			failAsyncArgs(ri.resolvers, exception);
		}
	}

	private void timeoutAction(ArgumentResolver[] resolvers, Future<?> f) {
		if (f.cancel(true)) {
			ServiceException exception = new ServiceException(
				"The asynchronous argument was not resolved before the remote call timed out", ServiceException.REMOTE,
				new TimeoutException());
			failAsyncArgs(resolvers, exception);
		}
	}

	private void failAsyncArgs(ArgumentResolver[] resolvers, Exception exception) {
		if (resolvers != null) {
			for (ArgumentResolver ar : resolvers) {
				if (ar != null) {
					ar.resolve(false, exception);
				}
			}
		}
	}

	private Future<?> invokeAndRespond(Channel channel, int callId, Method m, Object[] args, ReturnHandler handler) {
		Future<?> toReturn = completeAction;
		try {
			Object result = m.invoke(service, args);
			if (channel != null) {
				toReturn = handler.success(channel, callId, result);
			}
		} catch (InvocationTargetException ite) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("The remote call " + m.toGenericString() + " on object " + service + "failed",
					ite.getTargetException());
			}
			if (channel != null) {
				toReturn = handler.failure(channel, callId, ite.getTargetException());
			}
		} catch (Exception e) {
			LOG.warn("The remote call " + m.toGenericString() + " on object " + service + "encountered a serious error",
				e);
			if (channel != null) {
				toReturn = handler.failure(channel, callId, e);
			}
		}
		return toReturn;
	}

	public void close(Channel channel) {
		List<RemoteInvocation> runningTasks;
		synchronized (runningRemoteInvocations) {
			runningTasks = new ArrayList<>(runningRemoteInvocations.values());
			runningRemoteInvocations.clear();
		}
		ServiceException exception = new ServiceException("The remote communications channel is closing",
			ServiceException.REMOTE, new IOException());
		runningTasks.forEach(ri -> {
			ri.runningTask.cancel(true);
			failAsyncArgs(ri.resolvers, exception);
		});
	}

	public void cancel(int callId, boolean readBoolean) {
		RemoteInvocation ri;
		synchronized (runningRemoteInvocations) {
			ri = runningRemoteInvocations.get(callId);
		}

		if (ri != null) {
			ri.runningTask.cancel(true);
			failAsyncArgs(ri.resolvers,
				new ServiceException("The asynchronous argument was not resolved before the remote call was cancelled",
					ServiceException.REMOTE, new CancellationException()));
		}
	}

	public void asyncParam(Channel channel, byte command, int callId, short idx, ByteBuf buf) {
		RemoteInvocation ri;
		synchronized (runningRemoteInvocations) {
			ri = runningRemoteInvocations.get(callId);
		}

		if (ri != null) {
			try {
				ArgumentResolver resolver;
				Object value;

				try {
					resolver = ri.resolvers[idx];
				} catch (ArrayIndexOutOfBoundsException aioobe) {
					throw new ServiceException("There is no asynchronous argument to complete", ServiceException.REMOTE,
						aioobe);
				}

				try {
					value = serializer.deserializeReturn(buf);
				} catch (Exception e) {
					LOG.warn("Unable to deserialize the asynchronous value for a remote call", e);
					throw new ServiceException("Unable to deserialize the asynchronous value for argument " + idx,
						ServiceException.REMOTE, e);
				}

				switch (command) {
					case Protocol_V2.ASYNC_METHOD_PARAM_DATA :
						resolver.resolve(true, value);
						break;
					case Protocol_V2.ASYNC_METHOD_PARAM_FAILURE :
						resolver.resolve(false, value);
						break;
					default :
						throw new ServiceException("Not an asynchronous parameter command " + command,
							ServiceException.REMOTE);
				}
			} catch (Exception e) {
				LOG.warn("An unknown error occurred setting up a remote call for service {}", serviceId, e);
				sendInternalFailureResponse(channel, callId, ASYNC_PARAM_ERROR, e);
				ri.runningTask.cancel(true);
				failAsyncArgs(ri.resolvers, e);
				return;
			}
		}
	}
}
