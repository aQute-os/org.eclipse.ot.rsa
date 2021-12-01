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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.osgi.util.promise.Promises.failed;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.net.ServerSocketFactory;

import org.osgi.service.cm.ConfigurationException;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.FailedPromisesException;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.Promises;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.gossip.Gossip;
import com.paremus.gossip.GossipComms;
import com.paremus.gossip.GossipReplicator;
import com.paremus.gossip.activator.Config;
import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncryptionDetails;
import com.paremus.net.encode.ExpiredEncryptionDetailsException;
import com.paremus.net.encode.InvalidEncodingException;
import com.paremus.net.encode.MissingEncryptionDetailsException;

public class SocketComms implements GossipComms {

	private static final Logger logger = LoggerFactory.getLogger(SocketComms.class);
	
	private final AtomicReference<ScheduledExecutorService> workers = new AtomicReference<>();
	
	private final Semaphore maxConcurrentUDPMessages = new Semaphore(10);
	private final Semaphore maxConcurrentTCPConnections = new Semaphore(4);
	
	private final String cluster;
	private final UUID id;
	
	private final AtomicBoolean open = new AtomicBoolean(false);
	
	private final AtomicReference<Thread> tcpServerThread = new AtomicReference<>();
	private final AtomicReference<Thread> udpServerThread = new AtomicReference<>();
	
	private final AtomicReference<DatagramSocket> udpSocket = new AtomicReference<>();
	private final AtomicReference<ServerSocket> serverSocket = new AtomicReference<ServerSocket>();

	private final int udpPort;
	private final int tcpPort;

	private final AtomicReference<Gossip> listener = new AtomicReference<>();
	private final AtomicReference<GossipReplicator> replicator = new AtomicReference<>();

	private final InetAddress bindAddress;

	private final int networkMTU;
	
	private final EncodingScheme encoder;
	
	private final ConcurrentMap<InetSocketAddress, CertificateWrapper> certificates = new ConcurrentHashMap<>();
	private final ConcurrentMap<InetSocketAddress, EncryptionDetails> encryptionDetails = new ConcurrentHashMap<>();

	private final byte[] keyExchangeRequestHeader = new byte[]{1, 4};
	private final byte[] outgoingKeyExchangeHeader = new byte[]{1, 5};
	private final byte[] outgoingKeyUpdateHeader = new byte[]{1, 6};

	private final ServerSocketFactory factory;

	public SocketComms(String cluster, UUID id, Config config, EncodingScheme encoder,
			ServerSocketFactory factory) throws IOException, ConfigurationException {
		this.cluster = cluster;
		this.id = id;
		this.encoder = encoder;
		this.factory = factory;
		
		bindAddress = InetAddress.getByName(config.bind_address());
		DatagramSocket ds = null;
		ServerSocket ss = null;
		
		for(int i = 0; i < config.max_members(); i++) {
			try {
				ds = new DatagramSocket(config.base_udp_port() + i * config.port_increment(), bindAddress);
				ss = factory.createServerSocket(config.base_tcp_port() + i * config.port_increment(), 10, bindAddress);
				break;
			} catch (BindException be) {
				if(ds != null) ds.close();
				try {
					if(ss != null) ss.close();
				} catch (IOException ioe) {}
				ds = null;
				ss = null;
			}
		}
		
		if(ds == null || ss.equals(null)) {
			throw new BindException("Unable to bind to a port");
		}
		
		udpSocket.set(ds);
		serverSocket.set(ss);
		
		udpPort = ds.getLocalPort();
		tcpPort = ss.getLocalPort();
		
		if(logger.isDebugEnabled()) {
			logger.debug("Gossip communications for {} in cluster {} reserving UDP port {} and TCP port {}",
					new Object[] {id, cluster, udpPort, tcpPort});
		}
		
		int discoveredMTU = -1;
		if(bindAddress.isAnyLocalAddress()) {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while(interfaces.hasMoreElements()) {
				NetworkInterface ni = interfaces.nextElement();
				if(discoveredMTU < 0 || discoveredMTU > ni.getMTU()) {
					discoveredMTU = ni.getMTU();
				}
			}
		} else {
			NetworkInterface networkInterface = NetworkInterface.getByInetAddress(bindAddress);
			if(networkInterface != null) {
				discoveredMTU = networkInterface.getMTU();
			}
		}

		networkMTU = discoveredMTU <= 0 ? 1500 : discoveredMTU;
		logger.info("The discovered MTU for the gossip layer is {}. If gossip messages regularly exceed this size then packet loss may become an issue.", networkMTU);
	}

