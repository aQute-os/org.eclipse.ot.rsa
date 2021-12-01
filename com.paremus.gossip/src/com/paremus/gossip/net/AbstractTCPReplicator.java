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

import static java.util.Optional.ofNullable;
import static org.osgi.util.promise.Promises.failed;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.net.ssl.SSLSocket;

import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipReplicator;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.net.encode.EncodingScheme;

public abstract class AbstractTCPReplicator implements GossipReplicator {

	protected final class DelayedHolder {
		
		private final Supplier<DataInputStream> isSupplier;
		private final Supplier<DataOutputStream> osSupplier;
		
		private volatile DataInputStream is;
		private volatile DataOutputStream os;
		
		public DelayedHolder(Supplier<DataInputStream> inputStream, Supplier<DataOutputStream> outputStream) {
			this.isSupplier = inputStream;
			this.osSupplier = outputStream;
		}		
		
		public DataInputStream getInputStream() {
			return (is = (is == null) ? isSupplier.get() : is);  
		}
	
		public DataOutputStream getOutputStream() {
			return (os = (os == null) ? osSupplier.get() : os);  
		}
	}

	private static final Logger logger = LoggerFactory.getLogger(AbstractTCPReplicator.class);
	
	protected final UUID id;
	
	protected final Gossip gossip;

	protected final EncodingScheme encoder;
	
	protected final AtomicLong counter = new AtomicLong();
	
	public AbstractTCPReplicator(UUID id, Gossip gossip, EncodingScheme encoder) {
		this.id = id;
		this.gossip = gossip;
		this.encoder = encoder;
	}

	protected <T> Promise<T> doAsync(Executor e, Callable<T> c) {
		Deferred<T> d = new Deferred<>();
		try {
			e.execute(() -> {
				try {
					d.resolve(c.call());
				} catch (Throwable t) {
					d.fail(t);
				}
			});
		} catch (Exception ex) {
			d.fail(ex);
		}
		return d.getPromise();
	}

