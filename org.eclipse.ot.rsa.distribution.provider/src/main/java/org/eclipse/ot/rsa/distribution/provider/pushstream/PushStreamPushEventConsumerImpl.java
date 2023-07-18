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

import static org.osgi.framework.ServiceException.REMOTE;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import org.osgi.framework.ServiceException;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushStream;

import io.netty.util.Timeout;
import io.netty.util.Timer;

class PushStreamPushEventConsumerImpl extends AbstractPushEventConsumerImpl {

	private final PushStream<Object>	stream;

	private final Timeout				timeout;

	public PushStreamPushEventConsumerImpl(ToLongFunction<Object> onData, Consumer<Throwable> onTerminal,
		PushStream<Object> stream, Timer timer) {
		super(onData, onTerminal);
		this.stream = stream;
		timeout = timer.newTimeout(t -> {
			if (closed.compareAndSet(false, true)) {
				closeFuture.tryFailure(new TimeoutException("Stream timed out"));
				stream.close();
			}
		}, 30, TimeUnit.SECONDS);
	}

	@Override
	protected void terminalEvent(PushEvent<? extends Object> event) {
		if (closed.compareAndSet(false, true)) {
			closeFuture.trySuccess(null);
			super.terminalEvent(event);
		}
	}

	@Override
	public void open() {
		if (!closed.get()) {
			if (timeout.cancel()) {
				stream.forEachEvent(this);
			} else if (!timeout.isCancelled()) {
				super.terminalEvent(PushEvent.error(new ServiceException("The remote PushStream timed out", REMOTE)));
			}
		}
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			closeFuture.trySuccess(null);
			stream.close();
		}
	}
}
