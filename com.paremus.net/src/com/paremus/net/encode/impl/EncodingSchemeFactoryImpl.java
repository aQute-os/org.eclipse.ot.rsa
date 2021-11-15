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

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.Key;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import org.osgi.service.cm.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paremus.net.encode.EncodingScheme;
import com.paremus.net.encode.EncodingSchemeFactory;
import com.paremus.net.encode.EncryptionDetails;

public class EncodingSchemeFactoryImpl implements EncodingSchemeFactory {
	
	private static final Logger logger = LoggerFactory.getLogger(EncodingSchemeFactoryImpl.class);
	
	private final FibreCertificateInfo certificateInfo;
	
	private final EncryptionDetails encryption;
	
	private final Config config;
	
	private final String algorithm;
	private final String transform;
	private final int keySize;

	private final SocketFactory socketFactory;
	private final ServerSocketFactory serverSocketFactory;
	
	public EncodingSchemeFactoryImpl(Config config) throws ConfigurationException {
		
		this.config = config;
		this.certificateInfo = getCertificateInfo();
		this.encryption = getEncryptionDetails();
		
		if(this.encryption == null && certificateInfo != null) {
			algorithm = config.encryption_algorithm();
			transform = config.encryption_transform();
			keySize = config.encryption_key_length();
			testGenerateKeyAndTransform();
		} else {
			algorithm = null;
			transform = null;
			keySize = -1;
		}
		
		if(certificateInfo != null) {
			SSLContext sslContext = createSSLContext(config);
			SSLParameters sslConfig = getSSLConfiguration(sslContext);
			
			socketFactory = new SecureSocketFactory(sslContext.getSocketFactory(), sslConfig);
			serverSocketFactory = new SecureServerSocketFactory(sslContext.getServerSocketFactory(), sslConfig);
		} else {
			socketFactory = SocketFactory.getDefault();
			serverSocketFactory = ServerSocketFactory.getDefault();
		}
	}

	private void testGenerateKeyAndTransform() throws ConfigurationException {
		try {
			generateKey(new SecureRandom());
		} catch (NoSuchAlgorithmException nsae) {
			logger.error("Unable to generate an encryption key because the encryption algorithm was unknown");
			throw new ConfigurationException("encryption.algorithm", "The encryption algorithm " + algorithm + " is not supported by this JVM");
		} catch (RuntimeException re) {
			logger.error("Unable to generate an encryption key because the generator threw an Exception", re);
			throw new ConfigurationException("encryption.key.length", "Unable to generate an encryption key", re);
		}
		try {
			Cipher.getInstance(algorithm + "/" + transform);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
			logger.error("Unable to build a cipher because the encryption transform was unknown", e);
			throw new ConfigurationException("encryption.transform", "The encryption algorithm " + algorithm + " is not supported by this JVM");
		}
	}