	/* (non-Javadoc)
	 * @see com.paremus.gossip.net.GossipReplicator#outgoingExchangeSnapshots(java.util.concurrent.Executor, com.paremus.gossip.cluster.impl.MemberInfo, java.util.Collection, java.net.InetAddress)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public Promise<Long> outgoingExchangeSnapshots(Executor e, MemberInfo member, Collection<Snapshot> snapshots,
			InetAddress bindAddress) {
		final long exchangeId = counter.incrementAndGet();
		final Deferred<Long> deferred = new Deferred<>();
		if(logger.isDebugEnabled()) {
			logger.debug("Starting outgoing exchange " + exchangeId + " with: " + member.getTcpAddress());
		}
		
		e.execute(() -> {
			try {
				Socket s = getClientSocket();
				s.setSoLinger(true, 1);
				s.setSoTimeout(1000);
				s.bind(new InetSocketAddress(bindAddress.isAnyLocalAddress() ? null : bindAddress, 0));
				s.connect(member.getTcpAddress(), 2000);
				
				DelayedHolder h = outgoingHandshake(s, member.getId());
	
				Promise<Void> headersSent = doAsync(e, () -> sendExchangeStart(h.getOutputStream(), exchangeId, id))
					.then(p -> doAsync(e, () -> {
						DataOutputStream os = h.getOutputStream();
						sendSnapshots(os, snapshots);
						encoder.forceFlush(os);
						return null;
					}));
	
				Promise<Map<UUID, Snapshot>> headersReceived = doAsync(e, () -> validateIncomingExchangeStart(
						h.getInputStream(), exchangeId, member.getId()))
							.then(p -> doAsync(e, () -> readSnapshots(h.getInputStream())));
				
				Promise<Void> finishedSending = headersSent.flatMap(v -> headersReceived)
							.map(v -> getFullSnapshotsToSend(snapshots, v))
							.then(p ->  doAsync(e, () -> {
								sendSnapshots(h.getOutputStream(), p.getValue());
								h.getOutputStream().close();
								if(!(s instanceof SSLSocket)) {s.shutdownOutput();}
								return null;
							}), p -> {if(!(s instanceof SSLSocket)) {s.shutdownOutput();}});
				
				Promise<Void> finishedReceiving = headersReceived
						.then(p -> doAsync(e, () -> {
							DataInputStream inputStream = h.getInputStream();
							try {
								encoder.skipForcedFlush(inputStream);
								return readSnapshots(inputStream);
							} finally {
								inputStream.close();
								if(!(s instanceof SSLSocket)) {s.shutdownInput();}
							}
						}), p -> {if(!(s instanceof SSLSocket)) {s.shutdownInput();}})
						.then(p -> {
							p.getValue().values().forEach(gossip::merge);
							return null;
						});
				
				deferred.resolveWith(Promises.all(finishedSending, finishedReceiving)
						.onResolve(() -> { try { s.close(); } catch (Exception ex) {} })
						.map(v -> exchangeId));
			} catch (Exception ex) {
				deferred.fail(ex);
			}
		});
		
		return deferred.getPromise();
	}

	protected abstract Socket getClientSocket() throws IOException;

	private DelayedHolder outgoingHandshake(Socket s, UUID remoteNode) throws IOException {
		OutputStream socketOutput = new FilterOutputStream(s.getOutputStream()) {
			public void close() throws IOException {flush();}
		};
		
		InputStream socketInput = new FilterInputStream(s.getInputStream()) {
			public void close() {}
		};
		
		SocketAddress udpAddress = ofNullable(gossip)
				.map(l -> l.getInfoFor(remoteNode))
				.map(MemberInfo::getUdpAddress)
				.orElse(null);
		
		try (	DataOutputStream dos = new DataOutputStream(socketOutput); 
				DataInputStream dis = new DataInputStream(socketInput)
					){
			dos.write(1);
			dos.writeLong(id.getMostSignificantBits());
			dos.writeLong(id.getLeastSignificantBits());
			dos.flush();
			
			int read = dis.read();
			if(read == -1) {
				//TODO log
				return null;
			}
			else if(read != 1) {
				throw new IllegalArgumentException("Unknown protocol version " + read);
			}
			
			if(!remoteNode.equals(new UUID(dis.readLong(), dis.readLong()))) {
				throw new IllegalArgumentException("Exchanging with the wrong peer");
			}
			
			return doOutgoingHandshake(s, remoteNode, socketOutput, socketInput, udpAddress, dos, dis);
		}
	}

	protected abstract DelayedHolder doOutgoingHandshake(Socket s, UUID remoteNode, OutputStream socketOutput,
			InputStream socketInput, SocketAddress udpAddress, DataOutputStream dos, DataInputStream dis) 
				throws IOException;

	/* (non-Javadoc)
	 * @see com.paremus.gossip.net.GossipReplicator#incomingExchangeSnapshots(java.util.concurrent.Executor, java.net.Socket)
	 */
	@Override
	public Promise<Long> incomingExchangeSnapshots(Executor e, Socket s) {
		Deferred<Long> d = new Deferred<>();
		try {
			e.execute(() -> { 
				try {
					d.resolveWith(internalIncomingExchangeSnapshots(e, s));
				} catch (Throwable t) {
					d.fail(t);
				}
			});
		} catch (RuntimeException re) {
			d.fail(re);
		}
		return d.getPromise();
	}
	
	@SuppressWarnings("unchecked")
	private Promise<Long> internalIncomingExchangeSnapshots(Executor e, Socket s) {
		try {
			s.setSoLinger(true, 1);
			s.setSoTimeout(1000);
			
			DelayedHolder h = incomingHandshake(s);
			
			Collection<Snapshot> snapshots = gossip.getAllSnapshots();
			
			Promise<Long> exchangeStart = doAsync(e, () -> validateIncomingExchangeStart(h.getInputStream(), null, null));
			
			Promise<Map<UUID, Snapshot>> headersRead = exchangeStart
					.then(p -> doAsync(e, () -> readSnapshots(h.getInputStream())));
			
			Promise<?> headersSent = exchangeStart
					.flatMap(l -> doAsync(e, () -> {
						DataOutputStream os = h.getOutputStream();
						sendExchangeStart(os, l, id);
						sendSnapshots(os, snapshots);
						encoder.forceFlush(os);
						return null;
					}));
			
			Promise<Void> finishedSending = headersSent.flatMap(p -> headersRead)
				.map(v -> getFullSnapshotsToSend(snapshots, v))
				.then(p -> doAsync(e, () -> {
					sendSnapshots(h.getOutputStream(), p.getValue());
					h.getOutputStream().close();
					if(!(s instanceof SSLSocket)) {s.shutdownOutput();}
					return null;
				}), p -> { if(!(s instanceof SSLSocket)) {s.shutdownOutput();}});
			
			Promise<Void> finishedReceiving = headersRead
					.then(p -> doAsync(e, () -> {
						DataInputStream inputStream = h.getInputStream();
						try {
							encoder.skipForcedFlush(inputStream);
							return readSnapshots(inputStream);
						} finally {
							inputStream.close();
							if(!(s instanceof SSLSocket)) {s.shutdownInput();}
						}
					}), p -> {if(!(s instanceof SSLSocket)) {s.shutdownInput();}})
					.then(p -> {
						p.getValue().values().forEach(gossip::merge);
						return null;
					});
			
			return Promises.all(finishedSending, finishedReceiving)
					.onResolve(() -> { try { s.close(); } catch (Exception ex) {} })
					.flatMap(v -> exchangeStart);
		} catch (Throwable t) {
			return failed(t);
		}
	}

