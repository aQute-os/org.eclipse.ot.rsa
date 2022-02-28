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
package org.eclipse.ot.rsa.distribution.provider.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import org.eclipse.ot.rsa.distribution.provider.client.BeginStreamingInvocation;
import org.eclipse.ot.rsa.distribution.provider.client.ClientBackPressure;
import org.eclipse.ot.rsa.distribution.provider.client.EndStreamingInvocation;
import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage.CacheKey;
import org.eclipse.ot.rsa.distribution.provider.pushstream.PushStreamFactory.OnConnect;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.osgi.framework.ServiceException;

import io.netty.channel.Channel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

public class PushEventSourceConnector implements OnConnect<Object> {

	private final ConcurrentMap<CacheKey, PushEventSourceConnection> connections =
			new ConcurrentHashMap<>();

	private final Channel _channel;

	private final Serializer _serializer;

	private Timer _timer;

	public PushEventSourceConnector(Timer _timer, Channel _channel, Serializer _serializer) {
		this._timer = _timer;
		this._channel = _channel;
		this._serializer = _serializer;
	}

	@Override
	public void connect(CacheKey key, EventExecutor worker, Future<?> closeFuture,
			ToLongFunction<Object> pushData, Consumer<Exception> pushClose) {
		// We use an immediate executor here as we swap to a different thread for real delivery

		PushEventSourceConnection connection = connections.computeIfAbsent(key,
				k -> new PushEventSourceConnection(_timer, key, ImmediateEventExecutor.INSTANCE, _channel, _serializer));

		new PushEventSourceClient(worker, closeFuture, connection, pushData, pushClose);
	}

}

class PushEventSourceConnection {

	// Mostly there will only be one connected consumer
	private List<PushEventSourceClient> activeClients = new ArrayList<>(1);

	private final Timer _timer;
	private final CacheKey key;
	private final EventExecutor _executor;
	private final Channel _channel;
	private final Serializer _serializer;

	private final ClientBackPressure backPressureTemplate;

	private Promise<Object> closeFuture;

	private boolean closed;

	private Timeout timeout;

	public PushEventSourceConnection(Timer _timer, CacheKey key, EventExecutor _executor,
			Channel _channel, Serializer _serializer) {
		this._timer = _timer;
		this.key = key;
		this._executor = _executor;
		this._channel = _channel;
		this._serializer = _serializer;
		this.backPressureTemplate = new ClientBackPressure(key.getId(), key.getCallId(), 0);
		this.closeFuture = _executor.newPromise().addListener(this::setTimeout);

		setTimeout(null);
	}

	private void setTimeout(Future<?> f) {
		synchronized (activeClients) {
			if(!closed) {
				timeout = _timer.newTimeout(t -> {
						synchronized (activeClients) {
							if(activeClients.isEmpty()) {
								closed = true;
								closeFuture.trySuccess(null);
							}
						}
					}, 30, TimeUnit.SECONDS);
			}
		}
	}

	public void register(PushEventSourceClient pushEventSourceClient) {
		synchronized (activeClients) {
			if (closed) {
				throw new ServiceException("The PushEventSource has expired", ServiceException.REMOTE);
			}
			boolean add = activeClients.isEmpty();
			activeClients.add(pushEventSourceClient);
			if(add) {
				timeout.cancel();
				_channel.writeAndFlush(new BeginStreamingInvocation(key.getId(), key.getCallId(),
						_serializer, _executor, this::incomingData, this::incomingTerminal, closeFuture)).addListener(f -> {
							if(!f.isSuccess()) {
								incomingTerminal(new ServiceException("Unable to open the data stream",
										ServiceException.REMOTE, f.cause()));
							}
						});
			}
		}
	}

	private void incomingData(Object o) {
		// Data only ever comes in on a single thread and is pushed onto client threads
		synchronized (activeClients) {
			if (!closed) {
				BackPressureToken token = new BackPressureToken(_channel, backPressureTemplate);
				for (PushEventSourceClient client : activeClients) {
					client.data(token, o);
				}
			}
		}
	}

