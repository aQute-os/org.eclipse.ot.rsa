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
import java.util.function.Supplier;

import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipReplicator;
import com.paremus.net.encode.EncodingScheme;

public class TCPReplicator extends AbstractTCPReplicator implements GossipReplicator {

	private static final byte NEEDS_CERT = 0;
	private static final byte READY = 1;
	
	private final SocketComms socketComms;

	public TCPReplicator(UUID id, Gossip gossip, EncodingScheme encoder, SocketComms socketComms) {
		super(id, gossip, encoder);
		this.socketComms = socketComms;
	}
	
	protected Socket getClientSocket() throws IOException {
		return encoder.getSocketFactory().createSocket();
	}

	@Override
	protected DelayedHolder doOutgoingHandshake(Socket s, UUID remoteNode, OutputStream socketOutput,
			InputStream socketInput, SocketAddress udpAddress, DataOutputStream dos, DataInputStream dis)
					throws IOException {
		try {
			handleOutgoingHandshake(remoteNode, dos, dis, s.getInetAddress(), udpAddress);
			
			handleIncomingHandshake(s.getInetAddress(), udpAddress, dos, dis);

			Supplier<DataInputStream> in = () -> new DataInputStream(socketInput);
			Supplier<DataOutputStream> out = () -> new DataOutputStream(socketOutput);
			
			return new DelayedHolder(in, out);
		} catch (CertificateException ce) {
			throw new IOException(ce);
		}
	}

	@Override
	protected DelayedHolder doIncomingHandshake(Socket s, OutputStream socketOutput, InputStream socketInput,
			DataOutputStream dos, DataInputStream dis, UUID remoteNode, SocketAddress udpAddress) throws IOException {
		try {
			handleIncomingHandshake(s.getInetAddress(), udpAddress, dos, dis);

			handleOutgoingHandshake(remoteNode, dos,
					dis, s.getInetAddress(), udpAddress);
			
			Supplier<DataInputStream> in = () -> new DataInputStream(socketInput);
			Supplier<DataOutputStream> out = () -> new DataOutputStream(socketOutput);
			
			return new DelayedHolder(in, out);
		} catch (CertificateException ce) {
			throw new IOException(ce);
		}
	}

	private void handleIncomingHandshake(InetAddress address, SocketAddress udpAddress,
			DataOutputStream dos, DataInputStream dis) throws IOException,
			CertificateException, CertificateEncodingException {
		if(encoder.isConfidential() && encoder.requiresCertificates()) {
			if(udpAddress == null || socketComms.getCertificateFor(udpAddress) == null) {
				dos.writeByte(NEEDS_CERT);
				dos.flush();
				CertificateFactory factory = CertificateFactory.getInstance("X.509");
				Certificate cert = factory.generateCertificate(dis);
				encoder.isAcceptable(cert, address);
				if(udpAddress != null) {
					socketComms.setCertificateFor(udpAddress, cert);
				}
			} else {
				dos.writeByte(READY);
			}
		} else {
			dos.writeByte(READY);
		}
		dos.flush();
	}

	private void handleOutgoingHandshake(UUID remoteNode, OutputStream socketOutput, InputStream socketInput,
			InetAddress address, SocketAddress udpAddress) throws IOException,
			EOFException, CertificateEncodingException, CertificateException {
		int read;
		read = socketInput.read();
		switch(read) {
			case -1: throw new EOFException("The socket closed prematurely)");
			case READY:
				break;
			case NEEDS_CERT: {
				if(!encoder.requiresCertificates()) {
					throw new IllegalStateException("The remote node " + remoteNode + " requested a certificate but none is available locally");
				}
				socketOutput.write(encoder.getCertificate().getEncoded());
				socketOutput.flush();
				break;
			}
			default : {
				throw new IllegalStateException("An unknown handshake request was made " + read);
			}
		}
	}
}