	public synchronized void startListening(Gossip ml, GossipReplicator replicator, ScheduledExecutorService workers) {
		if(!open.compareAndSet(false, true)) {
			return;
		}
		
		this.listener.set(ml);
		this.workers.set(workers);
		this.replicator.set(replicator);
		
		if(logger.isDebugEnabled()) {
			logger.debug("Gossip communications starting for {} in cluster {} using UDP port {} and TCP port {}",
					new Object[] {id, cluster, udpPort, tcpPort});
		}
		
		workers.scheduleWithFixedDelay(this::ensureListenerThreadsAreStarted, 0, 500, TimeUnit.MILLISECONDS);
		workers.scheduleWithFixedDelay(this::reapUnusedData, 30000, 30000, TimeUnit.MILLISECONDS);

		if(logger.isDebugEnabled()) {
			logger.debug("Gossip communications started for {} in cluster {} using UDP port {} and TCP port {}",
					new Object[] {id, cluster, udpPort, tcpPort});
		}
	}
	
	private void reapUnusedData() {
		//TODO make this configurable
		long now = NANOSECONDS.toMillis(System.nanoTime());
		Set<InetSocketAddress> toRemove = certificates.entrySet().stream()
			.filter(e -> (now - e.getValue().lastRetrieved()) > 30000)
			.map(Entry::getKey)
			.collect(Collectors.toSet());
		
		if(logger.isDebugEnabled() && !toRemove.isEmpty()) {
			logger.debug("Releasing security data for the endpoints {} as they have not been used recently",
					new Object[] {toRemove});
		}
		
		certificates.keySet().removeAll(toRemove);
		encryptionDetails.keySet().removeAll(toRemove);
	}
	
	public synchronized void stopListening() {
		if(!open.compareAndSet(true, false)) {
			return;
		}
		if(logger.isDebugEnabled()) {
			logger.debug("Gossip communications shutting down for {} in cluster {}",
					new Object[] {id, cluster});
		}
		
		ScheduledExecutorService executorService = this.workers.get();
		executorService.shutdown();
		interruptThread(udpServerThread);
		interruptThread(tcpServerThread);
		try {
			if(!executorService.awaitTermination(1000, MILLISECONDS)) {
				logger.warn("Gave up waiting for the threads to terminate");
			}
		} catch (InterruptedException e) {}
		executorService.shutdownNow();
	}
	
	private void interruptThread(AtomicReference<Thread> ref) {
		ofNullable(ref.get()).ifPresent(Thread::interrupt);
	}

	private boolean isRunning(AtomicReference<Thread> ref) {
		return ofNullable(ref.get()).map(Thread::isAlive).orElse(false);
	}
	
	private synchronized void ensureListenerThreadsAreStarted() {
		if(!open.get() || Thread.currentThread().isInterrupted())
			return;
		
		if(udpSocket.get().isClosed()) {
			interruptThread(udpServerThread);
			try {
				DatagramSocket ds = new DatagramSocket(udpPort, bindAddress);
				udpSocket.set(ds);
				Thread t = new Thread(this::listenUDP, "UDP Gossip listener - " + cluster + " port " + udpPort);
				t.setDaemon(true);
				udpServerThread.set(t);
				t.start();
			} catch (SocketException se) {
				logger.error("Unable to regain UDP listener port " + udpPort + " for " + cluster, se);
			}
		} else if (!isRunning(udpServerThread)) {
			Thread t = new Thread(this::listenUDP, "UDP Gossip listener - " + cluster + " port " + udpPort);
			t.setDaemon(true);
			udpServerThread.set(t);
			t.start();
		}
		
		if(serverSocket.get().isClosed()) {
			interruptThread(tcpServerThread);
			try {
				serverSocket.set(factory.createServerSocket(tcpPort, 10, bindAddress));
				Thread t = new Thread(this::listenTCP, "TCP Gossip listener - " + cluster + " port " + tcpPort);
				t.setDaemon(true);
				tcpServerThread.set(t);
				t.start();
			} catch (IOException se) {
				logger.error("Unable to regain TCP listener port " + tcpPort + " for " + cluster, se);
			}
		} else if (!isRunning(tcpServerThread)) {
			Thread t = new Thread(this::listenTCP, "TCP Gossip listener - " + cluster + " port " + tcpPort);
			t.setDaemon(true);
			tcpServerThread.set(t);
			t.start();
		}
	}
	