	private FibreCertificateInfo getCertificateInfo() throws ConfigurationException {
		FibreCertificateInfo signingPair;
		String storeFile = config.signature_keystore();
		if(storeFile != null) {
			Certificate cert;
			Key key;
			KeyManagerFactory kmf;
			try {
				String algo = config.key_manager_algorithm();
				kmf = KeyManagerFactory.getInstance(algo == null ?
						KeyManagerFactory.getDefaultAlgorithm() : algo);
				
				KeyStore keystore = KeyStore.getInstance(config.signature_keystore_type());
				String keystorePw = config._signature_keystore_password();
				char[] keystorePwChars = keystorePw == null ? new char[0] : keystorePw.toCharArray();
				keystore.load(new FileInputStream(storeFile), keystorePwChars);
				
				String keyPw = config._signature_key_password();
				
				kmf.init(keystore, keyPw == null ? keystorePwChars : keyPw.toCharArray());
				
				key = keystore.getKey(config.signature_key_alias(), keyPw == null ? keystorePwChars : keyPw.toCharArray());
				if(key instanceof PrivateKey) {
					String certAlias = config.signature_cert_alias() == null ? config.signature_key_alias() : config.signature_cert_alias();
					cert = keystore.getCertificate(certAlias);
					
					if(cert == null) {
						logger.error("The signature keystore did not contain a certificate with alias {}", certAlias);
						throw new ConfigurationException("signature.cert.alias", "There is no certificate with alias " + certAlias);
					}
				} else {
					logger.error("The signature keystore did not contain a private key with alias {}", config.signature_key_alias());
					throw new ConfigurationException("signature.key.alias", "There is no private key with alias " + config.signature_cert_alias());
				}
			} catch (UnrecoverableKeyException uke) {
				logger.error("The supplied password for the signing key was incorrect", uke);
				throw new ConfigurationException(".signature.key.password", "Unable retrieve the signing key due to an incorrect password.", uke);
			} catch (KeyStoreException | IOException | CertificateException | NoSuchAlgorithmException e) {
				logger.error("The signing keystore could not be loaded", e);
				throw new ConfigurationException("signature.keystore", "Unable to load the signing keystore " + config.signature_keystore(), e);
			}
			TrustManagerFactory tmf;
			try {
				String algo = config.trust_manager_algorithm();
				tmf = TrustManagerFactory.getInstance(algo == null ?
						TrustManagerFactory.getDefaultAlgorithm() : algo);
				
				KeyStore truststore = KeyStore.getInstance(config.signature_truststore_type());
				String truststorePw = config._signature_truststore_password();
				char[] truststorePwChars = truststorePw == null ? new char[0] : truststorePw.toCharArray();
				truststore.load(new FileInputStream(config.signature_truststore()), truststorePwChars);
				
				tmf.init(truststore);
			} catch (KeyStoreException | IOException | CertificateException | NoSuchAlgorithmException e) {
				logger.error("The truststore could not be loaded", e);
				throw new ConfigurationException("signature.truststore", "Unable to load the truststore " + config.signature_truststore(), e);
			}
			
			signingPair = new FibreCertificateInfo(cert, (PrivateKey) key, kmf, tmf);
		} else {
			signingPair = null;
		}
		
		return signingPair;
	}


	private EncryptionDetails getEncryptionDetails()
			throws ConfigurationException {
		EncryptionDetails ed;
			final String encodedKey = config._encryption_key();
			if(encodedKey != null) {	
				final String algo = config.encryption_algorithm();
				if(certificateInfo != null) {
					logger.info("A raw {} key was supplied and will be used for encryption. No certificate was provided, therefore this key cannot be securely exchanged and must be configured on all other nodes.", algo);
				} else {
					logger.info("A raw {} key was supplied and will be used for encryption. This will be securely exchanged with other nodes.", algo);
				}
				ed = new EncryptionDetails(decodeKey(encodedKey, algo), algo + "/" + config.encryption_transform(), 1, -1, MILLISECONDS);
			} else {
				String storeFile = config.encryption_keystore();
				if(storeFile != null) {
					Key key = readEncryptionKeyFromKeystore(config, storeFile);
					
					if(certificateInfo != null) {
						logger.info("A {} key was supplied in a keystore and will be used for encryption. No certificate was provided, therefore this key cannot be securely exchanged and must be configured on all other nodes.", key.getAlgorithm());
					} else {
						logger.info("A {} key was supplied in a keystore and will be used for encryption. This will be securely exchanged with other nodes.", key.getAlgorithm());
					}
					
					ed = new EncryptionDetails(key, key.getAlgorithm() + "/" + config.encryption_transform(), 1, -1, MILLISECONDS);
				} else {
					logger.info("No certificate or encryption key was provided. Communications will be insecure.");
					ed = null;
				}
			}
		return ed;
	}

