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

import java.lang.reflect.InvocationTargetException;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import org.osgi.util.function.Function;
import org.osgi.util.function.Predicate;
import org.osgi.util.promise.Failure;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Success;

public class FuturePromise<T> implements Future<T>, Promise<T> {

	private static final int PENDING = 1;
	private static final int DONE = 2;
	private static final int CANCELLED = 3;
	
	private final Queue<Thread> waitingThreads = new ConcurrentLinkedQueue<Thread>();
	
	private final Queue<Runnable> pendingCallbacks = new ConcurrentLinkedQueue<Runnable>();
	private final Queue<Runnable> pendingFastCallbacks = new ConcurrentLinkedQueue<Runnable>();
	private final Executor callbackExecutor;
	
	private final AtomicInteger state = new AtomicInteger(PENDING);
	private final AtomicBoolean complete = new AtomicBoolean(false);

	final AtomicReference<T> value = new AtomicReference<T>();
	final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
	
	private final Consumer<Boolean> onCancel;
	
	public FuturePromise(Executor callbackExecutor, Consumer<Boolean> onCancel) {
		super();
		this.callbackExecutor = callbackExecutor;
		this.onCancel = onCancel;
	}

	private FuturePromise(Executor callbackExecutor) {
		this(callbackExecutor, null);
	}

	@Override
	public Promise<T> onResolve(Runnable callback) {
		try {
			pendingCallbacks.add(callback);
		} finally {
			if(complete.get()) triggerCallbacks();
		}
		return this;
	}
	
	@Override
	public <R> Promise<R> then(Success<? super T, ? extends R> success) {
		return then(success, null);
	}

	@Override
	public <R> Promise<R> then(final Success<? super T, ? extends R> success,
			final Failure failure) {
		final FuturePromise<R> returnedPromise = new FuturePromise<R>(callbackExecutor);
		
		onResolve(() -> then(success, failure, returnedPromise));
		return returnedPromise;
	}

	private <R> void then(final Success<? super T, ? extends R> success, final Failure failure,
			final FuturePromise<R> returnedPromise) {
		try {
			Throwable error = FuturePromise.this.failure.get();
			if(error != null) {
				try {
					if(failure != null) {
						failure.fail(FuturePromise.this);
					}
					returnedPromise.fail(error);
				} catch (Exception e) {
					returnedPromise.fail(e);
				}
			} else {
				try {
					if(success == null) {
						returnedPromise.resolve(null);
					} else {
						@SuppressWarnings({"rawtypes", "unchecked"})
						final Promise<? extends R> p = success.call((Promise)FuturePromise.this);
						if(p == null) {
							returnedPromise.resolve(null);
						} else {
							returnedPromise.resolveWith(p);
						}
					}
				} catch (Exception e) {
					returnedPromise.fail(e);
				}
			}
		} catch (Exception t) {}
	}

	@Override
	public Promise<T> filter(final Predicate<? super T> predicate) {
		checkNull(predicate);
		
		final FuturePromise<T> returnedPromise = new FuturePromise<T>(callbackExecutor);
		
		onResolve(() -> filter(predicate, returnedPromise));
		
		return returnedPromise;
	}

	private void filter(final Predicate<? super T> predicate, final FuturePromise<T> returnedPromise) {
		Throwable error = FuturePromise.this.failure.get();
		if(error != null) {
			returnedPromise.fail(error);
		} else {
			T val = value.get();
			try {
				if(predicate.test(val)) {
					returnedPromise.resolve(val);
				} else {
					returnedPromise.fail(new NoSuchElementException());
				}
			} catch (Exception e) {
				returnedPromise.fail(e);
			}
		}
	}

	@Override
	public <R> Promise<R> map(final Function<? super T, ? extends R> mapper) {
		checkNull(mapper);
		
		final FuturePromise<R> returnedPromise = new FuturePromise<R>(callbackExecutor);
		onResolve(() -> map(mapper, returnedPromise));
		return returnedPromise;
	}

	private <R> void map(final Function<? super T, ? extends R> mapper,
			final FuturePromise<R> returnedPromise) {
		Throwable error = FuturePromise.this.failure.get();
		if(error != null) {
			returnedPromise.fail(error);
		} else {
			try {
				returnedPromise.resolve(mapper.apply(value.get()));
			} catch (Exception e) {
				returnedPromise.fail(e);
			}
		}
	}

