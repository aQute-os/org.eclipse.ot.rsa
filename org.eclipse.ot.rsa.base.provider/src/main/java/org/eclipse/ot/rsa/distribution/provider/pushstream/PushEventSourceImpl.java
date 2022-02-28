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
package org.eclipse.ot.rsa.distribution.provider.pushstream;

import java.util.function.Consumer;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage.CacheKey;
import org.eclipse.ot.rsa.distribution.provider.pushstream.PushStreamFactory.OnConnect;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

class PushEventSourceImpl<T> implements PushEventSource<T> {

	private final CacheKey key;

	private final OnConnect<T> onConnect;
	private final Consumer<CacheKey> onClose;
	private final EventExecutor executor;

	public PushEventSourceImpl(CacheKey key, OnConnect<T> onConnect,
			Consumer<CacheKey> onClose, EventExecutor executor) {
		this.key = key;
		this.onConnect = onConnect;
		this.onClose = onClose;
		this.executor = executor;
	}

	@Override
	public AutoCloseable open(PushEventConsumer<? super T> aec) throws Exception {

		Promise<Object> closePromise = executor.newPromise();

		onConnect.connect(key, executor, closePromise, t -> {
				try {
					return aec.accept(PushEvent.data(t));
				} catch (Exception e) {
					try {
						aec.accept(PushEvent.error(e));
					} catch (Exception e1) {
						// TODO Auto-generated catch block
					} finally {
						closePromise.trySuccess(null);
					}
				}
				return -1;
			}, t -> {
				try {
					aec.accept(t == null ? PushEvent.close() : PushEvent.error(t));
				} catch (Exception e) {
					try {
						aec.accept(PushEvent.error(e));
					} catch (Exception e1) {
						// TODO Auto-generated catch block
					}
				} finally {
					closePromise.trySuccess(null);
				}
			});

		return () -> {
			onClose.accept(key);
			closePromise.trySuccess(null);
		};
	}

}
