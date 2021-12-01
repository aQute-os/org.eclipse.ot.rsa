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

import static com.paremus.dosgi.net.proxy.MethodCallHandler.CallType.WITH_RETURN;
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
import java.util.stream.Collectors;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceException;
import org.osgi.service.async.delegate.AsyncDelegate;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.net.impl.ImportRegistrationImpl;
import com.paremus.dosgi.net.proxy.MethodCallHandler.CallType;

/**
 * A generic proxy invocation handler superclass that catches all exceptions raised by a
 * distribution provider and rethrows those deemed "fatal" as a neat OSGi ServiceException
 * with type REMOTE. Exceptions not indicating transport-level errors (e.g. remote NPE,
 * IllegalArgumentException etc.) are forwarded to the caller.
 */
public class ServiceInvocationHandler implements InvocationHandler {
	
	private static final Logger LOG = LoggerFactory.getLogger(ServiceInvocationHandler.class);
	
    private final ImportRegistration _importRegistration;
    private final MethodCallHandler _handler;
    
    private static class InvocationInfo {
    	final InvocationHandler handler;
    	final Transformer transformer;
    	final int methodId;
    	
		public InvocationInfo(InvocationHandler handler, Transformer transformer, int method) {
			this.handler = handler;
			this.transformer = transformer;
			this.methodId = method;
		}
    	
    }
    
    private static interface Transformer {
    	Object transform(Object o) throws Throwable;
    }
    
    private final Map<Method, InvocationInfo> actions = new HashMap<>();
    
