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
package org.eclipse.ot.rsa.distribution.provider.activator;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;

public class RSAExecutorGroup extends MultithreadEventExecutorGroup {

	public RSAExecutorGroup(int nThreads, ThreadFactory threadFactory, int maxQueueDepth) {
		super(nThreads, new ThreadPoolExecutor(nThreads, nThreads, 0, TimeUnit.SECONDS,
				maxQueueDepth < 0 ? new LinkedBlockingQueue<>() : new ArrayBlockingQueue<>(maxQueueDepth)));
	}

	@Override
	protected EventExecutor newChild(Executor executor, Object... arg1) throws Exception {
		return new DefaultEventExecutor(this, executor);
	}
}