	private void listenUDP() {
		int maxUDP = (1 << 16) -1;
		main: while(open.get() && !Thread.interrupted()) {
			DatagramPacket dp;
			try {
				while(!maxConcurrentUDPMessages.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
					if(!open.get()) break main;
					
					if(logger.isWarnEnabled()) {
						logger.warn("Timed out waiting for a UDP listener slot - it's taking a long time to process gossip:",
								new TimeoutException());
					}
				}
			} catch (InterruptedException e1) {
				logger.info("The GossipImpl UDP listener thread was interrupted and is terminating");
				return;
			}
			DatagramSocket datagramSocket = udpSocket.get();
			dp = new DatagramPacket(new byte[maxUDP], maxUDP);
			try {
				datagramSocket.receive(dp);
				
				InetSocketAddress socketAddress = (InetSocketAddress) dp.getSocketAddress();
				if(logger.isTraceEnabled()) {
					logger.trace("Received Gossip from {}", socketAddress);
				}
				
				byte[] data = dp.getData();
				int offset = dp.getOffset();
				int length = dp.getLength();
				
				if(data[offset] == -7) {
					counterPunch(dp, datagramSocket);
				} else if(data[offset] != 1) {
					throw new IOException("Unknown gossip comms version " + data[offset]);
				}
				
				switch(data[offset + 1]) {
					case 1:
						encodedGossip(socketAddress, data, offset + 2, length - 2);
						break;
					case 2:
						handleCertificateRequest(socketAddress, data, offset + 2, length - 2);
						break;
					case 3:
						handleCertificateResponse(socketAddress, data, offset + 2, length - 2);
						break;
					case 4:
						handleKeyExchangeRequest(socketAddress);
						break;
					case 5:
						handleKeyExchange(socketAddress, outgoingKeyExchangeHeader, 
								data, offset + 2, length - 2);
						break;
					case 6:
						handleKeyExchange(socketAddress, outgoingKeyUpdateHeader, data, 
								offset + 2, length - 2);
						break;
				}
				
			} catch (InvalidEncodingException e) {
				logger.info("The Gossip UDP listener was unable to decode a message and is therefore ignoring it");
				maxConcurrentUDPMessages.release();
				continue;
			} catch (ExpiredEncryptionDetailsException e) {
				maxConcurrentUDPMessages.release();
				InetSocketAddress socketAddress = (InetSocketAddress) dp.getSocketAddress();
				logger.info("The Encryption key for {} has expired, gossip messages from that node will be ignored until a new key is available.", socketAddress);
				encryptionDetails.remove(socketAddress, e.getExpired());
				requestEncryptionData(socketAddress);
				continue;
			} catch (MissingEncryptionDetailsException e) {
				maxConcurrentUDPMessages.release();
				InetSocketAddress socketAddress = (InetSocketAddress) dp.getSocketAddress();
				logger.info("No Encryption key is currently available for {}, gossip messages from that node will be ignored until a key is available.", socketAddress);
				requestEncryptionData(socketAddress);
				continue;
			} catch(SocketTimeoutException ste) {
				//nothing to worry about
				maxConcurrentUDPMessages.release();
				continue;
			} catch (InterruptedIOException e) {
				maxConcurrentUDPMessages.release();
				logger.info("The GossipImpl UDP listener thread was interrupted and is terminating");
				return;
			} catch (RejectedExecutionException e) {
				maxConcurrentUDPMessages.release();
				if(open.get()) {
					logger.warn("The UDP executor rejected a task", e);
					continue;
				}
				return;
			} catch (IOException ioe) {
				maxConcurrentUDPMessages.release();
				if(datagramSocket.isClosed()) {
					logger.info("The GossipImpl UDP socket was closed, so the listener thread is terminating");
					return;
				} else {
					logger.warn("There was an unexpected error listening for UDP gossip, continuing", ioe);
					continue;
				}
			} catch (Throwable t) {
				maxConcurrentUDPMessages.release();
				logger.error("There was an unrecoverable error consuming a UDP gossip message", t);
				return;
			}
		}
	}

