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
package com.paremus.dosgi.net.pushstream;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventSource;

import io.netty.util.Timeout;
import io.netty.util.Timer;

class PushEventSourcePushEventConsumerImpl extends AbstractPushEventConsumerImpl {

	private static final AutoCloseable MARKER = () -> {};
	
	private final PushEventSource<Object> source;
	
	private final Timer timer;
	
	private Timeout timeout;
	
	private AtomicReference<AutoCloseable> connection = new AtomicReference<AutoCloseable>(null);
	
	public PushEventSourcePushEventConsumerImpl(ToLongFunction<Object> onData,
			Consumer<Throwable> onTerminal, PushEventSource<Object> source, Timer timer) {
		super(onData, onTerminal);
		this.source = source;
		this.timer = timer;
		synchronized (timer) {
			timeout = timer.newTimeout(this::timeout , 30, TimeUnit.SECONDS);
		}
	}
	
	private void timeout(Timeout t) {
		closed.set(true);
		internalClose();
		closeFuture.tryFailure(new TimeoutException("Stream timed out"));
	}
	
	protected void terminalEvent(PushEvent<? extends Object> event) {
		if(connection.getAndSet(null) != null) {
			super.terminalEvent(event);
		}
	}

	@Override
	public void open() {
		synchronized (this) {
			if(!timeout.cancel()) {
				return;
			}
		}
		if(!closed.get()) {
			if(connection.compareAndSet(null, MARKER)) {
				AutoCloseable open = null;
				try {
					open = source.open(this);
				} catch (Exception e) {
					closed.set(true);
					terminalEvent(PushEvent.error(e));
					internalClose();
					closeFuture.trySuccess(null);
				}
				// this can only happen due to an overlapping close
				if(!connection.compareAndSet(MARKER, open) && open != null) {
					try {
						open.close();
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		} else {
			
		}
	}

	@Override
	public void close() {
		internalClose();
		synchronized (this) {
			if(timeout.isCancelled()) {
				timeout = timer.newTimeout(this::timeout , 30, TimeUnit.SECONDS);
			}
		}
	}

	private void internalClose() {
		try (AutoCloseable conn = connection.getAndSet(null)) {
			if(conn != null) {
				try {
					conn.close();
				} catch (Exception e) {
					
				}
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
