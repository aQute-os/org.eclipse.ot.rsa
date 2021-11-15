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
package com.paremus.net.encode.impl;

import static com.paremus.net.encode.impl.EncodingSchemeImpl.EncodingType.COMPRESSED;
import static com.paremus.net.encode.impl.EncodingSchemeImpl.EncodingType.ENCRYPTED;
import static com.paremus.net.encode.impl.EncodingSchemeImpl.EncodingType.PLAIN;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncryptionDetails;
import com.paremus.net.encode.ExpiredEncryptionDetailsException;
import com.paremus.net.encode.InvalidEncodingException;
import com.paremus.net.encode.MissingEncryptionDetailsException;

public class EncodingSchemeImpl implements EncodingScheme {
	
	private static final byte[] ENCRYPTED_HEADER = new byte[] {1, (byte) ENCRYPTED.ordinal()};

	private final class DecryptedStream extends DataInputStream {
		private final  Cipher cipher;
		private final Mac mac;

		private DecryptedStream(InputStream in, Cipher cipher, Mac mac) {
			super(new CipherInputStream(in, cipher));
			this.cipher = cipher;
			this.mac = mac;
		}

		public void skipForcedFlush() throws IOException {
			int toSkip = cipher.getBlockSize() * 2;
			while(toSkip !=0) {
				long skipped = skip(toSkip);
				if(skipped == 0) {
					if (read() == -1) {
					throw new EOFException("Unexpectedly reached the end of the stream");
					} else {
						skipped = 1;
					}
				}
				toSkip -= skipped;
			}
		}
		
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				releaseCipher(cipher);
				releaseMac(mac);
			}
		}
	}

	private final class EncryptedStream extends DataOutputStream {
		private final Cipher cipher;
		private final Mac mac;
		
		private final byte[] flushBuffer;
		
		private EncryptedStream(OutputStream os, Cipher c, Mac mac) {
			super(new CipherOutputStream(os, c));
			this.cipher = c;
			this.mac = mac;
			flushBuffer = new byte[c.getBlockSize() * 2];
		}

		public void forceFlush() throws IOException {
			write(flushBuffer);
			flush();
		}
		
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				releaseCipher(cipher);
				releaseMac(mac);
			}
		}
	}

	static enum EncodingType {PLAIN, COMPRESSED, ENCRYPTED, SIGNED;}
	
	private static final Logger logger = LoggerFactory.getLogger(EncodingSchemeImpl.class);
	
	private final FibreCertificateInfo certificateInfo;
	
	private final AtomicReference<EncryptionDetails> encryption = new AtomicReference<>();
	
	private final SecureRandom random;

	private final Supplier<EncryptionDetails> regenerator;
	private final Runnable onKeyRegeneration;

	private final String macAlgorithm;

	private final SocketFactory socketFactory;
	private final ServerSocketFactory serverSocketFactory;


	public EncodingSchemeImpl(FibreCertificateInfo signingPair, EncryptionDetails details, 
			SecureRandom random, Supplier<EncryptionDetails> regenerator, Runnable onKeyRegeneration, 
			String macAlgorithm, SocketFactory socketFactory, ServerSocketFactory serverSocketFactory) {
		
		this.certificateInfo = signingPair;
		this.socketFactory = socketFactory;
		this.serverSocketFactory = serverSocketFactory;
		this.encryption.set(details);
		this.random = random;
		this.regenerator = regenerator;
		this.onKeyRegeneration = onKeyRegeneration;
		this.macAlgorithm = macAlgorithm;
	}

	/* (non-Javadoc)
	 * @see com.paremus.net.encode.impl.impl.EncodingScheme#getVersion()
	 */
	@Override
	public int getVersion() {
		return 1;
	}
	
	/* (non-Javadoc)
	 * @see com.paremus.net.encode.impl.impl.EncodingScheme#requiresCertificates()
	 */
	@Override
	public boolean requiresCertificates() {
		return certificateInfo != null;
	};
	
	@Override
	public boolean isConfidential() {
		return encryption.get() != null;
	};
	
	/* (non-Javadoc)
	 * @see com.paremus.net.encode.impl.impl.EncodingScheme#getCertificate()
	 */
	@Override
	public Certificate getCertificate() {
		return certificateInfo != null ? certificateInfo.getCertificate() : null;
	};
	
	@Override
	public boolean dynamicKeyGenerationSupported() {
		return regenerator != null;
	}

	@Override
	public void regenerateKey() {
		if(regenerator == null) {
			logger.error("The security key cannot be regenerated");
			throw new IllegalStateException("The security key cannot be regenerated");
		}
		encryption.set(regenerator.get());
		try {
			onKeyRegeneration.run();
		} catch (RuntimeException re) {
			logger.error("There was an error when notifying the key regeneration listener", re);
		}
	}

	/* (non-Javadoc)
	 * @see com.paremus.net.encode.impl.impl.EncodingScheme#isAcceptable(java.security.cert.Certificate)
	 */
	@Override
	public void isAcceptable(Certificate cert, InetAddress source) throws CertificateException {
		if(certificateInfo == null) {
			//TODO log that this shouldn't be happening
			//
			throw new CertificateException("Unable to validate the certificate as there is no local signing config");
		} else {
			if(cert instanceof X509Certificate) {
				X509Certificate x509Certificate = (X509Certificate) cert;
				try {
					x509Certificate.checkValidity();
					boolean verified = false;
					for (TrustManager trustManager : certificateInfo.getTrustManagerFactory().getTrustManagers()) {
						if(trustManager instanceof X509TrustManager) {
							((X509TrustManager)trustManager).checkServerTrusted(
									new X509Certificate[] {x509Certificate}, 
									certificateInfo.getSigningKey().getAlgorithm());
							verified = true;
							break;
						}
					}
					
					if(!verified) {
						throw new CertificateException("No trust manager to check the validity of the certificate from " + source);
					}
					
					//TODO check that it is allowed to come from the sender
					
				} catch (CertificateException e) {
					//TODO configurable?
					throw e;
				}
				//TODO support a revocation list
			} else {
				// TODO is this ok?
				throw new CertificateException("Unable to validate a non X509 certificate");
			}
		}
	};
	
	/* (non-Javadoc)
	 * @see com.paremus.net.encode.impl.impl.EncodingScheme#encode(byte[], byte[], int, int)
	 */
	@Override
	public byte[] encode(byte[] header, byte[] message, int offset, int length) {
		
		byte[] toSend = compressedOrPlain(header, message, offset, length);
		
		EncryptionDetails encryptionDetails = getLocalEncryptionDetails();
		if(encryptionDetails != null) {
			Cipher c = getCipher(encryptionDetails.getTransform());
			try {
				c.init(Cipher.ENCRYPT_MODE, encryptionDetails.getKey(), random);
				toSend = encrypt(header, toSend, header.length, toSend.length - header.length, c, 
						(a, b) -> addMac(a, b, encryptionDetails.getKey()));
			} catch (InvalidKeyException | IOException e) {
				logger.error("An error occurred when generating an encrypted message", e);
				throw new RuntimeException(e);
			} finally {
				releaseCipher(c);
			}
		}
		
		return toSend;
	}

	private byte[] compressedOrPlain(byte[] header, byte[] message, int offset, int length) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(header, 0, header.length);
		baos.write(1);
		if(length > 256) {
			int before = message.length;
			baos.write(COMPRESSED.ordinal());
			try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
				gos.write(message, offset, length);
			} catch (IOException ioe) {
				logger.error("Unable to compress a gossip message", ioe);
				baos.reset();
				baos.write(header, 0, header.length);
				baos.write(1);
				baos.write(PLAIN.ordinal());
				baos.write(message, offset, length);
			}
			if(logger.isDebugEnabled()) {
				logger.debug("Message compressed from {} to {} bytes", before, baos.size());
			}
		} else {
			baos.write(PLAIN.ordinal());
			baos.write(message, offset, length);
		}
		return baos.toByteArray();
	}

	private byte[] encrypt(byte[] header, byte[] toSend, int offset, int length, Cipher cipher, 
			BiFunction<byte[], byte[], byte[]> macCreator) throws IOException, InvalidKeyException {
		ByteArrayOutputStream messageOutput = new ByteArrayOutputStream();
		messageOutput.write(header, 0, header.length);
		messageOutput.write(1);
		messageOutput.write(ENCRYPTED.ordinal());
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final byte[] iv = cipher.getIV();
		if(iv != null) {
			if(iv.length > Byte.MAX_VALUE) {
				logger.error("The maximum permitted IV size is 127 bytes");
				throw new IllegalArgumentException("The IV for the transform " + cipher.getAlgorithm() + " is too big for this message version");
			}
			baos.write(iv.length);
			baos.write(iv);
		} else {
			baos.write(-1);
		}
		
		CipherOutputStream cos = new CipherOutputStream(baos, cipher);
		cos.write(toSend, offset, length);
		cos.close();
		byte[] encryptedBody = baos.toByteArray();
		
		byte[] macBytes = macCreator.apply(header, encryptedBody);
		messageOutput.write(macBytes.length >> 8);
		messageOutput.write(macBytes.length);
		messageOutput.write(macBytes);
		messageOutput.write(encryptedBody);
		return messageOutput.toByteArray();
	}

	private byte[] addMac(byte[] header, byte[] encryptedBody, Key encryptionKey) {
		Mac mac = getMac();
		byte[] macBytes;
		try {
			mac.init(encryptionKey);
			mac.update(header);
			mac.update(ENCRYPTED_HEADER);
			macBytes = mac.doFinal(encryptedBody);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException(e);
		} finally {
			releaseMac(mac);
		}
		return macBytes;
	}

	private boolean verifyMac(EncryptionDetails ed, byte[] sentMac, byte[] header, byte[] encryptedBody, int offset, int length) {
		Mac mac = getMac();
		try {
			mac.init(ed.getKey());
			mac.update(header);
			mac.update(ENCRYPTED_HEADER);
			mac.update(encryptedBody, offset, length);
			byte[] macBytes = mac.doFinal();
			return Arrays.equals(sentMac, macBytes);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException(e);
		} finally {
			releaseMac(mac);
		}
	}

	@Override
	public byte[] outgoingKeyExchangeMessage(byte[] header, Certificate remoteCertificate) {
		
		EncryptionDetails encryptionDetails = getLocalEncryptionDetails();
		if(encryptionDetails == null) {
			logger.error("Unable to create a key exchange message because no encryption is configured");
			throw new IllegalStateException("Unable to create a key exchange message because no encryption is configured");
		}
		
		PublicKey key = remoteCertificate.getPublicKey();
		Cipher c;
		if("RSA".equals(key.getAlgorithm())) {
			c = getCipher("RSA/ECB/PKCS1Padding");
		} else {
			c = getCipher(key.getAlgorithm() + "CBC/PKCS5Padding");
		}
		try {
			c.init(Cipher.ENCRYPT_MODE, remoteCertificate);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (DataOutputStream dos = new DataOutputStream(baos)) {
				dos.writeInt(encryptionDetails.getKeyGenerationCounter());
				long remainingTime = encryptionDetails.getRemainingTime(MILLISECONDS);
				//Add an extra two second leeway so that it's likely they'll receive an update before it expires
				dos.writeLong(remainingTime > 0 ? remainingTime + 2000 : remainingTime);
				dos.writeUTF(encryptionDetails.getKey().getAlgorithm());
				dos.writeUTF(encryptionDetails.getTransform());
				byte[] bytes = encryptionDetails.getKey().getEncoded();
				dos.writeShort(bytes.length);
				dos.write(bytes);
			}
			byte[] messageBody = baos.toByteArray();
			return encrypt(header, messageBody, 0, messageBody.length, c, this::addSignature);
		} catch (InvalidKeyException | IOException e) {
			logger.error("An error occurred when generating a key exchange message", e);
			throw new RuntimeException(e);
		} finally {
			releaseCipher(c);
		}
	}
	
	private byte[] addSignature(byte[] header, byte[] encryptedBody) {
		Signature sig = getSignature();
		byte[] sigBytes;
		try {
			sig.initSign(certificateInfo.getSigningKey(), random);
			sig.update(header);
			sig.update(ENCRYPTED_HEADER);
			sig.update(encryptedBody);
			sigBytes = sig.sign();
		} catch (InvalidKeyException | SignatureException e) {
			throw new IllegalArgumentException(e);
		} finally {
			releaseSignature(sig);
		}
		return sigBytes;
	}
	
	private boolean verifySignature(byte[] sentMac, byte[] header, byte[] encryptedBody, int offset, int length) {
		PublicKey publicKey = certificateInfo.getCertificate().getPublicKey();
		Signature sig = getSignature(publicKey.getAlgorithm());
		try {
			sig.initVerify(publicKey);
			sig.update(header);
			sig.update(ENCRYPTED_HEADER);
			sig.update(encryptedBody, offset, length);
			return sig.verify(sentMac);
		} catch (InvalidKeyException | SignatureException e) {
			throw new IllegalArgumentException(e);
		} finally {
			releaseSignature(sig);
		}
	}
	
	@Override
	public EncryptionDetails incomingKeyExchangeMessage(byte[] header, byte[] message, int offset, int length) {
		if(message[offset] != 1) {
			throw new IllegalArgumentException("Unknown encoding version " + message[offset]);
		}
		if(message[offset + 1] != ENCRYPTED.ordinal()) {
			throw new IllegalArgumentException("All key exchanges should be encrypted " + message[offset]);
		}
		offset += 2;
		length -= 2;
		
		PrivateKey privateKey = certificateInfo.getSigningKey();
		String incomingTransform;
		if("RSA".equals(privateKey.getAlgorithm())) {
			incomingTransform = "RSA/ECB/PKCS1Padding";
		} else {
			incomingTransform = privateKey.getAlgorithm() + "CBC/PKCS5Padding";
		}
		byte[] decrypted = decrypt(header, message, offset, length, 
				new EncryptionDetails(privateKey, incomingTransform, 1, -1, MILLISECONDS), this::verifySignature);
		try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(decrypted))){
			int keyGenerationCounter = dis.readInt();
			long remainingTimeMillis = dis.readLong();
			
			String algo = dis.readUTF();
			String transform = dis.readUTF();
			byte[] encoded = new byte[dis.readUnsignedShort()];
			dis.readFully(encoded);
			SecretKey key = new SecretKeySpec(encoded, algo); 
			try {
				key = SecretKeyFactory.getInstance(algo).translateKey(key);
			} catch (NoSuchAlgorithmException nsae) {
			}
			return new EncryptionDetails(key, transform, keyGenerationCounter, remainingTimeMillis, MILLISECONDS);
		} catch (IOException | InvalidKeyException e) {
			logger.error("An error occurred when decoding a key exchange message", e);
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public DataOutputStream encryptingStream(OutputStream os) {
		EncryptionDetails encryptionDetails = getLocalEncryptionDetails();
		if(encryptionDetails == null) {
			logger.error("Unable to create an encrypted stream because no encryption is configured");
			throw new IllegalStateException("Unable to create an encrypted stream because no encryption is configured");
		}
		Cipher cipher = getCipher(encryptionDetails.getTransform());
		Mac mac = getMac();
		try {
			mac.init(encryptionDetails.getKey());
			os = new VerifiableOutputStream(os, mac);
			cipher.init(Cipher.ENCRYPT_MODE, encryptionDetails.getKey(), random);
			final byte[] iv = cipher.getIV();
			
			if(iv != null) {
				os.write(1);
				int length = iv.length;
				os.write(length >> 8);
				os.write(length);
				os.write(iv);
			} else {
				os.write(2);
			}
			return new EncryptedStream(os, cipher, mac);
		} catch (Exception e) {
			releaseCipher(cipher);
			releaseMac(mac);
			logger.error("Unable to generate an encrypted stream", e);
			throw new RuntimeException(e);
		} 
	}

	@Override
	public void forceFlush(OutputStream os) {
		if(os instanceof EncryptedStream) {
			try {
				((EncryptedStream)os).forceFlush();
			} catch (IOException e) {
				logger.error("Unable to force a flush of the encrypted stream", e);
				throw new RuntimeException(e);
			}
		} else {
			try {
				os.flush();
			} catch (IOException e) {
				logger.error("Unable to flush the non-encrypted stream", e);
				throw new RuntimeException(e);
			}
		}
	}

	/* (non-Javadoc)
	 * @see com.paremus.net.encode.impl.impl.EncodingScheme#validateAndDecode(byte[], int, int, java.security.cert.Certificate)
	 */
	@Override
	public DataInput validateAndDecode(byte[] header, byte[] message, int offset, int length, EncryptionDetails remoteEncryption) {
		
		if(message[offset] != 1) {
			throw new IllegalArgumentException("Unknown encoding version " + message[offset]);
		}
		offset ++;
		length --;
		
		EncodingType es = getEncoding(message, offset);
		offset ++;
		length --;
		
		EncryptionDetails encryptionToUse = getEncryptionToUse(remoteEncryption);
		if(es == ENCRYPTED) {
			if(encryptionToUse == null) {
				logger.warn("An encrypted message was received, but this node is configured not to use encryption");
				throw new IllegalArgumentException("An encrypted message was received, but this node is configured not to use encryption");
			}
			message = decrypt(header, message, offset, length, encryptionToUse, (a, b, c, d, e) -> verifyMac(encryptionToUse, a, b, c, d, e));
			if(message[0] != 1) {
				throw new IllegalArgumentException("The nested message had the wrong version!");
			}
			offset = 1;
			length = message.length - 1;
			es = getEncoding(message, offset);
			offset ++;
			length --;
		} else if(encryptionToUse != null) {
			logger.warn("An unencrypted message was received, but this node is configured to use encryption");
			throw new IllegalArgumentException("The message was not encrypted, and was expected to be!");
		}
		
		InputStream raw = new ByteArrayInputStream(message, offset, length);
		
		DataInput input;
		switch(es) {
			case COMPRESSED:
				try {
					raw = new GZIPInputStream(raw);
				} catch (IOException e) {
					throw new IllegalArgumentException("Unable to decompress the message", e);
				}
			case PLAIN:
				input = new DataInputStream(raw);
				break;
			default:
				throw new IllegalArgumentException("The encoding " +es.toString() + " is not known to this version of the encoder");
		}
		return input;
	}

	private EncodingType getEncoding(byte[] message, int offset) {
		EncodingType es;
		try {
			es = EncodingType.values()[message[offset]];
		} catch (ArrayIndexOutOfBoundsException aioobe) {
			throw new IllegalArgumentException("The encoding " + message[offset] + " is not known to this version of the encoder");
		}
		return es;
	}
	
	private interface Verifier {
		boolean verify(byte[] sig, byte[] header, byte[] message, int offset, int length);
	}
	
	private byte[] decrypt(byte[] header, byte[] message, int offset, int length, EncryptionDetails remoteEncryption, Verifier verifier) {
		try {
			int macSize = ((0xFF & message[offset]) << 8) + (0xFF & message[offset + 1]);
			offset += (macSize + 2);
			length -= (macSize + 2);
			if(!verifier.verify(Arrays.copyOfRange(message, offset - macSize, offset), header, message, offset, length)) {
				//TODO log this
				throw new InvalidEncodingException("The message has been corrupted in transit - the mac does not match");
			}
			Cipher cipher = getCipher(remoteEncryption.getTransform());
			try {
				int ivLength = message[offset];
				if(ivLength >= 0) {
					cipher.init(Cipher.DECRYPT_MODE, remoteEncryption.getKey(), new IvParameterSpec(message, offset + 1, ivLength));
				} else {
					cipher.init(Cipher.DECRYPT_MODE, remoteEncryption.getKey());
					ivLength = 0;
				}
				return cipher.doFinal(message, offset + 1 + ivLength, length - 1 - ivLength);
			} finally {
				releaseCipher(cipher);
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			//TODO log
			throw new RuntimeException(e);
		} 
	}

	/* (non-Javadoc)
	 * @see com.paremus.net.encode.impl.impl.EncodingScheme#decryptingStream(java.io.InputStream, java.security.cert.Certificate)
	 */
	@Override
	public DataInputStream decryptingStream(InputStream is, EncryptionDetails remoteEncryption) {
		EncryptionDetails encryptionToUse = getEncryptionToUse(remoteEncryption);
		if(encryptionToUse == null) throw new MissingEncryptionDetailsException("No credentials available to decrypt the stream");
		Mac mac = getMac();
		try {
			mac.init(encryptionToUse.getKey());
			is = new VerifyingInputStream(is, mac);
			final IvParameterSpec ivParamSpec;
			int type = is.read();
			if(type == 1) {
				byte[] bytes = new byte[((is.read() & 0xFF) << 8) + (is.read() & 0xFF)];
				int i =0;
				while(i < bytes.length) {
					int count = is.read(bytes, i, bytes.length - i);
					if(count < 0) throw new EOFException("Unexpected end of stream");
					i += count;
				}
				ivParamSpec = new IvParameterSpec(bytes);
			} else if (type == 2) {
				ivParamSpec = null;
			} else if (type == -1) {
				throw new EOFException("Unexpected end of stream");
			} else {
				throw new IllegalArgumentException("Unknown type " + type);
			}
			
			Cipher cipher = getCipher(encryptionToUse.getTransform());
			if(ivParamSpec != null) {
				cipher.init(Cipher.DECRYPT_MODE, encryptionToUse.getKey(), ivParamSpec);
			} else {
				cipher.init(Cipher.DECRYPT_MODE, encryptionToUse.getKey());
			}
			return new DecryptedStream(is, cipher, mac);
		} catch (Exception e) {
			releaseMac(mac);
			//TODO log
			throw new RuntimeException(e);
		} 
	}

	@Override
	public void skipForcedFlush(InputStream is) {
		if(is instanceof DecryptedStream) {
			try {
				((DecryptedStream)is).skipForcedFlush();
			} catch (IOException e) {
				logger.error("Unable to skip the padding from a forced flush of the encrypted stream", e);
				throw new RuntimeException(e);
			}
		}
	}

	private EncryptionDetails getEncryptionToUse(EncryptionDetails remoteEncryption) {
		if(remoteEncryption != null) {
			if(remoteEncryption.hasExpired()) {
				throw new ExpiredEncryptionDetailsException("The remotely supplied encryption details have expired", remoteEncryption);
			} else {
				return remoteEncryption;
			}
		} else if(requiresCertificates()) {
			throw new MissingEncryptionDetailsException("No encryption details were provided for the remote node, but key exchange should be occurring.");
		} else {
			return getLocalEncryptionDetails();
		}
	}

	private EncryptionDetails getLocalEncryptionDetails() {
		EncryptionDetails ed = encryption.get();
		if(ed == null) return null;
		if(ed.hasExpired()) {
			synchronized (encryption) {
				ed = encryption.get();
				if(ed.hasExpired()) {
					regenerateKey();
					ed = encryption.get();
				}
			}
		}
		return ed;
	}

	private final ConcurrentMap<String, LinkedList<Signature>> signatures = new ConcurrentHashMap<>();
	
	private Signature getSignature() {
		if(certificateInfo != null) {
			return getSignature(certificateInfo.getSigningKey().getAlgorithm());
		} else {
			throw new IllegalStateException("There are no signing credentials");
		}
	}

	private Signature getSignature(String algorithm) {
		if(certificateInfo != null) {
			if("EC".equals(algorithm)) algorithm = "ECDSA";
			LinkedList<Signature> list = signatures.computeIfAbsent(algorithm, k -> new LinkedList<>());
			Signature s;
			synchronized (list) {
				s = list.pollLast();
			}
			if(s == null) {
				try {
					s = Signature.getInstance("SHA256with" + algorithm);
				} catch (Exception e) {
					logger.error("The Signature implementation " + "SHA256with" + algorithm + " is not supported.", e);
					throw new RuntimeException(e);
				} 
			}
			return s;
		} else {
			throw new IllegalStateException("There is no encryption configured");
		}
	}

	private void releaseSignature(Signature s) {
		LinkedList<Signature> list = signatures.get(s.getAlgorithm().substring(10));
		synchronized (list) {
			list.push(s);
		}
	}
	
	private final ConcurrentMap<String, LinkedList<Cipher>> ciphers = new ConcurrentHashMap<>();
	
	private Cipher getCipher(String transform) {
		if(encryption.get() != null) {
			LinkedList<Cipher> list = ciphers.computeIfAbsent(transform, k -> new LinkedList<>());
			Cipher c;
			synchronized (list) {
				c = list.pollLast();
			}
			if(c == null) {
				try {
					c = Cipher.getInstance(transform);
				} catch (Exception e) {
					logger.error("The Cipher implementation " + transform + " is not supported.", e);
					throw new RuntimeException(e);
				} 
			}
			return c;
		} else {
			throw new IllegalStateException("There is no encryption configured");
		}
	}

	private void releaseCipher(Cipher c) {
		
		LinkedList<Cipher> list = ciphers.get(c.getAlgorithm());
		synchronized (list) {
			list.push(c);
		}
	}

	private final LinkedList<Mac> macs = new LinkedList<>();
	
	private Mac getMac() {
		if(encryption.get() != null) {
			Mac mac;
			synchronized (macs) {
				mac = macs.pollLast();
			}
			if(mac == null) {
				try {
					mac = Mac.getInstance(macAlgorithm);
				} catch (Exception e) {
					logger.error("All JVM implementations are required to support message authentication codes.", e);
					throw new RuntimeException(e);
				} 
			}
			return mac;
		} else {
			throw new IllegalStateException("There is no encryption configured");
		}
	}
	
	private void releaseMac(Mac c) {
		c.reset();
		synchronized (macs) {
			macs.push(c);
		}
	}

	@Override
	public SocketFactory getSocketFactory() {
		return socketFactory;
	}

	@Override
	public ServerSocketFactory getServerSocketFactory() {
		return serverSocketFactory;
	}

	@Override
	public byte[] sign(byte[] bytes) {
		Signature sig = getSignature();
		byte[] sigBytes;
		try {
			sig.initSign(certificateInfo.getSigningKey(), random);
			sig.update(bytes);
			sigBytes = sig.sign();
		} catch (InvalidKeyException | SignatureException e) {
			throw new IllegalArgumentException(e);
		} finally {
			releaseSignature(sig);
		}
		return sigBytes;
	}

	@Override
	public boolean verifySignature(byte[] bytes, byte[] signature, Certificate signingCertificate) {
		PublicKey publicKey = signingCertificate.getPublicKey();
		Signature sig = getSignature(publicKey.getAlgorithm());
		try {
			sig.initVerify(publicKey);
			sig.update(bytes);
			return sig.verify(signature);
		} catch (InvalidKeyException | SignatureException e) {
			throw new IllegalArgumentException(e);
		} finally {
			releaseSignature(sig);
		}
	}
}