	private DelayedHolder incomingHandshake(Socket s) throws IOException {
		OutputStream socketOutput = new FilterOutputStream(s.getOutputStream()) {

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
			}

			public void close() throws IOException {flush();}
		};
		
		InputStream socketInput = new FilterInputStream(s.getInputStream()) {
			public void close() {}
		};
		
		try (	DataOutputStream dos = new DataOutputStream(socketOutput); 
				DataInputStream dis = new DataInputStream(socketInput)
					){

			dos.write(1);
			dos.writeLong(id.getMostSignificantBits());
			dos.writeLong(id.getLeastSignificantBits());
			dos.flush();

			int read = socketInput.read();
			if(read == -1) {
				return null;
			}
			else if(read != 1) {
				throw new IllegalArgumentException("Unknown protocol version " + read);
			}
			
			UUID remoteNode = new UUID(dis.readLong(), dis.readLong());
			
			SocketAddress udpAddress = ofNullable(gossip)
					.map(l -> l.getInfoFor(remoteNode))
					.map(MemberInfo::getUdpAddress)
					.orElse(null);

				return doIncomingHandshake(s, socketOutput, socketInput, dos, dis, remoteNode, udpAddress);
		}
	}

	protected abstract DelayedHolder doIncomingHandshake(Socket s, OutputStream socketOutput, InputStream socketInput,
			DataOutputStream dos, DataInputStream dis, UUID remoteNode, SocketAddress udpAddress)
				throws IOException;

	private Void sendExchangeStart(DataOutputStream dos, 
			long exchangeId, UUID id) throws Exception {
		dos.writeLong(exchangeId);
		dos.writeLong(id.getMostSignificantBits());
		dos.writeLong(id.getLeastSignificantBits());
		return null;
	}
	
	private Void sendSnapshots(DataOutputStream dos, Collection<Snapshot> snapshots) throws Exception {
		dos.writeInt(snapshots.size());
		snapshots.forEach((s) -> s.writeOut(dos));
		return null;
	}

	private Long validateIncomingExchangeStart(DataInputStream input, 
			Long expectedExchangeId, UUID expectedNode) throws IOException {
		long exchangeId = input.readLong();
		UUID remoteId = new UUID(input.readLong(), input.readLong());
		
		if(expectedExchangeId != null && !expectedExchangeId.equals(exchangeId)) {
			throw new IllegalArgumentException("Received the wrong exchange id");
		}
		if(expectedNode != null) {
			if(!expectedNode.equals(remoteId)) {
				throw new IllegalArgumentException("Not exchanging with the correct peer!");
			}
		} else if(logger.isDebugEnabled()){
			logger.debug("Participating in exchange " + exchangeId + " with " + id);
		}
		return exchangeId;
	}
	
	private Map<UUID, Snapshot> readSnapshots(DataInputStream input) throws IOException, CertificateException {
		int size = input.readInt();
		Map<UUID, Snapshot> result = new HashMap<>();
		for(int i = 0; i < size; i++) {
			Snapshot snapshot = new Snapshot(input);
			result.put(snapshot.getId(), snapshot);
		}
		return result;
	}
	
	private Collection<Snapshot> getFullSnapshotsToSend(Collection<Snapshot> headers, 
			Map<UUID, Snapshot> remoteSnapshots) {
		return headers.stream()
						.filter(snapshot -> {
							Snapshot sent = remoteSnapshots.get(snapshot.getId());
							return sent == null 
									|| shouldSendFullShapshot(snapshot, sent);
						})
						.map(snapshot -> gossip.getInfoFor(snapshot.getId()).toSnapshot())
						.collect(Collectors.toSet());
	}
	
	private boolean shouldSendFullShapshot(Snapshot localHeader, Snapshot remoteHeader) {
		int delta = localHeader.getStateSequenceNumber() - remoteHeader.getStateSequenceNumber();
		
		if(delta > 0) {
			return true;
		} else if (delta == 0 &&
				(localHeader.getSnapshotTimestamp() - remoteHeader.getSnapshotTimestamp()) < 0) {
			gossip.getInfoFor(localHeader.getId()).update(remoteHeader);
		}
		
		return false;
	}
}
