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
package com.paremus.dosgi.discovery.gossip.comms;

import static com.paremus.dosgi.discovery.gossip.comms.MessageType.ACKNOWLEDGMENT;
import static com.paremus.dosgi.discovery.gossip.comms.MessageType.ANNOUNCEMENT;
import static com.paremus.dosgi.discovery.gossip.comms.MessageType.REMINDER;
import static com.paremus.dosgi.discovery.gossip.comms.MessageType.REQUEST_REANNOUNCEMENT;
import static com.paremus.dosgi.discovery.gossip.comms.MessageType.REVOCATION;
import static java.util.Arrays.copyOfRange;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.dosgi.discovery.gossip.impl.Config;
import com.paremus.dosgi.discovery.gossip.local.LocalDiscoveryListener;
import com.paremus.dosgi.discovery.gossip.remote.RemoteDiscoveryNotifier;
import com.paremus.gossip.cluster.ClusterInformation;
import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncodingSchemeFactory;
import com.paremus.net.encode.EncryptionDetails;
import com.paremus.net.encode.ExpiredEncryptionDetailsException;
import com.paremus.net.encode.InvalidEncodingException;
import com.paremus.net.encode.MissingEncryptionDetailsException;
import com.paremus.net.info.ClusterNetworkInformation;

public class SocketComms {

	private static final byte KEY_EXCHANGE_INITIATE = 3;
	private static final byte KEY_EXCHANGE_RESPOND = 4;
	
	private static final Logger logger = LoggerFactory.getLogger(SocketComms.class);
	
	private final ScheduledExecutorService workers;
	
	private final AtomicBoolean open = new AtomicBoolean(true);
	
	private final AtomicBoolean bound = new AtomicBoolean(false);
	
	private final AtomicReference<Thread> udpServerThread = new AtomicReference<>();
	
	private final AtomicReference<DatagramSocket> udpSocket = new AtomicReference<>();

	private final UUID localId;
	
	private final AtomicInteger udpPort = new AtomicInteger(-1);

	private final LocalDiscoveryListener local;

	private final RemoteDiscoveryNotifier listener;
	
	private final ClusterInformation clusterInformation;

	private final ConcurrentMap<SocketAddress, UUID> socketToId = new ConcurrentHashMap<>();

	private final ConcurrentMap<SocketAddress, Certificate> certificates = new ConcurrentHashMap<>();

	private final ConcurrentMap<SocketAddress, EncryptionDetails> encryptionDetails = new ConcurrentHashMap<>();

	private final AtomicInteger keyGenerationState = new AtomicInteger(0);

	private final ConcurrentMap<UUID, Map<String, PendingAck>> pendingAcknowledgments = new ConcurrentHashMap<>();

	private final EncodingScheme encodingScheme;
	
	private final AtomicReference<ScheduledFuture<?>> restartListenerThread = new AtomicReference<>();

	private final AtomicReference<ScheduledFuture<?>> resendMessages = new AtomicReference<>();
	