	private Key decodeKey(String encodedKey, String algo) throws ConfigurationException {
		if((encodedKey.length() & 1) != 0) {
			throw new ConfigurationException(".encryption.key", "Must represent a set of bytes in hex, so the length of the string must be a multiple of two");
		}
		
		char[] hex = encodedKey.toLowerCase().toCharArray();
		byte[] bytes = new byte[hex.length / 2];
		for(int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) ((toNibble(hex[2*i]) << 4) + toNibble(hex[2*i + 1]));
		}
		
		return new SecretKeySpec(bytes, algo);
	}


	private int toNibble(char c) {
		switch(c) {
			case '0': return 0;
			case '1': return 1;
			case '2': return 2;
			case '3': return 3;
			case '4': return 4;
			case '5': return 5;
			case '6': return 6;
			case '7': return 7;
			case '8': return 8;
			case '9': return 9;
			case 'a': return 10;
			case 'b': return 11;
			case 'c': return 12;
			case 'd': return 13;
			case 'e': return 14;
			case 'f': return 15;
			default: throw new IllegalArgumentException("" + c + " is not a valid hex character");
		}
	}

	private Key readEncryptionKeyFromKeystore(Config config, String storeFile)
			throws ConfigurationException {
		try {
			KeyStore keystore = KeyStore.getInstance(config.encryption_keystore_type());
			String keystorePw = config._encryption_keystore_password();
			char[] keystorePwChars = keystorePw == null ? new char[0] : keystorePw.toCharArray();
			keystore.load(new FileInputStream(storeFile), 
					keystorePwChars);
			String keyPw = config._encryption_key_password();
			Key key = keystore.getKey(config.encryption_key_alias(), 
					keyPw == null ? keystorePwChars : keyPw.toCharArray());
			if(key instanceof PrivateKey) {
				throw new ConfigurationException("encryption.key.alias", "The encryption key was not a symmetric cipher " + config.encryption_key_alias());
			} else if(key instanceof SecretKey) {
				return key;
			} else {
				//TODO Warn
				throw new ConfigurationException("encryption.key.alias", "There is no suitable key with alias " + config.encryption_key_alias());
			}
		} catch (UnrecoverableKeyException uke) {
			logger.error("The supplied password for the encryption key was incorrect", uke);
			throw new ConfigurationException(".encryption.key.password", "Unable retrieve the encryption key due to an incorrect password.", uke);
		} catch (KeyStoreException | IOException | CertificateException | NoSuchAlgorithmException e) {
			logger.error("The encryption keystore could not be loaded", e);
			throw new ConfigurationException("signature.keystore", "Unable to load the encryption keystore " + config.encryption_keystore(), e);
		}
	}
	
	private SSLContext createSSLContext(Config config) throws ConfigurationException {
		SSLContext sslContext = config.socket_protocols().stream()
				.map(p -> {
					try {
						return SSLContext.getInstance(p);
					} catch (NoSuchAlgorithmException nsae) {
						logger.warn("{} is not a valid protocol", p);
						return null;
					}
				}).filter(s -> s != null)
				.findFirst().orElseThrow(
						() -> new ConfigurationException("socket.protocols", "No valid protocols for creating an SSLContext"));
		try {
			sslContext.init(certificateInfo.getKeyManagerFactory().getKeyManagers(), 
					certificateInfo.getTrustManagerFactory().getTrustManagers(), null);
		} catch (KeyManagementException e) {
			throw new ConfigurationException("signature.keystore", "Unable to initialize an SSLContext with the supplied keystore", e);
		}
		return sslContext;
	}

	private SSLParameters getSSLConfiguration(SSLContext sslContext) throws ConfigurationException {
		SSLParameters supportedSSLParameters = sslContext.getSupportedSSLParameters();
		
		List<String> supportedProtocols = asList(supportedSSLParameters.getProtocols());
		String[] acceptableProtocols = config.socket_protocols().stream()
			.filter(supportedProtocols::contains)
			.collect(Collectors.toSet()).toArray(new String[0]);
		
		if(acceptableProtocols.length == 0) {
			throw new ConfigurationException("socket.protocols", "No acceptable protocols - supported were " + supportedProtocols);
		}
	
		List<String> supportedCiphers = asList(supportedSSLParameters.getCipherSuites());
		String[] acceptableCiphers = config.socket_ciphers().stream()
				.filter(supportedCiphers::contains)
				.collect(Collectors.toSet()).toArray(new String[0]);
		
		if(acceptableProtocols.length == 0) {
			throw new ConfigurationException("socket.ciphers", "No acceptable ciphers - supported were " + supportedCiphers);
		}
		
		SSLParameters defaultConfig = sslContext.getDefaultSSLParameters();
		
		SSLParameters sslConfig = new SSLParameters(acceptableCiphers, acceptableProtocols);
		sslConfig.setEndpointIdentificationAlgorithm(defaultConfig.getEndpointIdentificationAlgorithm());
		sslConfig.setAlgorithmConstraints(defaultConfig.getAlgorithmConstraints());
		boolean needCA = true;
		boolean wantCA = true;
		switch(config.socket_client_auth()) {
		case NONE:
			wantCA = false;
		case WANT:
			needCA = false;
		case NEED:
			break;
		default:
			throw new ConfigurationException("socket.client.auth", "Unsupported client auth type: " + config.socket_client_auth());
			
		}
		sslConfig.setNeedClientAuth(needCA);
		sslConfig.setWantClientAuth(wantCA);
		sslConfig.setSNIMatchers(defaultConfig.getSNIMatchers());
		sslConfig.setServerNames(defaultConfig.getServerNames());
		return sslConfig;
	}

	@Override
	public EncodingScheme createEncodingScheme() {
		return createEncodingScheme(() -> {});
	}

	public EncodingScheme createEncodingScheme(Runnable onKeyRegeneration) {
		SecureRandom random = new SecureRandom();
		EncryptionDetails details = encryption;
		Supplier<EncryptionDetails> regenerator = null;
		
		if(details == null) {
			if(certificateInfo != null) {
				
				long nextExpiry = getNextExpiryIntervalMillis();
				
				AtomicInteger keyGenerationCounter = new AtomicInteger();
				regenerator = () -> new EncryptionDetails(silentGenerateKey(random), algorithm + "/" + transform, 
						keyGenerationCounter.incrementAndGet(), nextExpiry, MILLISECONDS);
				details = regenerator.get();
				logger.info("A {} encryption key was auto-generated for this node, and will be securely exchanged using the configured certificate.", algorithm);
			} else {
				logger.info("No certificate or encryption key was provided. Communications will be insecure.");
			}
		}
		
		return new EncodingSchemeImpl(certificateInfo, details, random, regenerator, onKeyRegeneration, 
				config.mac_algorithm(), socketFactory, serverSocketFactory);
	}

	private long getNextExpiryIntervalMillis() {
		//TODO support cron syntax alternative
		long expiry = config.encryption_key_expiry_unit().toMillis(config.encryption_key_expiry());
		return expiry <= 0 ? -1 : expiry;
	}

	private Key silentGenerateKey(SecureRandom random) {
		try {
			return generateKey(random);
		} catch (NoSuchAlgorithmException nsae) {
			throw new RuntimeException("Unable to generate a key", nsae);
		}
 	}
	
	private Key generateKey(SecureRandom random) throws NoSuchAlgorithmException {
		KeyGenerator keyGen = KeyGenerator.getInstance(algorithm);
		keyGen.init(keySize, random);
		
		return keyGen.generateKey();
	}

	@Override
	public TrustManagerFactory getSSLTrustManagerFactory() {
		return certificateInfo != null ? certificateInfo.getTrustManagerFactory() : null;
	}

	@Override
	public KeyManagerFactory getSSLKeyManagerFactory() {
		return certificateInfo != null ? certificateInfo.getKeyManagerFactory() : null;
	}

	@Override
	public ServerSocketFactory getServerSocketFactory() {
		return serverSocketFactory;
	}

	@Override
	public SocketFactory getSocketFactory() {
		return socketFactory;
	}
}