	private void counterPunch(DatagramPacket dp, DatagramSocket datagramSocket)
			throws IOException {
		if(logger.isTraceEnabled()) {
			logger.trace("Responding to a punch request from {}", dp.getSocketAddress());
		}
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (DataOutputStream dos = new DataOutputStream(baos)) {
			dos.writeShort(dp.getPort());
			byte[] address = dp.getAddress().getAddress();
			dos.writeShort(address.length);
			dos.write(address);
		}
		datagramSocket.send(new DatagramPacket(baos.toByteArray(), baos.size(), dp.getSocketAddress()));
	}


	private void handleKeyExchange(InetSocketAddress socketAddress, byte[] header, byte[] data, 
			int offset, int length) {
		try {
			boolean debug = logger.isDebugEnabled();
			if(debug) {
				logger.debug("Incoming key exchange request from {}", socketAddress);
			}
			encryptionDetails.merge(socketAddress, 
					encoder.incomingKeyExchangeMessage(header, data, offset, length),
					(o, n) -> o.getKeyGenerationCounter() >= n.getKeyGenerationCounter() ? o : n);
			if(debug) {
				logger.debug("Successfully decoded the key from {}", socketAddress);
			}
			if(header[1] == outgoingKeyExchangeHeader[1]) {
				ofNullable(listener.get()).ifPresent(l -> l.ping(socketAddress));
			}
		} finally {
			maxConcurrentUDPMessages.release();
		}
	}

	private void handleKeyExchangeRequest(InetSocketAddress socketAddress) {
		try {
			if(!encoder.requiresCertificates()) {
				logger.warn("There was a key exchange request from {}, but this node is not able to securely exchange keys", socketAddress);
				return;
			}
			CertificateWrapper wrapper = certificates.get(socketAddress);
			if(wrapper == null) {
				requestEncryptionData(socketAddress);
			} else {
				sendKeyExchangeMessage(socketAddress, outgoingKeyExchangeHeader, wrapper.getCertificate());
			}
		} finally {
			maxConcurrentUDPMessages.release();
		}
	}

	private void handleCertificateRequest(InetSocketAddress socketAddress, byte[] bytes, int offset, int length) {
		try {
			if(!encoder.requiresCertificates()) {
				logger.warn("There was a certificate request from {}, but this node does not have a certificate", socketAddress);
				return;
			}
			
			handleCertificateMessage(socketAddress, bytes, offset, length);
			
			sendCertificateMessage(socketAddress, 3, encoder.getCertificate());
			
			ofNullable(workers.get()).ifPresent(w -> 
				w.schedule(() -> requestEncryptionData(socketAddress), 50, TimeUnit.MILLISECONDS));
		} catch (Exception e) {
			logger.error("Unable to handle a certificate request from {}", socketAddress, e);
		} finally {
			maxConcurrentUDPMessages.release();
		}
	}

	private void handleCertificateResponse(InetSocketAddress socketAddress, byte[] bytes, int offset, int length) {
		try {
			if(!encoder.requiresCertificates()) {
				logger.warn("There was a certificate response from {}, but this node does not have a certificate", socketAddress);
				return;
			}
			
			Certificate cert = handleCertificateMessage(socketAddress, bytes, offset, length);
			
			sendKeyExchangeMessage(socketAddress, outgoingKeyExchangeHeader, cert);
		} catch (Exception e) {
			logger.error("Unable to handle a certificate response from {}", socketAddress, e);
		} finally {
			maxConcurrentUDPMessages.release();
		}
	}

	private void sendKeyExchangeMessage(InetSocketAddress socketAddress, byte[] header,
			Certificate cert) {
		if(logger.isDebugEnabled()) {
			logger.debug("Sending a secure encryption key exchange to {} for cluster {}", 
					new Object[] {socketAddress, cluster});
		}
		byte[] encoded = encoder.outgoingKeyExchangeMessage(header, cert);
		
		DatagramPacket dp = new DatagramPacket(encoded, encoded.length, socketAddress);
		
		ofNullable(workers.get()).ifPresent(w -> w.execute(() -> safeSend(dp)));
	}

