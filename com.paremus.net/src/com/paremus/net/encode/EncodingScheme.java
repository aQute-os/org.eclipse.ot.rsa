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
package com.paremus.net.encode;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

public interface EncodingScheme {

	public abstract int getVersion();

	public abstract boolean requiresCertificates();

	public abstract Certificate getCertificate();

	public abstract void isAcceptable(Certificate cert, InetAddress source) throws CertificateException;

	public abstract DataInput validateAndDecode(byte[] header, byte[] message, int offset,
			int length, EncryptionDetails encryptionUsed);

	public abstract DataInputStream decryptingStream(InputStream is,
			 EncryptionDetails encryptionUsed);
	
	public abstract void skipForcedFlush(InputStream is);

	public abstract byte[] encode(byte[] header, byte[] message, int offset,
			int length);

	boolean isConfidential();

	public abstract DataOutputStream encryptingStream(OutputStream os);
	
	public abstract void forceFlush(OutputStream os);

	public abstract EncryptionDetails incomingKeyExchangeMessage(byte[] header,
			byte[] message, int offset, int length);

	public abstract byte[] outgoingKeyExchangeMessage(byte[] header, Certificate remoteCertificate);
	
	public abstract boolean dynamicKeyGenerationSupported();

	public abstract void regenerateKey();

	ServerSocketFactory getServerSocketFactory();

	SocketFactory getSocketFactory();
	
	byte[] sign(byte[] bytes);
	
	boolean verifySignature(byte[] bytes, byte[] signature, Certificate signingCertificate);

}