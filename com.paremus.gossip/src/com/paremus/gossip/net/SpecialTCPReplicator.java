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
package com.paremus.gossip.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipReplicator;
import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncryptionDetails;

public class SpecialTCPReplicator extends AbstractTCPReplicator implements GossipReplicator {

	private final SocketComms socketComms;
	
	private static final byte INSECURE_EXCHANGE = 0;
	private static final byte PRE_SHARED_KEY = 1;
	private static final byte NEEDS_CERT = 2;
	private static final byte NEEDS_DETAILS = 3;
	private static final byte READY = 4;

	public SpecialTCPReplicator(UUID id, Gossip gossip, EncodingScheme encoder, SocketComms socketComms) {
		super(id, gossip, encoder);
		this.socketComms = socketComms;
	}
	
	protected Socket getClientSocket() throws IOException {
		return new Socket();
	}

	@Override
	protected DelayedHolder doOutgoingHandshake(Socket s, UUID remoteNode, OutputStream socketOutput,
			InputStream socketInput, SocketAddress udpAddress, DataOutputStream dos, DataInputStream dis)
					throws IOException {
		try {
			boolean encryptOutput = determineOutputSecurity(remoteNode, dos, dis,
					s.getInetAddress(), udpAddress);
			
			EncryptionDetails remoteEncryption = determineInputSecurity(s.getInetAddress(), udpAddress, dos, dis);

			Supplier<DataInputStream> in;
			Supplier<DataOutputStream> out;
			
			if(encryptOutput) {
				out = () -> encoder.encryptingStream(socketOutput);
			} else {
				out = () -> new DataOutputStream(socketOutput);
			}
			
			if(remoteEncryption != null) {
				in = () -> encoder.decryptingStream(socketInput, 
						remoteEncryption.getKey() == null ? null : remoteEncryption);
			} else {
				in = () -> new DataInputStream(socketInput);
			}
			
			return new DelayedHolder(in, out);
		} catch (CertificateException ce) {
			throw new IOException(ce);
		}
	}

	@Override
	protected DelayedHolder doIncomingHandshake(Socket s, OutputStream socketOutput, InputStream socketInput,
			DataOutputStream dos, DataInputStream dis, UUID remoteNode, SocketAddress udpAddress) throws IOException {
		try {
			EncryptionDetails remoteEncryption = determineInputSecurity(s.getInetAddress(), udpAddress, dos, dis);

			boolean encryptOutput = determineOutputSecurity(remoteNode, dos,
					dis, s.getInetAddress(), udpAddress);
			
			Supplier<DataInputStream> in;
			Supplier<DataOutputStream> out;
			
			if(encryptOutput) {
				out = () -> encoder.encryptingStream(socketOutput);
			} else {
				out = () -> new DataOutputStream(socketOutput);
			}
			
			if(remoteEncryption != null) {
				in = () -> encoder.decryptingStream(socketInput, 
						remoteEncryption.getKey() == null ? null : remoteEncryption);
			} else {
				in = () -> new DataInputStream(socketInput);
			}
			
			return new DelayedHolder(in, out);
		} catch (CertificateException ce) {
			throw new IOException(ce);
		}
	}

	private EncryptionDetails determineInputSecurity(InetAddress address, SocketAddress udpAddress,
			DataOutputStream dos, DataInputStream dis) throws IOException,
			CertificateException, CertificateEncodingException {
		EncryptionDetails toReturn = null;
		if(encoder.isConfidential()) {
			if(encoder.requiresCertificates()) {
				if(udpAddress != null && socketComms.getEncryptionDetailsFor(udpAddress) != null) {
					toReturn = socketComms.getEncryptionDetailsFor(udpAddress);
					dos.writeByte(READY);
				} else {
					Certificate cert;
					if(udpAddress == null || socketComms.getCertificateFor(udpAddress) == null) {
						dos.writeByte(NEEDS_CERT);
						dos.flush();
						CertificateFactory factory = CertificateFactory.getInstance("X.509");
						cert = factory.generateCertificate(dis);
						encoder.isAcceptable(cert, address);
						if(udpAddress != null) {
							socketComms.setCertificateFor(udpAddress, cert);
						}
					} else {
						cert = socketComms.getCertificateFor(udpAddress);
						dos.writeByte(NEEDS_DETAILS);
					}
					dos.write(encoder.getCertificate().getEncoded());
					dos.flush();
					byte[] b = new byte[dis.readUnsignedShort()];
					dis.readFully(b, 2, b.length - 2);
					toReturn = encoder.incomingKeyExchangeMessage(new byte[2], b, 2, b.length -2);
					if(udpAddress != null) {
						socketComms.setEncryptionDetailsFor(udpAddress, toReturn);
					}
				}
			} else {
				dos.writeByte(PRE_SHARED_KEY);
				toReturn = new EncryptionDetails(null, null, -1, -1, TimeUnit.SECONDS);
			}
		} else {
			dos.writeByte(INSECURE_EXCHANGE);
		}
		dos.flush();
		return toReturn;
	}

	private boolean determineOutputSecurity(UUID remoteNode, OutputStream socketOutput, InputStream socketInput,
			InetAddress address, SocketAddress udpAddress) throws IOException,
			EOFException, CertificateEncodingException, CertificateException {
		int read;
		boolean encryptOutput;
		read = socketInput.read();
		switch(read) {
			case -1: throw new EOFException("The socket closed prematurely)");
			case INSECURE_EXCHANGE: {
				if(encoder.isConfidential()) {
					throw new IllegalStateException("The remote node " + remoteNode + " requested an insecure exchange but this node is secure");
				}
				encryptOutput = false;
				break;
			}
			case PRE_SHARED_KEY: {
				if(encoder.dynamicKeyGenerationSupported()) {
					throw new IllegalStateException("The remote node " + remoteNode + " is configured with a static key and cannot exchange it securely");
				}
				encryptOutput = true;
				break;
			}
			case NEEDS_CERT: {
				if(!encoder.requiresCertificates()) {
					throw new IllegalStateException("The remote node " + remoteNode + " requested a certificate but none is available locally");
				}
				socketOutput.write(encoder.getCertificate().getEncoded());
				socketOutput.flush();
			}
			case NEEDS_DETAILS : {
				CertificateFactory factory = CertificateFactory.getInstance("X.509");
				Certificate cert = factory.generateCertificate(socketInput);
				encoder.isAcceptable(cert, address);
				if(udpAddress != null) {
					socketComms.setCertificateFor(udpAddress, cert);
				}
				byte[] b = encoder.outgoingKeyExchangeMessage(new byte[2], cert);
				b[0] = (byte) (b.length >> 8);
				b[1] = (byte) b.length;
				socketOutput.write(b);
				socketOutput.flush();
				encryptOutput = true;
				break;
			}
			case READY: {
				encryptOutput = true;
				break;
			}
			default : {
				throw new IllegalStateException("An unknown handshake request was made " + read);
			}
		}
		return encryptOutput;
	}
}