	private Certificate handleCertificateMessage(InetSocketAddress from, byte[] bytes, int offset, int length)
			throws CertificateException, IOException {
		if(logger.isDebugEnabled()) {
			logger.debug("Received a certificate from {}", from);
		}
		
		ByteArrayInputStream bais = new ByteArrayInputStream(bytes, offset, length);
		Certificate cert = CertificateFactory.getInstance("X.509")
				.generateCertificate(bais);
		encoder.isAcceptable(cert, from.getAddress());
		
		
		if(logger.isDebugEnabled()) {
			int sigOffset = offset + length - bais.available();
			try (DataInputStream dis = new DataInputStream(
					new ByteArrayInputStream(bytes, sigOffset, length + offset - sigOffset))) {
				logger.debug("Received certificate for node " + new UUID(dis.readLong(), dis.readLong()));
			}
		}
		
		certificates.compute(from, (k,v) -> {
			if(v != null) {
				v.touch();
				return v;
			} else {
				return new CertificateWrapper(cert);
			}
		});
		
		if(logger.isDebugEnabled()) {
			logger.debug("Accepted the certificate from {}", from);
		}
		
		return cert;
	}

	private void sendCertificateMessage(InetSocketAddress socketAddress, int type,
			Certificate localCert) throws IOException {
		if(logger.isDebugEnabled()) {
			logger.debug("Sending a certificate to {} for cluster {}", 
					new Object[] {socketAddress, cluster});
		}
		
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(1);
		baos.write(type);
		
		try {
			baos.write(localCert.getEncoded());
		} catch (CertificateEncodingException e) {
			throw new IOException("Unable to serialize certificate", e);
		}
		
		byte[] header = baos.toByteArray();
		baos.reset();
		
		try (DataOutputStream dos = new DataOutputStream(baos)){
			dos.writeLong(id.getMostSignificantBits());
			dos.writeLong(id.getLeastSignificantBits());
		}
		
		byte[] encoded = encoder.encode(header, baos.toByteArray(), 0, 16);
		
		DatagramPacket dp = new DatagramPacket(encoded, encoded.length, socketAddress);
		
		ofNullable(workers.get()).ifPresent(w -> w.execute(() -> safeSend(dp)));
	}


	private void encodedGossip(InetSocketAddress socketAddress, byte[] data, int offset,
			int length) {
		
		Gossip ml = listener.get();
		
		EncryptionDetails details;
		if(encoder.requiresCertificates()) {
			details = requestEncryptionData(socketAddress);
			if(details == null) {
				maxConcurrentUDPMessages.release();
				logger.debug("Ignoring a gossip message from {} because we don't have the key to decrypt it",
						socketAddress);
				return;
			}
		} else {
			details = null;
		}
		DataInput input = encoder.validateAndDecode(Arrays.copyOfRange(data, offset -2, offset), data, offset, length, details);
		workers.get().execute(() -> releaseAfter("UDP", maxConcurrentUDPMessages, 
				() -> ml.handleMessage(socketAddress, input)));
	}
	
	private void listenTCP() {
		main: while(open.get() && !Thread.interrupted()) {
			try {
				while(!maxConcurrentTCPConnections.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
					if(!open.get()) break main;
					
					if(logger.isWarnEnabled()) {
						logger.warn("Timed out waiting for a TCP listener slot - it's taking a long time to process gossip:",
								new TimeoutException());
					}
				}
			} catch (InterruptedException e) {
				logger.info("The GossipImpl TCP listener thread was interrupted and is terminating");
				return;	
			}
			try {
				Socket client = serverSocket.get().accept();
				if(logger.isDebugEnabled()) logger.debug("Incoming synchronization request from {}", client.getRemoteSocketAddress());
				
				replicator.get().incomingExchangeSnapshots(workers.get(), client)
					.then(p -> {
							if(logger.isDebugEnabled()) {
								logger.debug("Successfully handled incoming snapshot exchange from: " + client.getRemoteSocketAddress());
							}
							return null;
						}, p -> {
							if(open.get()) {
								Throwable failure = p.getFailure();
								if(failure instanceof FailedPromisesException) {
									FailedPromisesException fpe = (FailedPromisesException) failure;
									for(Promise<?> p2 : fpe.getFailedPromises()) {
										if(p2.getFailure() != null) {
											failure = p2.getFailure();
											break;
										}
									}
								}
								logger.info("There was an error servicing an incoming synchronization request in node " + id, failure);
							} else {
								logger.info("Abandoned an incoming synchronization request because {} is being shut down", id);
							}
						}).onResolve(() -> releaseAfter("TCP", maxConcurrentTCPConnections, () -> {}));
			} catch (InterruptedIOException e) {
				maxConcurrentTCPConnections.release();
				logger.info("The GossipImpl TCP listener thread was interrupted and is terminating");
				return;
			} catch (IOException ioe) {
				maxConcurrentTCPConnections.release();
				if(serverSocket.get().isClosed()) {
					logger.info("The GossipImpl TCP socket was closed, so the listener thread is terminating");
					return;
				} else {
					logger.trace("There was an unexpected error listening for TCP gossip, continuing", ioe);
					continue;
				}
			} catch (Throwable t) {
				maxConcurrentTCPConnections.release();
				logger.error("There was an unrecoverable error accepting a TCP gossip exchange", t);
				return;
			}
		}
	}

