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
package com.paremus.dosgi.net.proxy;

import static com.paremus.dosgi.net.proxy.ClientServiceFactory.ASYNC_DELEGATE_TYPE;
import static com.paremus.dosgi.net.wireformat.Protocol_V1.toSignature;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.IntStream.Builder;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceException;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.client.ClientInvocation;
import com.paremus.dosgi.net.client.EndStreamingInvocation;
import com.paremus.dosgi.net.impl.ImportRegistrationImpl;
import com.paremus.dosgi.net.promise.PromiseFactory;
import com.paremus.dosgi.net.pushstream.PushStreamFactory;
import com.paremus.dosgi.net.serialize.Serializer;

import io.netty.channel.Channel;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

/**
 * A generic proxy invocation handler superclass that catches all exceptions raised by a
 * distribution provider and rethrows those deemed "fatal" as a neat OSGi ServiceException
 * with type REMOTE. Exceptions not indicating transport-level errors (e.g. remote NPE,
 * IllegalArgumentException etc.) are forwarded to the caller.
 */
public class ServiceInvocationHandler implements InvocationHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ServiceInvocationHandler.class);

    private final ImportRegistrationImpl _importRegistration;
    private final Channel _channel;
    private final EventExecutorGroup _executor;
    private final Timer _timer;
    private final Serializer _serializer;
    private final IntSupplier _callIdGenerator;

    private final Future<Boolean> _trueReturn;
    private final Future<Boolean> _falseReturn;

    public static interface CallHandler {
    	Future<?> handle(boolean withReturn, Object proxy, Method m, Object[] args) throws Exception;
    }

    private static class InvocationInfo {
    	final CallHandler handler;
    	final Function<Future<?>, Object> returnTransformer;

		public InvocationInfo(CallHandler handler, //ArgumentTransformFunction argsTransformer,
				Function<Future<?>, Object> returnTransformer) {
			this.handler = handler;
			this.returnTransformer = returnTransformer;
		}

    }

    private final Map<Method, InvocationInfo> actions = new HashMap<>();

    public ServiceInvocationHandler(ImportRegistrationImpl importRegistration, EndpointDescription endpoint,
    		Bundle callingContext, Class<?> proxyClass, List<Class<?>> interfaces, Class<?> promiseClass, boolean isAsyncDelegate,
    		Class<?> pushStreamClass, Class<?> pushEventSourceClass, Channel channel, Serializer serializer, IntSupplier callIdGenerator,
    		AtomicLong serviceCallTimeout, EventExecutorGroup executor, Timer timer) {
    	_importRegistration = Objects.requireNonNull(importRegistration, "ImportRegistration cannot be null");
        _channel = Objects.requireNonNull(channel, "A communications channel must be supplied");
        _executor = Objects.requireNonNull(executor, "An executor must be supplied");
        _timer = Objects.requireNonNull(timer, "A timer must be supplied");
        _serializer = Objects.requireNonNull(serializer, "A Serializer must be supplied");
        _callIdGenerator = Objects.requireNonNull(callIdGenerator, "A call id generator must be supplied");

        _trueReturn = executor.next().newSucceededFuture(true);
        _falseReturn = executor.next().newSucceededFuture(false);

        Map<String, Integer> reverseMappings = importRegistration.getMethodMappings().entrySet().stream()
        		.collect(Collectors.toMap(Entry::getValue, Entry::getKey));

        Function<Future<?>, Object> promiseTransformer = getPromiseTransformer(promiseClass);

        Function<Future<?>, Object> pushStreamTransformer = getPushStreamTransformer(pushStreamClass);

        Function<Future<?>, Object> pushEventSourceTransformer = getPushEventSourceTransformer(pushEventSourceClass);

        Function<EventExecutor, Promise<Object>> nettyPromiseSupplier = PromiseFactory.nettyPromiseCreator(promiseClass, _timer);

        Function<Object, Future<Object>> nettyFutureAdapter = promiseClass == null ? null :
        	PromiseFactory.toNettyFutureAdapter(promiseClass);

        Set<Method> objectMethods = stream(Object.class.getMethods()).collect(toSet());

        interfaces.stream()
        	.map(Class::getMethods)
        	.flatMap(Arrays::stream)
	        .forEach(m -> {
	        	InvocationInfo action;
	        	if(objectMethods.contains(m)) {
	        		action = OBJECT_DELEGATOR;
	        	} else {
	        		action = getReturnActionFor(m, reverseMappings, promiseClass, promiseTransformer,
	        				serviceCallTimeout, nettyPromiseSupplier, nettyFutureAdapter, pushStreamClass,
	        				pushStreamTransformer, pushEventSourceClass, pushEventSourceTransformer);
	        	}
	        	actions.put(m, action);
	        	//We must also add the concrete type mapping in here for people who do
	        	//reflective lookups on the type for async/execute calls
	        	try {
	        		actions.put(proxyClass.getMethod(m.getName(), m.getParameterTypes()), action);
				} catch (Exception e) {
					LOG.warn("The proxy class was missing a concrete method for " + m.toGenericString(), e);
				}
	        });

        try {
        	actions.put(Object.class.getMethod("equals", Object.class),
        			new InvocationInfo((x,o,m,a) -> proxyEquals(o, a[0]),
        					DEFAULT_RETURN_TRANSFORM));
        	actions.put(Object.class.getMethod("hashCode"),
        			new InvocationInfo((x,o,m,a) -> proxyHashCode(o),
        					DEFAULT_RETURN_TRANSFORM));
        	actions.put(Object.class.getMethod("toString"),
        			new InvocationInfo((x,o,m,a) -> proxyToString(o),
        					DEFAULT_RETURN_TRANSFORM));

        	if(isAsyncDelegate) {
        		Class<?> asyncClass = interfaces.stream()
        			.filter(c -> ASYNC_DELEGATE_TYPE.equals(c.getName()))
        			.findFirst().get();

        		actions.put(asyncClass.getMethod("async", Method.class, Object[].class),
        				new InvocationInfo((x,o,m,a) -> {
        					Method actual = (Method) a[0];
        					InvocationInfo invocationInfo = actions.get(actual);
        					if(invocationInfo == null) {
        						throw new NoSuchMethodException(String.valueOf(actual));
        					} else {
        						return invocationInfo.handler.handle(true, o, actual, (Object[]) a[1]);
        					}
        				}, promiseTransformer));
        		actions.put(asyncClass.getMethod("execute", Method.class, Object[].class),
        				new InvocationInfo((x,o,m,a) -> {
        					Method actual = (Method) a[0];
        					InvocationInfo invocationInfo = actions.get(actual);
        					if(invocationInfo == null) {
        						throw new NoSuchMethodException(String.valueOf(actual));
        					} else {
        						invocationInfo.handler.handle(false, o, actual, (Object[]) a[1]);
        						return _trueReturn;
        					}
        				}, DEFAULT_RETURN_TRANSFORM));
        	}
        } catch (NoSuchMethodException nsme) {
        	throw new IllegalArgumentException("Unable to set up the actions for the proxy for endpoint " + endpoint.getId(), nsme);
        }
    }

	private InvocationInfo getReturnActionFor(Method method, Map<String, Integer> signaturesToIds,
			Class<?> promiseClass, Function<Future<?>, Object> promiseTransform, AtomicLong timeout,
			Function<EventExecutor, Promise<Object>> nettyPromiseSupplier, Function<Object, Future<Object>> nettyFutureAdapter,
			Class<?> pushStreamClass, Function<Future<?>, Object> pushStreamTransformer,
			Class<?> pushEventSourceClass, Function<Future<?>, Object> pushEventSourceTransformer) {

		Integer i = signaturesToIds.get(toSignature(method));

		if(i != null) {
			int methodId = i;
			Function<Future<?>, Object> transformer =
					getReturnTransformer(method, promiseClass, promiseTransform,
							pushStreamClass, pushStreamTransformer, pushEventSourceClass, pushEventSourceTransformer);

			int[] promiseArgs = promiseClass == null ? new int[0] : getArgsOfType(method, promiseClass);
			int[] completableFutureArgs = getArgsOfType(method, CompletableFuture.class, CompletionStage.class);

			UUID id = _importRegistration.getId();
			ClientInvocation template = new ClientInvocation(false, id, methodId, -1, null,
					promiseArgs, completableFutureArgs, _serializer, nettyFutureAdapter, null, timeout, method.toString());

			return new InvocationInfo((w,o,m,a) -> {
					Promise<Object> result = nettyPromiseSupplier.apply(_executor.next());
					_channel.writeAndFlush(template.fromTemplate(
						w, _callIdGenerator.getAsInt(), a, result),
							_channel.newPromise().addListener(f -> {
									if(!f.isSuccess()) {
										result.tryFailure(new ServiceException("Failed to send the remote invocation", ServiceException.REMOTE, f.cause()));
									}
								}));
					return result;
				}, transformer);
		}

		return new InvocationInfo((a,b,c,d) -> {
					throw new NoSuchMethodException("The remote service does not define a method "
							+ method.toGenericString());
				}, UNREACHABLE_RETURN_TRANSFORMER);
	}

	private int[] getArgsOfType(Method method, Class<?>... clazz) {
		Class<?>[] parameterTypes = method.getParameterTypes();

		Builder builder = IntStream.builder();
		for(int i = 0; i < parameterTypes.length; i++) {
			Class<?> toCheck = parameterTypes[i];
			if(stream(clazz).anyMatch(c -> c.equals(toCheck))) {
				builder.accept(i);
			}
		}
		return builder.build().toArray();
	}

	private Function<Future<?>, Object> getReturnTransformer(Method method, Class<?> promiseClass,
			Function<Future<?>, Object> promiseTransform, Class<?> pushStreamClass,
			Function<Future<?>, Object> pushStreamTransformer, Class<?> pushEventSourceClass, Function<Future<?>, Object> pushEventSourceTransformer) {
		Class<?> returnType = method.getReturnType();

		return (promiseClass != null &&
				promiseClass.equals(returnType)) ?
						promiseTransform :
				(pushStreamClass != null &&
				pushStreamClass.equals(returnType)) ?
						pushStreamTransformer :
				(pushEventSourceClass != null &&
				pushEventSourceClass.equals(returnType)) ?
						pushEventSourceTransformer :
				java.util.concurrent.Future.class.equals(returnType) ?
						IDENTITY_RETURN_TRANSFORM :
				CompletableFuture.class.equals(returnType) ||
				CompletionStage.class.equals(returnType) ?
						COMPLETABLE_FUTURE_RETURN_TRANSFORM : DEFAULT_RETURN_TRANSFORM;
	}

	private Function<Future<?>, Object> getPromiseTransformer(Class<?> promiseClass) {
		Function<Future<?>, Object> promiseReturnAction;
		if(promiseClass == null) {
			promiseReturnAction = UNREACHABLE_RETURN_TRANSFORMER;
		} else {
        	try {
        		promiseReturnAction = PromiseFactory.fromNettyFutureAdapter(
        				promiseClass, _executor);
        	} catch (NoClassDefFoundError | Exception e) {
        		throw new RuntimeException("The Promises package is not supported", e);
        	}
        }
		return promiseReturnAction;
	}

	private Function<Future<?>, Object> getPushStreamTransformer(Class<?> pushStreamClass) {
		Function<Future<?>, Object> pushStreamReturnAction;
		if(pushStreamClass == null) {
			pushStreamReturnAction = UNREACHABLE_RETURN_TRANSFORMER;
		} else {
			try {
				pushStreamReturnAction = PushStreamFactory.pushStreamHandler(pushStreamClass,
						_executor, new PushStreamConnector(_channel, _serializer), (key) -> {
							_channel.writeAndFlush(new EndStreamingInvocation(key.getId(), key.getCallId()));
						});
			} catch (NoClassDefFoundError | Exception e) {
				throw new RuntimeException("The PushStream package is not supported", e);
			}
		}
		return pushStreamReturnAction;
	}

	private Function<Future<?>, Object> getPushEventSourceTransformer(Class<?> pushEventSourceClass) {
		Function<Future<?>, Object> pushEventSourceReturnAction;
		if(pushEventSourceClass == null) {
			pushEventSourceReturnAction = UNREACHABLE_RETURN_TRANSFORMER;
		} else {
			try {
				pushEventSourceReturnAction = PushStreamFactory.pushEventSourceHandler(pushEventSourceClass,
						_executor, new PushEventSourceConnector(_timer, _channel, _serializer), (key) -> {});
			} catch (NoClassDefFoundError | Exception e) {
				throw new RuntimeException("The PushStream package is not supported", e);
			}
		}
		return pushEventSourceReturnAction;
	}

	private static final Function<Future<?>, Object> UNREACHABLE_RETURN_TRANSFORMER =
			t -> { throw new IllegalStateException("This transformer should never be called");};

	private static final Function<Future<?>, Object> DEFAULT_RETURN_TRANSFORM = f -> {
			try {
				return f.sync().getNow();
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new ServiceException("Interrupted while waiting for a remote service response",
						ServiceException.REMOTE, ie);
			}
		};

	private static final Function<Future<?>, Object> IDENTITY_RETURN_TRANSFORM = f -> f;

	private static final Function<Future<?>, Object> COMPLETABLE_FUTURE_RETURN_TRANSFORM = f -> {
			CompletableFuture<Object> cf =
					new CompletableFuture<>();
			f.addListener(c -> {
					if(c.isSuccess()) {
						cf.complete(c.getNow());
					} else {
						cf.completeExceptionally(c.cause());
					}
				});
			return cf;
		};

    private static final InvocationInfo OBJECT_DELEGATOR = new InvocationInfo((x,o,m,a) -> {
			try {
				return ImmediateEventExecutor.INSTANCE.newSucceededFuture(m.invoke(o, a));
			} catch (InvocationTargetException ite) {
				throw (Exception) ite.getCause();
			} catch (Exception e) {
				throw e;
			}
		}, DEFAULT_RETURN_TRANSFORM);

    private static final InvocationInfo MISSING_METHOD_HANDLER = new InvocationInfo((x,o,m,a) -> {
			throw new NoSuchMethodException(String.format("The method %s is not known to this handler", m));
		}, UNREACHABLE_RETURN_TRANSFORMER);

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            InvocationInfo info = actions.getOrDefault(method, MISSING_METHOD_HANDLER);
            return info.returnTransformer.apply(info.handler.handle(true, proxy, method, args));
        } catch (Throwable t) {
            // RuntimeExceptions are safe to be thrown
            if (t instanceof RuntimeException) {
                throw t;
            }

            if(t instanceof NoSuchMethodException) {
            	LOG.error("The local service interface contains methods that are not available on the remote object. The client attempted to call {} and so this registration will now be closed.",
            			method.toGenericString());
            	_importRegistration.close();
            	throw new ServiceException("The method invoked is not supported for remote calls. This indicates a version mismatch between the service APIs on the client and server.",
            			ServiceException.REMOTE, t);
            }

            // only propagate declared Exceptions, otherwise the client will see an
            // UndeclaredThrowableException through the proxy call.
            for (Class<?> declared : method.getExceptionTypes()) {
                if (t.getClass().isAssignableFrom(declared)) {
                    throw t;
                }
            }

            throw new ServiceException("Failed to invoke method: " + method.getName(),
                ServiceException.REMOTE, t);
        }
    }

    protected Future<Boolean> proxyEquals(Object proxy, Object other) {
        if (other == null) {
            return _falseReturn;
        }

        if (proxy == other) {
            return _trueReturn;
        }

        if (Proxy.isProxyClass(other.getClass())) {
            return this == Proxy.getInvocationHandler(other) ?
            		_trueReturn : _falseReturn;
        }

        return _falseReturn;
    }

    protected Future<Integer> proxyHashCode(Object proxy) {
        return _executor.next().newSucceededFuture(System.identityHashCode(this));
    }

    protected Future<String> proxyToString(Object proxy) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("Proxy");

        Class<?>[] interfaces = proxy.getClass().getInterfaces();
        if (interfaces.length > 0) {
            List<String> names = new ArrayList<>(interfaces.length);
            for (Class<?> iface : interfaces) {
                names.add(iface.getName());
            }

            sb.append(names.toString());
        }

        sb.append('@');
        sb.append(Integer.toHexString(System.identityHashCode(proxy)));

        return _executor.next().newSucceededFuture(sb.toString());
    }
}