	public SocketComms(UUID id, ClusterInformation ci, LocalDiscoveryListener local, 
			RemoteDiscoveryNotifier discoveryNotifier, EncodingSchemeFactory esf) {
		this.localId = id;
		this.local = local;
		this.clusterInformation = ci;
		listener = discoveryNotifier;
		workers = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "RSA Discovery publisher - " + ci.getClusterName());
			t.setDaemon(true);
			return t;
		});
		this.encodingScheme = esf.createEncodingScheme(() -> workers.execute(this::updatedEncryption));
	}
	
	private void updatedEncryption() {
		keyGenerationState.incrementAndGet();
		pendingAcknowledgments.values().forEach(m -> m.values().stream().forEach(PendingAck::updateEncoding));
		socketToId.keySet().stream().forEach(this::sendKeyUpdate);
	}
	
	private void sendKeyUpdate(SocketAddress address) {
		UUID uuid = socketToId.get(address);
		if(uuid == null) {
			logger.debug("The node at address {} is not known and cannot be contacted", address);
			return;
		}
		Certificate cert = certificates.get(address);
		if(cert != null) {
			int state =  keyGenerationState.get();
			byte[] header = getKeyExchangeHeader(KEY_EXCHANGE_RESPOND, state);
			doSend(new byte[][] { encodingScheme.outgoingKeyExchangeMessage(header, cert) }, 
					localId.toString(), state, uuid, address);
			if(logger.isDebugEnabled()) {
				logger.debug("Outgoing key response from {} to {} on {}", 
						new Object[] {localId, uuid, address});
			}
		}
		
	}
	
	public synchronized void bind(ClusterNetworkInformation info, Config config) {
		if(!open.get()) {
			throw new IllegalStateException("Communications for DOSGi discovery in cluster " + info + " are closed");
		}
		if(bound.get()) {
			return;
		}
		if(info.isFirewalled()) {
			logger.warn("This node is firewalled from the peers in cluster {}. Discovery may be unreliable.",
					new Object[] {info.getClusterName()});
		}
		try {
			DatagramSocket ds = new DatagramSocket(config.port(), info.getBindAddress());
			udpSocket.set(ds);
			udpPort.set(ds.getLocalPort());
			bound.set(true);
			restartListenerThread.set(workers.scheduleWithFixedDelay(this::ensureListenerThreadsAreStarted, 
					0, 500, TimeUnit.MILLISECONDS));
			resendMessages.set(workers.scheduleWithFixedDelay(this::resendNonAckedMessages, 0, 500, TimeUnit.MILLISECONDS));
		} catch (IOException e) {
			logger.error("Unable to start the discovery commmunications layer.", e);
		}
	}
	
	private void resendNonAckedMessages() {
		long now = NANOSECONDS.toMillis(System.nanoTime());
		pendingAcknowledgments.values().stream().forEach(v -> v.values().stream().forEach(p -> p.awaitingAck(now)));
	}
	
	public synchronized void destroy() {
		if(!open.compareAndSet(true, false)) {
			return;
		}
		
		if(bound.compareAndSet(true, false)) {
			ofNullable(restartListenerThread.getAndSet(null)).ifPresent(s -> s.cancel(false));
			ofNullable(resendMessages.getAndSet(null)).ifPresent(s -> s.cancel(false));
			interruptThread(udpServerThread);
			udpSocket.getAndSet(null).close();
		}
		
		workers.shutdown();
		try {
			workers.awaitTermination(2000, MILLISECONDS);
		} catch (InterruptedException e) {}
		workers.shutdownNow();
	}

	private void interruptThread(AtomicReference<Thread> ref) {
		ofNullable(ref.get()).ifPresent(Thread::interrupt);
	}

	private synchronized void ensureListenerThreadsAreStarted() {
		if(!bound.get())
			return;
		DatagramSocket ds = udpSocket.get();
		if(ds.isClosed()) {
			interruptThread(udpServerThread);
			try {
				udpSocket.set(new DatagramSocket(ds.getLocalSocketAddress()));
				Thread t = new Thread(this::listenUDP, "RSA Discovery listener - " 
						+ clusterInformation.getClusterName() + " port " + udpPort.get());
				t.setDaemon(true);
				udpServerThread.set(t);
				t.start();
			} catch (SocketException se) {
				logger.error("Unable to regain UDP discovery port " + udpPort, se);
			}
		} else if (!ofNullable(udpServerThread.get()).map(Thread::isAlive).orElse(false)) {
			Thread t = new Thread(this::listenUDP, "RSA Discovery listener - " 
					+ clusterInformation.getClusterName() + " port " + udpPort.get());
			t.setDaemon(true);
			udpServerThread.set(t);
			t.start();
		}
	}
	
	private void listenUDP() {
		int maxUDP = (1 << 16) -1;
		while(open.get() && !Thread.interrupted()) {
			DatagramSocket datagramSocket = udpSocket.get();
			DatagramPacket dp = new DatagramPacket(new byte[maxUDP], maxUDP);
			try {
				datagramSocket.receive(dp);
				
				if(logger.isTraceEnabled()) {
					logger.trace("Received Discovery Message from {}", dp.getSocketAddress());
				}
				
				receivedData(dp);
			} catch (InvalidEncodingException e) {
				logger.info("The Discovery UDP listener was unable to decode a message and is therefore ignoring it");
				continue;
			} catch (ExpiredEncryptionDetailsException e) {
				InetSocketAddress socketAddress = (InetSocketAddress) dp.getSocketAddress();
				logger.info("The Encryption key for {} at {} has expired, discovery messages from that node will be ignored until a new key is available.", 
						socketToId.get(socketAddress), socketAddress);
				encryptionDetails.remove(socketAddress, e.getExpired());
				beginEncryptionExchange(socketAddress);
				continue;
			} catch (MissingEncryptionDetailsException e) {
				InetSocketAddress socketAddress = (InetSocketAddress) dp.getSocketAddress();
				logger.info("No Encryption key is currently available for {}, discovery messages from that node will be ignored until a key is available.", socketAddress);
				beginEncryptionExchange(socketAddress);
				continue;
			} catch (InterruptedIOException e) {
				logger.info("The Discovery UDP listener thread was interrupted and is terminating");
				return;
			} catch (RejectedExecutionException e) {
				if(open.get()) {
					logger.warn("The Discovery UDP executor rejected a task", e);
					continue;
				}
				return;
			} catch (IOException ioe) {
				if(datagramSocket.isClosed()) {
					logger.info("The Discovery UDP socket was closed, so the listener thread is terminating");
					return;
				} else {
					logger.warn("There was an unexpected error listening for UDP discovery, continuing", ioe);
					continue;
				}
			} catch (Throwable t) {
				logger.error("There was an unrecoverable error consuming a UDP gossip message", t);
				return;
			}
		}
	}

	private final byte[] discoveryHeader = {1,2};
	
	private void receivedData(DatagramPacket dp) throws IOException,
			UnsupportedEncodingException {
		byte[] data = dp.getData();
		int offset = dp.getOffset();
		int length = dp.getLength();
		SocketAddress socketAddress = dp.getSocketAddress();
		
		if(data[offset] != 1) {
			throw new IOException("Unknown Discovery comms version " + data[offset]);
		}
		
		switch(data[offset + 1]) {
			case 2 : {
				DataInput input = encodingScheme.validateAndDecode(discoveryHeader, data, offset + 2, length - 2, 
						encryptionDetails.get(socketAddress));
				handleDiscoveryMessage(socketAddress, input);
				return;
			} case 3 : {
				handleIncomingKeyExchange(data, offset, length, socketAddress);
				break;
			} case 4 : {
				UUID remoteId;
				Integer state;
				try(DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data, offset + 2, 20))) {
					remoteId = new UUID(dis.readLong(), dis.readLong());
					state = dis.readInt();
				}
				
				encryptionDetails.merge(socketAddress, 
						encodingScheme.incomingKeyExchangeMessage(copyOfRange(data, 0, 22), data, offset + 22, length - 22),
						(o, n) -> o.getKeyGenerationCounter() >= n.getKeyGenerationCounter() ? o : n);
				acknowledge(remoteId.toString(), state, socketAddress);
				break;
			}
				
		}
		
		
	}

	private void handleIncomingKeyExchange(byte[] data, int offset, int length,
			SocketAddress socketAddress) throws IOException {
		UUID remoteId;
		Integer state;
		try(DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data, offset + 2, 20))) {
			remoteId = new UUID(dis.readLong(), dis.readLong());
			state = dis.readInt();
		}
		
		if(logger.isDebugEnabled()) {
			logger.debug("An incoming key exchange from node {} at {}",
					new Object[] {remoteId, socketAddress});
		}
		
		encryptionDetails.merge(socketAddress, 
				encodingScheme.incomingKeyExchangeMessage(copyOfRange(data, 0, 22), data, offset + 22, length - 22),
				(o, n) -> o.getKeyGenerationCounter() >= n.getKeyGenerationCounter() ? o : n);
		
		Certificate cert = certificates.get(socketAddress);
		if(cert == null) {
			cert = clusterInformation.getCertificateFor(remoteId);
			if(cert == null) {
				logger.error("There is no certificate available for the remote node {}. Discovery will not be able to exchange dynamically generated keys", 
						remoteId);
				return;
			}
		}
		if(cert != null) {
			state = keyGenerationState.get();
			byte[] header = getKeyExchangeHeader(KEY_EXCHANGE_RESPOND, state);
			doSend(new byte[][] { encodingScheme.outgoingKeyExchangeMessage(header, cert) }, 
					localId.toString(), state, remoteId, socketAddress);
			if(logger.isDebugEnabled()) {
				logger.debug("Outgoing key response from {} to {} on {}", 
						new Object[] {localId, remoteId, socketAddress});
			}
			acknowledge(remoteId.toString(), state, socketAddress);
		}
	}

	private byte[] getKeyExchangeHeader(byte type, int state) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (DataOutputStream dos = new DataOutputStream(baos)) {
			dos.writeByte(1);
			dos.writeByte(type);
			dos.writeLong(localId.getMostSignificantBits());
			dos.writeLong(localId.getLeastSignificantBits());
			dos.writeInt(state);
		} catch (IOException ioe) {
			//Can't happen with a ByteArrayOutputStream
		}
		
		byte[] header = baos.toByteArray();
		return header;
	}

	private void handleDiscoveryMessage(SocketAddress address, DataInput input) throws IOException,
			UnsupportedEncodingException {
		byte messageType = input.readByte();
		switch(messageType) {
			case 0: {
					EndpointDescription ed = EndpointSerializer.deserializeEndpoint(input);
					int state = input.readInt();
					
					if(logger.isDebugEnabled()) {
						logger.debug("Received endpoint announcement {} in {} from {}",
								new Object[]{ed.getId(), localId, ed.getFrameworkUUID()});
					}
					
					workers.execute(() -> acknowledge(ed.getId(), state, address));
					listener.announcementEvent(ed, state);
					break;
				}
			case 1: {
					String endpointId = input.readUTF();
					int state = input.readInt();
					
					if(logger.isDebugEnabled()) {
						logger.debug("Received endpoint revocation {} state {} in {}",
								new Object[]{endpointId, state, localId});
					}
					
					workers.execute(() -> acknowledge(endpointId, state, address));
					listener.revocationEvent(endpointId, state);
					break;
				}
			case 2: {
					UUID remote = new UUID(input.readLong(), input.readLong());
					String endpointId = input.readUTF();
					Integer stateBeingAcked = input.readInt();
					
					if(logger.isDebugEnabled()) {
						logger.debug("Received acknowledgement announcement {} in {} from {}",
								new Object[]{ endpointId, localId, remote});
					}
					
					pendingAcknowledgments.computeIfPresent(remote, (k, v) -> {
							ConcurrentMap<String, PendingAck> toReturn = v.entrySet().stream()
								.filter(e -> !endpointId.equals(e.getKey()) || !stateBeingAcked.equals(e.getValue().state))
								.collect(Collectors.toConcurrentMap(Entry::getKey, Entry::getValue));
							return toReturn.isEmpty() ? null : toReturn;
						});
					break;
				}
			case 3: {
				UUID remote = new UUID(input.readLong(), input.readLong());
				int counter = input.readInt();
				
				if(logger.isDebugEnabled()) {
					logger.debug("Received reminder announcement in {} from {}",
							new Object[]{localId, remote});
				}
				
				workers.execute(() -> acknowledge(localId.toString(), counter, address));
				
				Map<String, Integer> known = listener.getEndpointsFor(remote);
				int size = input.readUnsignedShort();
				Collection<String> unknownIds = new ArrayList<>();
				
				for(int i = 0; i < size; i ++) {
					String endpointId = input.readUTF();
					if(known.remove(endpointId) == null) {
						unknownIds.add(endpointId);
					}
				}
				if(!unknownIds.isEmpty()) {
					requestReAnnounce(unknownIds, counter, remote, address);
				}
				known.forEach((id, state) -> listener.revocationEvent(id, state));
				
				break;
			}
			case 4: {
				UUID ack = new UUID(input.readLong(), input.readLong());
				UUID remote = new UUID(input.readLong(), input.readLong());
				int counter = input.readInt();
				
				if(logger.isDebugEnabled()) {
					logger.debug("Received acknowledgement for announcement {} with state {} from {}",
							new Object[]{ack, counter, remote});
				}
				
				workers.execute(() -> acknowledge(ack.toString(), counter, address));
				
				int size = input.readUnsignedShort();
				
				for(int i = 0; i < size; i ++) {
					local.republish(input.readUTF(), remote);
				}
				break;
			}
			default:
				throw new UnsupportedEncodingException("The discovery message type " + messageType + " is not known");
		}
	}
	
	private final UUID reannouncementId = UUID.randomUUID();
	
	private void requestReAnnounce(Collection<String> unknownIds, Integer counter, UUID remote, SocketAddress address) {
		
		if(logger.isDebugEnabled()) {
			logger.debug("Requesting reannouncement of the endpoints {} from the node {} at {}",
					new Object[] {unknownIds, remote, address});
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			dos.writeByte(REQUEST_REANNOUNCEMENT.ordinal());
			dos.writeLong(reannouncementId.getMostSignificantBits());
			dos.writeLong(reannouncementId.getLeastSignificantBits());
			dos.writeLong(localId.getMostSignificantBits());
			dos.writeLong(localId.getLeastSignificantBits());
			dos.writeInt(counter);
			
			dos.writeShort(unknownIds.size());
			
			for(String s : unknownIds) {
				dos.writeUTF(s);
			}
			
			dos.close();
		} catch (IOException ioe) {
			logger.error("Unable to build the endpoint reannouncement request", ioe);
			return;
		}
		
		doSend(new byte[][] {discoveryHeader, baos.toByteArray()}, reannouncementId.toString(), 
				counter, remote, address);
		if(logger.isDebugEnabled()) {
			logger.debug("Requested reannouncement id {} from {} at {}", 
					new Object[] {reannouncementId, remote, address});
		}
	}

	private void acknowledge(String endpointId, int state,
			SocketAddress socketAddress) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			dos.writeByte(ACKNOWLEDGMENT.ordinal());
			dos.writeLong(localId.getMostSignificantBits());
			dos.writeLong(localId.getLeastSignificantBits());
			
			dos.writeUTF(endpointId);
			dos.writeInt(state);
			
			dos.close();
		} catch (IOException ioe) {
			logger.error("Unable to acknowledge receipt of an endpoint announcement", ioe);
			return;
		}
		
		byte[] toSend = encodingScheme.encode(discoveryHeader, baos.toByteArray(), 0, baos.size());
		
		if(logger.isDebugEnabled()) {
			logger.debug("Acknowledging message sent to {} for endpoint {} at {}", 
					new Object[] {localId, endpointId, state});
		}
		safeSend(new DatagramPacket(toSend, toSend.length, socketAddress));
	}

	private class PendingAck {
		private final byte[][] rawMessage;
		private byte[] message;
		private final Integer state;
		private final UUID targetId;
		private final SocketAddress target;
		private long lastSentTime = NANOSECONDS.toMillis(System.nanoTime());
		
		public PendingAck(byte[][] rawMessage, byte[] message, Integer state, UUID targetId, SocketAddress target) {
			this.rawMessage = rawMessage;
			this.message = message;
			this.state = state;
			this.targetId = targetId;
			this.target = target;
		}
		
		synchronized void awaitingAck(long now) {
			if(now - lastSentTime > 1000) {
				if(logger.isDebugEnabled()) {
					logger.debug("No acknowledgement received, resending a discovery message from {} to {} on {}", 
							new Object[] {localId, targetId, target});
				}
				safeSend(new DatagramPacket(message, message.length, target));
			}
		}
		
		synchronized void updateEncoding() {
			if(rawMessage.length == 2) {
				message = encodingScheme.encode(rawMessage[0], rawMessage[1], 0, rawMessage[1].length);
			}
		}
	}
	
	public void publishEndpoint(EndpointDescription ed, Integer state, UUID remoteNodeId, SocketAddress address) {
		if(!open.get()) return;
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			dos.writeByte(ANNOUNCEMENT.ordinal());
			EndpointSerializer.serialize(ed, dos);
			dos.writeInt(state);
			dos.close();
		} catch (Exception e) {
			logger.error("Unable to announce an endpoint with properties " + ed.getProperties(), e);
		}
		
		byte[] encoded = encodingScheme.encode(discoveryHeader, baos.toByteArray(), 0, baos.size());
		if(encoded.length > 65535) {
			logger.error("The serialized endpoint with properties {} is too large to send ({} bytes).", ed.getProperties(), encoded.length);
			return;
		}
		String endpointId = ed.getId();
		
		doSend(new byte[][] {discoveryHeader, baos.toByteArray()}, endpointId, state, remoteNodeId, address);
		if(logger.isDebugEnabled()) {
			logger.debug("Outgoing endpoint publication id: {}, state {} from {} to {}",
					new Object[] {endpointId, state, localId, remoteNodeId});
		}
	}

	private void doSend(byte[][] rawData, String endpointId, Integer state,
			UUID remoteNodeId, SocketAddress address) {
		workers.execute(() -> {
			byte[] data = rawData.length == 2 ? encodingScheme.encode(rawData[0], rawData[1], 0, 
					rawData[1].length) : rawData[0];
			pendingAcknowledgments.compute(remoteNodeId, (k,v) -> {
				Map<String, PendingAck> computed = (v == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(v);
				computed.compute(endpointId, (k2, v2) -> {
						if(v2 == null || state > v2.state) {
							return new PendingAck(rawData, data, state, remoteNodeId, address);
						} else {
							return v2;
						}
					});
				return computed;
			});
			if(isBound()) {
				safeSend(new DatagramPacket(data, data.length, address));
			}
		});
	}
	
	public void revokeEndpoint(String endpointId, Integer state, UUID remoteNodeId, SocketAddress address) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			dos.writeByte(REVOCATION.ordinal());
			dos.writeUTF(endpointId);
			dos.writeInt(state);
			
			dos.close();
			doSend(new byte[][] {discoveryHeader, baos.toByteArray()}, 
					endpointId, state, remoteNodeId, address);
			if(logger.isDebugEnabled()) {
				logger.debug("Outgoing endpoint revocation id: {}, state {} from {} to {}",
						new Object[] {endpointId, state, localId, remoteNodeId});
			}
		} catch (IOException ioe) {
			logger.error("Unable to revoke endpoint " + endpointId, ioe);
		}
	}
	
	private void safeSend(DatagramPacket dp) {
		try {
			if(logger.isTraceEnabled()) {
				logger.trace("Sending data to {}", dp.getSocketAddress());
			}
			udpSocket.get().send(dp);
		} catch (IOException e) {
			logger.warn("Failed to send a discovery message to " + dp.getSocketAddress(), e);
		}
	}


	public int getUdpPort() {
		return udpPort.get();
	}

	public void newDiscoveryEndpoint(UUID remoteId, SocketAddress address) {
		socketToId.put(address, remoteId);
		if(encodingScheme.requiresCertificates()) {
			Certificate remoteCert = clusterInformation.getCertificateFor(remoteId);
			if(remoteCert == null) {
				logger.error("There is no certificate available for the remote node {}. Discovery will not be able to exchange dynamically generated keys", 
						remoteId);
				return;
			}
			
			certificates.putIfAbsent(address,remoteCert);
			
			if(!encryptionDetails.containsKey(address)) {
				beginEncryptionExchange(remoteId, address, remoteCert);
			}
		}
	}

	private void beginEncryptionExchange(SocketAddress address) {
		UUID uuid = socketToId.get(address);
		if(uuid == null) {
			logger.debug("The node at address {} is not known and cannot be contacted", address);
			return;
		}
		
		Certificate certificate = certificates.get(address);
		if(certificate == null) {
			certificate = clusterInformation.getCertificateFor(uuid);
			if(certificate == null) {
				logger.error("There is no certificate available for the remote node {}. Discovery will not be able to exchange dynamically generated keys", 
						uuid);
				return;
			}
		}
		beginEncryptionExchange(uuid, address, certificate);
	}

	private void beginEncryptionExchange(UUID remoteId, SocketAddress address,
			Certificate remoteCert) {
		int kgs = keyGenerationState.get();
		byte[] encoded = encodingScheme.outgoingKeyExchangeMessage(getKeyExchangeHeader(KEY_EXCHANGE_INITIATE, kgs), remoteCert);
		doSend(new byte[][] {encoded}, localId.toString(), kgs, remoteId, address);
		
		if(logger.isDebugEnabled()) {
			logger.debug("Starting encryption exchange from {} to {}",
					new Object[] {localId, remoteId});
		}
	}

	public void stopCalling(UUID id, SocketAddress socketAddress) {
		pendingAcknowledgments.remove(id);
		socketToId.remove(socketAddress);
		certificates.remove(socketAddress);
		encryptionDetails.remove(socketAddress);
	}

	public boolean isBound() {
		return bound.get();
	}

	public void sendReminder(Collection<String> published, int counter, UUID remoteNodeId,
			InetSocketAddress address) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			dos.writeByte(REMINDER.ordinal());
			dos.writeLong(localId.getMostSignificantBits());
			dos.writeLong(localId.getLeastSignificantBits());
			dos.writeInt(counter);
			
			dos.writeShort(published.size());
			
			for(String s : published) {
				dos.writeUTF(s);
			}
			
			dos.close();
		} catch (IOException ioe) {
			logger.error("Unable to build the endpoint reminder announcement", ioe);
			return;
		}
		
		doSend(new byte[][] {discoveryHeader, baos.toByteArray()}, remoteNodeId.toString(), 
				counter, remoteNodeId, address);
		if(logger.isDebugEnabled()) {
			logger.debug("Reminder from {} to {}", new Object[] {localId, remoteNodeId});
		}
	}

}