	private void releaseAfter(String name, Semaphore s, Runnable r) {
		try {
			r.run();
		} catch (Exception e) {
			logger.info("Failed to process message", e);
		} finally {
			if(logger.isDebugEnabled()) {
				logger.debug("Releasing semaphore for {}", name);
			}
			s.release();
		}
	}
	
	public void destroy() {
		stopListening();
		udpSocket.get().close();
		try {
			serverSocket.get().close();
		} catch (IOException e) {}
	}
	
	private final byte[] defaultHeader = new byte[] {1,1};
	/* (non-Javadoc)
	 * @see com.paremus.gossip.net.GossipComms#publish(byte[], java.util.Collection)
	 */
	@Override
	public void publish(byte[] message, Collection<SocketAddress> participants) {
		if(!open.get()) return;
		
		byte[] encoded = encoder.encode(defaultHeader, message, 0, message.length);
		if(encoded.length > networkMTU) {
			logger.warn("A large gossip message ({} bytes) is being sent, this often indicates that a message is being forwarded too many times.", encoded.length);
		}
		
		ofNullable(workers.get()).ifPresent((w) -> w.execute(
				() -> participants.stream().map((sa) -> new DatagramPacket(encoded, encoded.length, sa))
				.forEach(dp -> {
					if(encoder.requiresCertificates()) {
						requestEncryptionData((InetSocketAddress) dp.getSocketAddress());
					}
					safeSend(dp);
				})));
	}


	private EncryptionDetails requestEncryptionData(InetSocketAddress address) {
		EncryptionDetails details = encryptionDetails.get(address);

		if(details == null) {
			CertificateWrapper wrapper = certificates.get(address);
			if(wrapper == null) {
				try {
					sendCertificateMessage(address, 2, encoder.getCertificate());
				} catch (IOException e) {
					logger.error("A problem occured sending a certificate to {}", address, e);
				}
				return null;
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("Requesting a key exchage with {} for cluster {}",
							new Object[] {address, cluster});
				}
				DatagramPacket dp = new DatagramPacket(keyExchangeRequestHeader, 2, address);
				ofNullable(workers.get()).ifPresent(w -> w.execute(() -> safeSend(dp)));
			}
		}

