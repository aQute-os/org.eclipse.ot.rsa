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
package org.eclipse.ot.rsa.distribution.provider.client;

import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.CacheAction.ADD;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.CacheAction.REMOVE;
import static org.eclipse.ot.rsa.distribution.provider.client.ClientMessageType.CacheAction.SKIP;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITHOUT_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CALL_WITH_RETURN;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1.CANCEL;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_CLOSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_DATA;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.ASYNC_METHOD_PARAM_FAILURE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.CLIENT_BACK_PRESSURE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.CLIENT_CLOSE;
import static org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2.CLIENT_OPEN;

import org.eclipse.ot.rsa.distribution.provider.message.MessageType;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V1;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol_V2;

public enum ClientMessageType implements MessageType {

		WITH_RETURN(Protocol_V1.VERSION, CALL_WITH_RETURN, ADD),
		FIRE_AND_FORGET(Protocol_V1.VERSION, CALL_WITHOUT_RETURN, SKIP),
		CANCELLATION(Protocol_V1.VERSION, CANCEL, REMOVE),
		ASYNC_ARG_SUCCESS(Protocol_V2.VERSION, ASYNC_METHOD_PARAM_DATA, SKIP),
		ASYNC_ARG_FAILURE(Protocol_V2.VERSION, ASYNC_METHOD_PARAM_FAILURE, SKIP),
		ASYNC_ARG_CLOSE(Protocol_V2.VERSION, ASYNC_METHOD_PARAM_CLOSE, SKIP),
		STREAMING_RESPONSE_OPEN(Protocol_V2.VERSION, CLIENT_OPEN, ADD),
		STREAMING_RESPONSE_CLOSE(Protocol_V2.VERSION, CLIENT_CLOSE, REMOVE),
		STREAMING_RESPONSE_BACK_PRESSURE(Protocol_V2.VERSION, CLIENT_BACK_PRESSURE, SKIP);

	public enum CacheAction {ADD, REMOVE, SKIP}

		private final byte version;
		private final byte command;
		private final CacheAction action;

		private ClientMessageType(byte version, byte command, CacheAction action) {
			this.version = version;
			this.command = command;
			this.action = action;
		}

		@Override
		public byte getVersion() {
			return version;
		}

		@Override
		public byte getCommand() {
			return command;
		}

		public CacheAction getAction() {
			return action;
		}
	}