	private void incomingTerminal(Exception e) {

		List<PushEventSourceClient> toCloseClients;
		Promise<Object> toClosePromise;
		synchronized (activeClients) {
			toCloseClients = new ArrayList<>(activeClients);
			activeClients.clear();
			toClosePromise = closeFuture;
			closeFuture = _executor.newPromise().addListener(this::setTimeout);
		}

		for (PushEventSourceClient client : toCloseClients) {
			client.terminal(e);
		}
		toClosePromise.trySuccess(null);
	}

	public void unregister(PushEventSourceClient pushEventSourceClient) {
		Promise<Object> toClosePromise = null;
		synchronized (activeClients) {
			if(activeClients.remove(pushEventSourceClient) && activeClients.isEmpty()) {
				_channel.writeAndFlush(new EndStreamingInvocation(key.getId(), key.getCallId()));
				toClosePromise = closeFuture;
				closeFuture = _executor.newPromise().addListener(this::setTimeout);
			}
		}
		if(toClosePromise != null) {
			toClosePromise.trySuccess(null);
		}
	}
}

class PushEventSourceClient {

	private final AtomicBoolean closed = new AtomicBoolean();
	private final EventExecutor worker;
	private final ToLongFunction<Object> pushData;
	private final Consumer<Exception> pushClose;

	public PushEventSourceClient(EventExecutor worker, Future<?> closeFuture, PushEventSourceConnection connection, ToLongFunction<Object> pushData,
			Consumer<Exception> pushClose) {
		this.worker = worker;
		this.pushData = pushData;
		this.pushClose = pushClose;
		if(!closeFuture.isDone()) {
			closeFuture.addListener(f -> {
					connection.unregister(this);
					terminal(null);
				});
			connection.register(this);
		}
	}

	public void data(BackPressureToken token, Object o) {
		if(!closed.get()) {
			try {
				worker.execute(() -> internalDataEvent(token, o));
			} catch (Exception e) {
				checkedTerminal(e);
			}
		}
	}

	private void internalDataEvent(BackPressureToken token, Object o) {
		if(!closed.get()) {
			try {
				long bp = pushData.applyAsLong(o);
				if(bp > 0) {
					token.applyBackPressure(bp);
				} else if (bp < 0) {
					checkedTerminal(null);
				}
			} catch (Exception e) {
				checkedTerminal(e);
			}
		}
	}

	private void checkedTerminal(Exception e) {
		if(!closed.getAndSet(true)) {
			pushClose.accept(e);
		}
	}

	public void terminal(Exception e) {
		if(!closed.get()) {
			try {
				worker.execute(() -> {
					try {
						checkedTerminal(e);
					} catch (Exception x) {
						// Nothing to do here
					}
				});
			} catch (Exception e2) {
				checkedTerminal(e);
			}
		}
	}
}

class BackPressureToken {
	private long backPressureFutureTime;

	private final Channel _channel;

	private final ClientBackPressure backPressureTemplate;

	public BackPressureToken(Channel _channel, ClientBackPressure backPressureTemplate) {
		this._channel = _channel;
		this.backPressureTemplate = backPressureTemplate;
	}

	public void applyBackPressure(long bp) {

		long suggestedFutureTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(bp);

		if(suggestedFutureTime == 0) {
			suggestedFutureTime = 1;
		}

		long toSend = 0;

		synchronized (this) {
			if(backPressureFutureTime == 0) {
				// This happens the first time that we apply backpressure
				backPressureFutureTime = suggestedFutureTime;
				toSend = bp;
			} else {
				long diff = suggestedFutureTime - backPressureFutureTime;
				if(diff > 0) {
					// We have more back pressure to send, either the diff, or the full back pressure
					backPressureFutureTime = suggestedFutureTime;
					toSend = Math.min(TimeUnit.NANOSECONDS.toMillis(diff), bp);
				}
			}
		}

		if(toSend > 0) {
			_channel.writeAndFlush(backPressureTemplate.fromTemplate(toSend));
		}
	}
}
