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
package com.paremus.dosgi.net.promise;

import java.util.concurrent.TimeUnit;

import org.osgi.util.function.Consumer;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.TimeoutException;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;

/**
 * This type should never be used directly, but always created using
 * the {@link PromiseFactory} helper. This is because the RSA implementation
 * does not import any Promise API, and should always define this type
 * in some other bundle's class space. 
 * 
 * <p>
 * Note that the dependencies of this type must be carefully restricted,
 * it can only depend on JVM types, org.osgi.util.promise, org.osgi.util.function
 * and io.netty.util.concurrent
 *
 * @param <T>
 */
class FuturePromise_v1_1<T> extends FuturePromise_v1<T> {

	FuturePromise_v1_1(EventExecutor executor, Timer timer) {
		super(executor, timer);
	}

	@Override
	protected <Z> FuturePromise_v1_1<Z> newInstance() {
		return new FuturePromise_v1_1<>(executor(), timer);
	}

	@SuppressWarnings("unchecked")
	public Promise<T> timeout(long millis) {
		if(isDone()) {
			if(isSuccess()) {
				return (Promise<T>) newInstance().setSuccess(getNow());
			} else {
				return (Promise<T>) newInstance().setFailure(cause());
			}
		} else if(millis <= 0) {
			return (Promise<T>) newInstance().setFailure(new TimeoutException());
		} else {
			final FuturePromise_v1_1<T> chained = newInstance(); 
			Timeout timeout = timer.newTimeout(
					t -> executor().execute(() -> chained.setFailure(new TimeoutException())), 
					millis, TimeUnit.MILLISECONDS);
			
			addListener(f -> {
				timeout.cancel();
				resolveWith(chained, this);
			});
			
			return chained;
		}
	}

	public Promise<T> delay(long millis) {
		
		final FuturePromise_v1_1<T> chained = newInstance(); 
		
		if(millis <= 0) {
			resolveWith(chained, this);
		} else {
			addListener(f -> delay(chained, millis));
		}
		
		return chained;
	}
	
	private void delay(io.netty.util.concurrent.Promise<T> chained, long millis) {
		timer.newTimeout(t -> executor().execute(() -> resolveWith(chained, this)), 
				millis, TimeUnit.MILLISECONDS);
	}

	public Promise<T> onSuccess(Consumer<? super T> success) {
		addListener(f -> {
				if(isSuccess()) {
					success.accept(get());
				}
			});
		return this;
	}

	public Promise<T> onFailure(Consumer<? super Throwable> failure) {
		addListener(f -> {
			if(!isSuccess()) {
				failure.accept(cause());
			}
		});
		return this;
	}

	public Promise<T> thenAccept(Consumer<? super T> consumer) {
		return then(x -> {
				consumer.accept(get());
				return x;
			});
	}
}
