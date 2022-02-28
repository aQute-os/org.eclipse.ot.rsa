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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import org.eclipse.ot.rsa.tls.netty.provider.dtls.adapter.DtlsEngineResult.OperationRequired;
import org.eclipse.ot.rsa.tls.netty.provider.dtls.adapter.DtlsEngineResult.OperationResult;

import io.netty.buffer.ByteBuf;

public class JdkDtlsEngineAdapter implements DtlsEngine {

    private final SSLEngine engine;

    public JdkDtlsEngineAdapter(SSLEngine engine) {
        this.engine = engine;
    }

    @Override
    public DtlsEngineResult generateDataToSend(ByteBuf input, ByteBuf output) throws SSLException {
        SSLEngineResult result =
                engine.wrap(input.nioBuffer(), output.nioBuffer(output.writerIndex(), output.writableBytes()));

        updateBufferPositions(input, output, result);

        return new JdkDtlsEngineResultAdapter(toOperationResult(result.getStatus()),
                toOperationRequired(result.getHandshakeStatus()));
    }

    private void updateBufferPositions(ByteBuf input, ByteBuf output, SSLEngineResult result) {
        int bytesConsumed = result.bytesConsumed();
        if(bytesConsumed > 0) {
            input.skipBytes(bytesConsumed);
        }

        int bytesProduced = result.bytesProduced();
        if(bytesProduced > 0) {
            output.writerIndex(output.writerIndex() + bytesProduced);
        }
    }

    @Override
    public DtlsEngineResult handleReceivedData(ByteBuf input, ByteBuf output) throws SSLException {
        SSLEngineResult result =
                engine.unwrap(input.nioBuffer(), output.nioBuffer(output.writerIndex(), output.writableBytes()));

        updateBufferPositions(input, output, result);

        return new JdkDtlsEngineResultAdapter(toOperationResult(result.getStatus()),
                toOperationRequired(result.getHandshakeStatus()));
    }

    @Override
    public Runnable getTaskToRun() {
        return engine.getDelegatedTask();
    }

    @Override
    public void closeOutbound() {
        engine.closeOutbound();
    }

    @Override
    public SSLParameters getSSLparameters() {
        return engine.getSSLParameters();
    }

    @Override
    public int getMaxSendOutputBufferSize() {
        return engine.getSession().getPacketBufferSize();
    }

    @Override
    public int getMaxReceiveOutputBufferSize() {
        return engine.getSession().getApplicationBufferSize();
    }

    @Override
    public void setClient(boolean isClient) {
        engine.setUseClientMode(isClient);
    }

    @Override
    public boolean isClient() {
        return engine.getUseClientMode();
    }

    @Override
    public OperationRequired getOperationRequired() {
        return toOperationRequired(engine.getHandshakeStatus());
    }

    @Override
    public void startHandshaking() throws SSLException {
        engine.beginHandshake();
    }

    public OperationResult toOperationResult(Status status) {
        switch(status) {
            case BUFFER_OVERFLOW:
                return OperationResult.TOO_MUCH_OUTPUT;
            case BUFFER_UNDERFLOW:
                return OperationResult.INSUFFICIENT_INPUT;
            case CLOSED:
                return OperationResult.ENGINE_CLOSED;
            case OK:
                return OperationResult.OK;
            default:
                throw new IllegalArgumentException("Unknown status " + status);
        }
    }

    public OperationRequired toOperationRequired(HandshakeStatus status) {
        switch(status) {
            case FINISHED:
            case NOT_HANDSHAKING:
                return OperationRequired.NONE;
            case NEED_TASK:
                return OperationRequired.RUN_TASK;
            case NEED_UNWRAP:
                return OperationRequired.AWAITING_DATA;
            case NEED_WRAP:
                return OperationRequired.DATA_TO_SEND;
            default:
                // We do this so that the code remains Java 8 compatible
                if("NEED_UNWRAP_AGAIN".equals(status.name())) {
                    return OperationRequired.PENDING_RECEIVED_DATA;
                }

                throw new IllegalArgumentException("Unknown handshake status " + status);
        }
    }

    public static class JdkDtlsEngineResultAdapter implements DtlsEngineResult {

        private final OperationResult operationResult;
        private final OperationRequired operationRequired;

        public JdkDtlsEngineResultAdapter(OperationResult result, OperationRequired required) {
            this.operationResult = result;
            this.operationRequired = required;
        }

        @Override
        public OperationResult getOperationResult() {
            return operationResult;
        }

        @Override
        public OperationRequired getOperationRequired() {
            return operationRequired;
        }

    }
}