	@Override
	public <R> Promise<R> flatMap(
			final Function<? super T, Promise<? extends R>> mapper) {
		checkNull(mapper);
		
		final FuturePromise<R> returnedPromise = new FuturePromise<R>(callbackExecutor);
		onResolve(() -> flatMap(mapper, returnedPromise));
		return returnedPromise;
	}

	private <R> void flatMap(final Function<? super T, Promise<? extends R>> mapper,
			final FuturePromise<R> returnedPromise) {
		Throwable error = FuturePromise.this.failure.get();
		if(error != null) {
			returnedPromise.fail(error);
		} else {
			try {
				returnedPromise.resolveWith(mapper.apply(value.get()));
			} catch (Exception e) {
				returnedPromise.fail(e);
			}
		}
	}

	@Override
	public Promise<T> recover(final Function<Promise<?>, ? extends T> recovery) {
		checkNull(recovery);
		
		final FuturePromise<T> returnedPromise = new FuturePromise<T>(callbackExecutor);
		onResolve(() -> recover(recovery, returnedPromise));
		return returnedPromise;
	}

	private void recover(final Function<Promise<?>, ? extends T> recovery,
			final FuturePromise<T> returnedPromise) {
		Throwable error = FuturePromise.this.failure.get();
		if(error != null) {
			try {
				T recoveryValue = recovery.apply(FuturePromise.this);
				if(recoveryValue != null) {
					returnedPromise.resolve(recoveryValue);
				} else {
					returnedPromise.fail(error);
				}
			} catch (Exception e) {
				returnedPromise.fail(e);
			}
		} else {
			returnedPromise.resolve(value.get());
		}
	}

	@Override
	public Promise<T> recoverWith(
			final Function<Promise<?>, Promise<? extends T>> recovery) {
		checkNull(recovery);
		
		final FuturePromise<T> returnedPromise = new FuturePromise<T>(callbackExecutor);
		onResolve(() -> recoverWith(recovery, returnedPromise));
		return returnedPromise;
	}

	private void recoverWith(final Function<Promise<?>, Promise<? extends T>> recovery,
			final FuturePromise<T> returnedPromise) {
		Throwable error = FuturePromise.this.failure.get();
		if(error != null) {
			try {
				Promise<? extends T> recoveryPromise = recovery.apply(FuturePromise.this);
				if(recoveryPromise != null) {
					returnedPromise.resolveWith(recoveryPromise);
				} else {
					returnedPromise.fail(error);
				}
			} catch (Exception e) {
				returnedPromise.fail(e);
			}
		} else {
			returnedPromise.resolve(value.get());
		}
	}

	@Override
	public Promise<T> fallbackTo(final Promise<? extends T> fallback) {
		checkNull(fallback);
		
		final FuturePromise<T> returnedPromise = new FuturePromise<T>(callbackExecutor);
		onResolve(() -> fallbackTo(fallback, returnedPromise));
		return returnedPromise;
	}

	private void fallbackTo(final Promise<? extends T> fallback, final FuturePromise<T> returnedPromise) {
		final Throwable error = FuturePromise.this.failure.get();
		if(error != null) {
			try {
				fallback.onResolve(() -> {
						try {
							Throwable t = fallback.getFailure();
							if(t == null) {
								returnedPromise.resolve(fallback.getValue());
							} else {
								returnedPromise.fail(error);
							}
						} catch (InterruptedException ie) {
							fail(ie);
						} catch (InvocationTargetException ite) {
							fail(ite.getTargetException());
						}
					});
			} catch (Exception e) {
				returnedPromise.fail(e);
			}
		} else {
			returnedPromise.resolve(value.get());
		}
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		if(state.compareAndSet(PENDING, CANCELLED)) {
			if(onCancel != null) onCancel.accept(mayInterruptIfRunning);
			failure.set(new CancellationException("Cancelled by the client"));
			releaseWaitingThreads();
			triggerCallbacks();
			return true;
		}
		return false;
	}

	@Override
	public boolean isCancelled() {
		return state.get() == CANCELLED;
	}

	@Override
	public boolean isDone() {
		return state.get() > PENDING;
	}

	@Override
	public T getValue() throws InterruptedException, InvocationTargetException {
		blockUntilCompletion();
		
		Throwable t = failure.get();
		if(t != null) {
			throw new InvocationTargetException(t);
		} 
		return value.get();
	}

	@Override
	public Throwable getFailure() throws InterruptedException {
		blockUntilCompletion();
		return failure.get();
	}