    public ServiceInvocationHandler(ImportRegistrationImpl importRegistration, EndpointDescription endpoint,
    		Bundle callingContext, Class<?> proxyClass, List<Class<?>> interfaces, Class<?> promiseClass, boolean isAsyncDelegate, 
    		MethodCallHandler handler, int serviceCallTimeout) {
        _importRegistration = Objects.requireNonNull(importRegistration, "ImportRegistration cannot be null");
        _handler = Objects.requireNonNull(handler, "The Method Call Handler is unavailable");
        
        Map<String, Integer> reverseMappings = importRegistration.getMethodMappings().entrySet().stream()
        		.collect(Collectors.toMap(Entry::getValue, Entry::getKey));
        
        Transformer promiseTransformer = getPromiseTransformer(promiseClass);
        
        Set<Method> objectMethods = stream(Object.class.getMethods()).collect(toSet());
        
        interfaces.stream()
        	.map(Class::getMethods)
        	.flatMap(Arrays::stream)
	        .forEach(m -> {
	        	InvocationInfo action;
	        	if(objectMethods.contains(m)) {
	        		action = OBJECT_DELEGATOR;
	        	} else {
	        		action = getReturnActionFor(m, reverseMappings, promiseClass, promiseTransformer, serviceCallTimeout);
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
        			new InvocationInfo((o,m,a) -> proxyEquals(o, a[0]), IDENTITY_TRANSFORM, -1));
        	actions.put(Object.class.getMethod("hashCode"), 
        			new InvocationInfo((o,m,a) -> proxyHashCode(o), IDENTITY_TRANSFORM, -1));
        	actions.put(Object.class.getMethod("toString"), 
        			new InvocationInfo((o,m,a) -> proxyToString(o), IDENTITY_TRANSFORM, -1));
        	
        	if(isAsyncDelegate) {
        		Class<?> asyncClass = interfaces.stream()
        			.filter(c -> AsyncDelegate.class.getName().equals(c.getName()))
        			.findFirst().get();
        		
        		actions.put(asyncClass.getMethod("async", Method.class, Object[].class),
        				new InvocationInfo((o,m,a) -> {
        					Method actual = (Method) a[0];
        					InvocationInfo invocationInfo = actions.get(actual);
        					if(invocationInfo == null) {
        						throw new NoSuchMethodException(String.valueOf(actual));
        					} else {
        						return _handler.call(WITH_RETURN, invocationInfo.methodId, (Object[]) a[1], serviceCallTimeout);
        					}
        				}, promiseTransformer, -1));
        		actions.put(asyncClass.getMethod("execute", Method.class, Object[].class), 
        				new InvocationInfo((o,m,a) -> {
        					Method actual = (Method) a[0];
        					InvocationInfo invocationInfo = actions.get(actual);
        					if(invocationInfo == null) {
        						throw new NoSuchMethodException(String.valueOf(actual));
        					} else {
        						_handler.call(CallType.FIRE_AND_FORGET, invocationInfo.methodId, (Object[]) a[1], serviceCallTimeout);
        						return true;
        					}
        				}, IDENTITY_TRANSFORM, -1));
        	}
        } catch (NoSuchMethodException nsme) {
        	throw new IllegalArgumentException("Unable to set up the actions for the proxy for endpoint " + endpoint.getId(), nsme);
        }
    }

	private InvocationInfo getReturnActionFor(Method method, Map<String, Integer> signaturesToIds,
			Class<?> promiseClass, Transformer promiseTransform, int timeout) {
		Integer i = signaturesToIds.get(toSignature(method));
		
		if(i != null) {
			int callId = i;
			Transformer transformer =  (promiseClass != null && 
					promiseClass.isAssignableFrom(method.getReturnType())) ?
					promiseTransform : DEFAULT_TRANSFORM;
			return new InvocationInfo((x,m,a) -> _handler.call(WITH_RETURN, callId, a, timeout), transformer, i);
		}
		
		return new InvocationInfo((a,b,c) -> {
					throw new NoSuchMethodException("The remote service does not define a method " 
							+ method.toGenericString());
				}, UNREACHABLE_TRANSFORMER, -1);
	}

	private Transformer getPromiseTransformer(Class<?> promiseClass) {
		Transformer promiseReturnAction;
		if(promiseClass == null || promiseClass.equals(Promise.class)) {
			promiseReturnAction = IDENTITY_TRANSFORM;
		} else {
        	try {
	        	Class<?> deferred = promiseClass.getClassLoader().loadClass(Deferred.class.getName());
	        	Method deferredResolve = deferred.getMethod("resolve", Object.class);
	        	Method deferredFail = deferred.getMethod("fail", Throwable.class);
	        	Method deferredGetPromise = deferred.getMethod("getPromise");

	        	promiseReturnAction = p -> convertPromise((Promise<?>)p, deferred, 
	        			deferredResolve, deferredFail, deferredGetPromise);
        	} catch (Exception e) {
        		throw new RuntimeException("The Promises package is not supported", e);
        	}
        }
		return promiseReturnAction;
	}

	private static final Transformer UNREACHABLE_TRANSFORMER = 
			t -> { throw new IllegalStateException("This transformer should never be called");};
	
	private static final Transformer DEFAULT_TRANSFORM = o -> {
															Promise<?> p = (Promise<?>) o;
															Throwable failure = p.getFailure();
															if(failure == null) {
																return p.getValue();
															} else {
																throw failure;
															}
														};
					
	private static final Transformer IDENTITY_TRANSFORM = o -> o;
	
    private static final InvocationInfo OBJECT_DELEGATOR = new InvocationInfo((x,y,a) -> {
			try {
				return y.invoke(x, a);
			} catch (InvocationTargetException ite) {
				throw ite.getCause();
			} catch (Exception e) {
				throw e;
			}
		}, IDENTITY_TRANSFORM, -1);
    
    private static final InvocationInfo MISSING_METHOD_HANDLER = new InvocationInfo((x,y,a) -> {
			throw new NoSuchMethodException(String.format("The method %s is not known to this handler", y));
		}, UNREACHABLE_TRANSFORMER, -1);
    
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            InvocationInfo info = actions.getOrDefault(method, MISSING_METHOD_HANDLER);
            return info.transformer.transform(info.handler.invoke(proxy, method, args));
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

    protected boolean proxyEquals(Object proxy, Object other) {
        if (other == null) {
            return false;
        }

        if (proxy == other) {
            return true;
        }

        if (Proxy.isProxyClass(other.getClass())) {
            return this == Proxy.getInvocationHandler(other);
        }

        return false;
    }

    protected int proxyHashCode(Object proxy) {
        return System.identityHashCode(this);
    }

    protected String proxyToString(Object proxy) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("Proxy");

        Class<?>[] interfaces = proxy.getClass().getInterfaces();
        if (interfaces.length > 0) {
            List<String> names = new ArrayList<String>(interfaces.length);
            for (Class<?> iface : interfaces) {
                names.add(iface.getName());
            }

            sb.append(names.toString());
        }

        sb.append('@');
        sb.append(Integer.toHexString(System.identityHashCode(proxy)));

        return sb.toString();
    }

	private Object convertPromise(Promise<?> p, Class<?> deferred, Method deferredResolve,
			Method deferredFail, Method deferredGetPromise) throws Exception {
		Object o = deferred.newInstance();
		p.then(x -> {
			deferredResolve.invoke(o, x.getValue());
			return null;
		}, x -> deferredFail.invoke(o, x.getFailure()));
		return deferredGetPromise.invoke(o);
	}
}
