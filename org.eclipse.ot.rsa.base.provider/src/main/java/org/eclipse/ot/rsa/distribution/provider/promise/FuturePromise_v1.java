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
package org.eclipse.ot.rsa.distribution.provider.promise;

import java.lang.reflect.InvocationTargetException;
import java.util.NoSuchElementException;

import org.osgi.util.function.Consumer;
import org.osgi.util.function.Function;
import org.osgi.util.function.Predicate;
import org.osgi.util.promise.Failure;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;
import org.osgi.util.promise.Success;

import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultPromise;
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
class FuturePromise_v1<T> extends DefaultPromise<T> implements Promise<T> {

	protected final Timer timer;

	FuturePromise_v1(EventExecutor executor, Timer timer) {
		super(executor);
		this.timer = timer;
	}

	protected <Z> FuturePromise_v1<Z> newInstance() {
		return new FuturePromise_v1<>(executor(), timer);
	}

	@Override
	public Promise<T> onResolve(Runnable callback) {
		addListener(f -> callback.run());
		return this;
	}

	@Override
	public <R> Promise<R> then(Success<? super T, ? extends R> success) {
		return then(success, null);
	}

	@Override
	public <R> Promise<R> then(final Success<? super T, ? extends R> success,
			final Failure failure) {

		final FuturePromise_v1<R> chained = newInstance();

		addListener(f -> then(success, failure, chained));

		return chained;
	}

	private <R> void then(final Success<? super T, ? extends R> success, final Failure failure,
			final FuturePromise_v1<R> chained) {
		try {
			if(isSuccess()) {
				if(success == null) {
					chained.setSuccess(null);
				} else {
					@SuppressWarnings({ "unchecked", "rawtypes" })
					Promise<R> p = success.call((Promise)this);
					if(p == null) {
						chained.setSuccess(null);
					} else {
						p.onResolve(() -> resolveWith(chained, p));
					}
				}
			} else {
				Throwable t = cause();
				if(failure != null) {
					failure.fail(FuturePromise_v1.this);
				}
				chained.setFailure(t);
			}
		} catch (Exception e) {
			try {
				chained.setFailure(e);
			} catch (Exception e2) {}
		}
	}

	protected static <R> void resolveWith(final io.netty.util.concurrent.Promise<R> chained, Promise<? extends R> p) {
		try {
			Throwable t = p.getFailure();
			if(t == null) {
				chained.setSuccess(p.getValue());
			} else {
				chained.setFailure(t);
			}
		} catch (Exception e) {
			chained.setFailure(e);
		}
	}

	@Override
	public Promise<T> filter(final Predicate<? super T> predicate) {
		checkNull(predicate);

		final FuturePromise_v1<T> chained = newInstance();

		addListener(f -> filter(predicate, chained));

		return chained;
	}

	private void filter(Predicate<? super T> predicate, FuturePromise_v1<T> chained) {
		try {
			if(isSuccess()) {
				T val = getNow();
				if(predicate.test(val)) {
					chained.setSuccess(val);
				} else {
					chained.setFailure(new NoSuchElementException());
				}
			} else {
				chained.setFailure(cause());
			}
		} catch (Exception e) {
			chained.setFailure(e);
		}
	}

	@Override
	public <R> Promise<R> map(final Function<? super T, ? extends R> mapper) {
		checkNull(mapper);

		final FuturePromise_v1<R> chained = newInstance();

		addListener(f -> map(mapper, chained));

		return chained;
	}

	private <R> void map(Function<? super T, ? extends R> mapper, FuturePromise_v1<R> chained) {
		try {
			if(isSuccess()) {
				try {
					chained.setSuccess(mapper.apply(getNow()));
				} catch (Exception e) {
					chained.setFailure(e);
				}
			} else {
				chained.setFailure(cause());
			}
		} catch (Exception e) {
			chained.setFailure(e);
		}
	}

	@Override
	public <R> Promise<R> flatMap(
			final Function<? super T, Promise<? extends R>> mapper) {
		checkNull(mapper);

		final FuturePromise_v1<R> chained = newInstance();

		addListener(f -> flatMap(mapper, chained));

		return chained;
	}

	private <R> void flatMap(Function<? super T, Promise<? extends R>> mapper,
			FuturePromise_v1<R> chained) {
		try {
			if(isSuccess()) {
				try {
					resolveWith(chained, mapper.apply(getNow()));
				} catch (Exception e) {
					chained.setFailure(e);
				}
			} else {
				chained.setFailure(cause());
			}
		} catch (Exception e) {
			chained.setFailure(e);
		}
	}

	@Override
	public Promise<T> recover(final Function<Promise<?>, ? extends T> recovery) {
		checkNull(recovery);

		final FuturePromise_v1<T> chained = newInstance();

		addListener(f -> recover(recovery, chained));

		return chained;
	}

	private void recover(Function<Promise<?>, ? extends T> recovery, FuturePromise_v1<T> chained) {
		try {
			if(isSuccess()) {
				chained.setSuccess(getNow());
			} else {
				T recoveryValue = recovery.apply(this);
				if(recoveryValue != null) {
					chained.setSuccess(recoveryValue);
				} else {
					chained.setFailure(cause());
				}
			}
		} catch (Exception e) {
			chained.setFailure(e);
		}
	}

	@Override
	public Promise<T> recoverWith(
			final Function<Promise<?>, Promise<? extends T>> recovery) {
		checkNull(recovery);

		final FuturePromise_v1<T> chained = newInstance();

		addListener(f -> recoverWith(recovery, chained));

		return chained;
	}

	private void recoverWith(Function<Promise<?>, Promise<? extends T>> recovery,
			FuturePromise_v1<T> chained) {

		try {
			if(isSuccess()) {
				chained.setSuccess(getNow());
			} else {
				Promise<? extends T> recoveryValue = recovery.apply(this);
				if(recoveryValue != null) {
					resolveWith(chained, recoveryValue);
				} else {
					chained.setFailure(cause());
				}
			}
		} catch (Exception e) {
			chained.setFailure(e);
		}
	}

	@Override
	public Promise<T> fallbackTo(final Promise<? extends T> fallback) {
		checkNull(fallback);

		final FuturePromise_v1<T> chained = newInstance();

		addListener(f -> fallbackTo(fallback, chained));

		return chained;
	}

	private void fallbackTo(Promise<? extends T> fallback, FuturePromise_v1<T> chained) {

		try {
			if(isSuccess()) {
				chained.setSuccess(getNow());
			} else {
				fallback.onResolve(() -> {
					try {
						Throwable t = fallback.getFailure();
						if(t == null) {
							chained.setSuccess(fallback.getValue());
						} else {
							chained.setFailure(cause());
						}
					} catch (Exception e) {
						chained.setFailure(e);
					}
				});
			}
		} catch (Exception e) {
			chained.setFailure(e);
		}
	}

	@Override
	public T getValue() throws InterruptedException, InvocationTargetException {
		Throwable t = await().cause();
		if(t != null) {
			throw new InvocationTargetException(t);
		}
		return getNow();
	}

	@Override
	public Throwable getFailure() throws InterruptedException {
		return await().cause();
	}

	private void checkNull(Object o) {
		if(o == null) {
			throw new NullPointerException("Null is not permitted");
		}
	}

	@Override
	public Promise<T> onSuccess(Consumer<? super T> success) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Promise<T> onFailure(Consumer<? super Throwable> failure) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Promise<T> thenAccept(Consumer<? super T> consumer) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Promise<T> timeout(long milliseconds) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Promise<T> delay(long milliseconds) {
		// TODO Auto-generated method stub
		return null;
	}

}
