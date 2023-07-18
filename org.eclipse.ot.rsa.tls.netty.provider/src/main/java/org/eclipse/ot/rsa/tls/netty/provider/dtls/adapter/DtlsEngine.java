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
package org.eclipse.ot.rsa.tls.netty.provider.dtls.adapter;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import org.eclipse.ot.rsa.tls.netty.provider.dtls.adapter.DtlsEngineResult.OperationRequired;

import io.netty.buffer.ByteBuf;

public interface DtlsEngine {

	DtlsEngineResult generateDataToSend(ByteBuf input, ByteBuf output) throws SSLException;

	DtlsEngineResult handleReceivedData(ByteBuf input, ByteBuf output) throws SSLException;

	Runnable getTaskToRun();

	void closeOutbound();

	SSLParameters getSSLparameters();

	int getMaxSendOutputBufferSize();

	int getMaxReceiveOutputBufferSize();

	void setClient(boolean isClient);

	boolean isClient();

	OperationRequired getOperationRequired();

	void startHandshaking() throws SSLException;
}