		return details;
	}
	
	private void safeSend(DatagramPacket dp) {
		try {
			DatagramSocket datagramSocket = udpSocket.get();
			if(datagramSocket == null) {
				if(open.get()) {
					logger.debug("Unable to send a message to {}", dp.getSocketAddress());
				}
			} else {
				if(logger.isTraceEnabled()) {
					logger.trace("Sending data to {}", dp.getSocketAddress());
				}
				datagramSocket.send(dp);
			}
		} catch (IOException e) {
			logger.debug("Failed to send gossip message to " + dp.getSocketAddress(), e);
		}
	}
	
	/* (non-Javadoc)
	 * @see com.paremus.gossip.net.GossipComms#replicate(com.paremus.gossip.cluster.impl.MemberInfo, java.util.Collection)
	 */
	@Override
	public Promise<Void> replicate(MemberInfo member,
			Collection<Snapshot> snapshots) {
		if(!open.get()) {
			IllegalStateException failure = new IllegalStateException("Communications have been shut down");
			logger.error("Unable to synchronize members", failure);
			return Promises.failed(failure);
		}
		
		ScheduledExecutorService ses = workers.get();
		GossipReplicator rep = replicator.get();
		if(ses != null) {
			if(encoder.requiresCertificates()) {
				requestEncryptionData((InetSocketAddress) member.getUdpAddress());
			}
			try {
				return rep.outgoingExchangeSnapshots(ses, member, snapshots, bindAddress)
						.then(p -> {
							if(logger.isDebugEnabled()) {
								logger.debug("Successfully handled outgoing snapshot exchange " + p.getValue() + " with " +
										member.getId() + " at " + member.getTcpAddress());
							}
							return null;
						}, p -> {
							if(open.get()) {
								Throwable failure = p.getFailure();
								if(failure instanceof FailedPromisesException) {
									FailedPromisesException fpe = (FailedPromisesException) failure;
									for(Promise<?> p2 : fpe.getFailedPromises()) {
										if(p2.getFailure() != null) {
											failure = p2.getFailure();
											break;
										}
									}
								}
								logger.info("There was an error servicing an outgoing synchronization request in node " + id, failure);
								logger.error("Unable to complete the synchronization exchange from " + id 
									+ " to " + member.getId() + " at " + member.getTcpAddress(), failure);
							} else {
								logger.info("Abandoned a synchronization exchange from {} to {} at {} because the gossip component was being shut down",
										new Object[] {id, member.getId(), member.getTcpAddress()});
							}
						});
			} catch (RuntimeException re) {
				logger.error("Unable to enqueue the resynchronization request", re);
				return failed(re);
			}
		} else {
			IllegalStateException failure = new IllegalStateException("No worker pool");
			logger.error("Unable to synchronize members", failure);
			return failed(failure);
		}
	}

	public int getUdpPort() {
		return udpPort;
	}
	
	public int getTcpPort() {
		return tcpPort;
	}

	public InetAddress getBindAddress() {
		return bindAddress;
	}

	public Certificate getCertificateFor(SocketAddress address) {
		return ofNullable(certificates.get(address)).map(CertificateWrapper::getCertificate).orElse(null);
	}
	
	EncryptionDetails getEncryptionDetailsFor(SocketAddress udpAddress) {
		return encryptionDetails.get(udpAddress);
	}

	void setCertificateFor(SocketAddress address, Certificate cert) {
		certificates.putIfAbsent((InetSocketAddress) address, new CertificateWrapper(cert));
	}
	
	void setEncryptionDetailsFor(SocketAddress udpAddress, EncryptionDetails ed) {
		encryptionDetails.putIfAbsent((InetSocketAddress) udpAddress, ed);
	}

	public Promise<InetSocketAddress> punch(DatagramSocket ds, List<SocketAddress> peers) {
		
		Deferred<InetSocketAddress> d = new Deferred<>();
		
		Thread requester = new Thread() {
			{
				setDaemon(true);
			}
			
			public void run() {
				while(!Thread.currentThread().isInterrupted()) {
					try {
						for(SocketAddress sa : peers) {
							ds.send(new DatagramPacket(new byte[] {-7}, 1, sa));
							Thread.sleep(10);
						}
					} catch (Exception e) {
						//TODO log
					}
				}
			}
		};
		
		new Thread() {
			{
				setDaemon(true);
			}
			
			public void run() {
				try {
					int timeout = ds.getSoTimeout();
					try {
						ds.setSoTimeout(0);
						DatagramPacket dp = new DatagramPacket(new byte[64], 64);
						while(true) {
							ds.receive(dp);
							if(peers.contains(dp.getSocketAddress())) {
								try (DataInputStream dis = new DataInputStream(
										new ByteArrayInputStream(dp.getData(), dp.getOffset(), dp.getLength()))) {
									if(dis.readByte() != -7) {
										continue;
									}
									int port = dis.readUnsignedShort();
									byte[] address = new byte[dis.readUnsignedShort()];
									dis.readFully(address);
									
									d.resolve(new InetSocketAddress(InetAddress.getByAddress(address), port));
									return;
								}
							}
						}
					} finally {
						ds.setSoTimeout(timeout);
					}
				} catch (Exception e) {
					d.fail(e);
				} finally {
					requester.interrupt();
				}
			}
		}.start();
		
		return d.getPromise();
	}

	@Override
	public boolean preventIndirectDiscovery() {
		return encoder.requiresCertificates();
	}

	@Override
	public void sendKeyUpdate(Stream<InetSocketAddress> toNotify) {
		toNotify.forEach(sa -> {
			ofNullable(certificates.get(sa))
				.map(CertificateWrapper::getCertificate)
				.ifPresent(c -> {
					try {
						sendKeyExchangeMessage(sa, outgoingKeyUpdateHeader, c);
					} catch (Exception e) {
						logger.error("There was a problem sending an encryption key udpate to node " + sa,
								e);
					}
				});
		});
	}
	
}
