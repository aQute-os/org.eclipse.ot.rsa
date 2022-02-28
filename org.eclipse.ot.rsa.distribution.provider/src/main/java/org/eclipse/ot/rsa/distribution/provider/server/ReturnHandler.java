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
package org.eclipse.ot.rsa.distribution.provider.server;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;

interface ReturnHandler {
	Future<?> success(Channel channel, int callId, Object returnValue);

	Future<?> failure(Channel channel, int callId, Throwable failure);
}