	@Override
	public T get() throws InterruptedException, ExecutionException {
		blockUntilCompletion();
			
		Throwable t = failure.get();

		if(state.get() == CANCELLED) {
			throw (CancellationException) t;
		}
		
		if(t != null) {
			throw new ExecutionException(t);
		}
		return value.get();
	}

	@Override
	public T get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException,
			TimeoutException {
		if(!complete.get()) {
			waitingThreads.offer(Thread.currentThread());
			long toWait = unit.toNanos(timeout);
			long previousTime = System.nanoTime();
			while(!complete.get()) {
				LockSupport.parkNanos(this, toWait);
				
				if(Thread.currentThread().isInterrupted())
					throw new InterruptedException();
				
				long newTime = System.nanoTime();
				toWait -= (newTime - previousTime);
				previousTime = newTime;
				if(toWait <= 0)
					throw new TimeoutException("Timed out waiting for remote invocation to complete");
			}
		}
		
		Throwable t = failure.get();

		if(state.get() == CANCELLED) {
			throw (CancellationException) t;
		}
		
		if(t != null) {
			throw new ExecutionException(t);
		}
		return value.get();
	}
	
	void resolve(T value) {
		if(state.compareAndSet(PENDING, DONE)) {
			this.value.set(value);
			releaseWaitingThreads();
			triggerCallbacks();
		}
	}

	void fail(Throwable failure) {
		if(state.compareAndSet(PENDING, DONE)) {
			this.failure.set(failure);
			releaseWaitingThreads();
			triggerCallbacks();
		}
	}
	
	void resolveWith(final Promise<? extends T> promise) {
		promise.onResolve(() -> {
				try {
					Throwable t = promise.getFailure();
					if(t == null) {
						resolve(promise.getValue());
					} else {
						fail(t);
					}
				} catch (InterruptedException ie) {
					fail(ie);
				} catch (InvocationTargetException ite) {
					fail(ite.getTargetException());
				}
		});
	}
	
	private void blockUntilCompletion() throws InterruptedException {
		if(!complete.get()) {
			waitingThreads.offer(Thread.currentThread());
			while(!complete.get()) {
				LockSupport.park(this);
				if(Thread.currentThread().isInterrupted())
					throw new InterruptedException();
			}
		}
	}

	private void releaseWaitingThreads() {
		complete.set(true);
		
		Thread t;
		while((t = waitingThreads.poll()) != null) {
			LockSupport.unpark(t);
		}
	}

	private void triggerCallbacks() {
		Runnable r;
		while((r = pendingFastCallbacks.poll()) != null) {
			try {
				r.run();
			} catch (Exception ree) {
				//TODO log this
			}
		}
		
		while((r = pendingCallbacks.poll()) != null) {
			try {
				if(pendingCallbacks.isEmpty()) {
					try {
						r.run();
					} catch (Exception e) {
						//TODO log this
					}
				} else {
					callbackExecutor.execute(r);
				}
			} catch (RejectedExecutionException ree) {
				//Just call inline as we shouldn't skip the callback
				try {
					r.run();
				} catch (Exception e) {
					//TODO log this
				}
			}
		}
	}
	
	private void checkNull(Object o) {
		if(o == null) {
			throw new NullPointerException("Null is not permitted");
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.promise.Promise#onSuccess(org.osgi.util.function.Consumer)
	 */
	@Override
	public Promise<T> onSuccess(org.osgi.util.function.Consumer<? super T> success) {
		throw new UnsupportedOperationException("This method is not suppported yet");
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.promise.Promise#onFailure(org.osgi.util.function.Consumer)
	 */
	@Override
	public Promise<T> onFailure(org.osgi.util.function.Consumer<? super Throwable> failure) {
		throw new UnsupportedOperationException("This method is not suppported yet");
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.promise.Promise#thenAccept(org.osgi.util.function.Consumer)
	 */
	@Override
	public Promise<T> thenAccept(org.osgi.util.function.Consumer<? super T> consumer) {
		throw new UnsupportedOperationException("This method is not suppported yet");
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.promise.Promise#timeout(long)
	 */
	@Override
	public Promise<T> timeout(long milliseconds) {
		throw new UnsupportedOperationException("This method is not suppported yet");
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.promise.Promise#delay(long)
	 */
	@Override
	public Promise<T> delay(long milliseconds) {
		throw new UnsupportedOperationException("This method is not suppported yet");
	}
}
