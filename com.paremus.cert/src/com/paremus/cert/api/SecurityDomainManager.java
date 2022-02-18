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
package com.paremus.cert.api;

import java.net.URL;
import java.util.Collection;

/**
 * The {@link SecurityDomainManager} service is used to manage key pairs,
 * certificate keystores and trust stores, as well as to create and sign
 * certificates.
 *
 * <p>
 * Due to limitations in the Java PKCS12 support, all store names must be
 * lower case
 *
 * <p>
 * Note that access to key stores, trust stores, and passwords for these
 * resources is via configuration admin, using the configuration properties
 * described in {@link SecurityDomainConfiguration}
 */
public interface SecurityDomainManager {

    /**
     * List the named key pairs known to this manager.
     * Note that each key pair may correspond to one or
     * more certificates
     * @return A collection of names
     */
    Collection<String> listKeyPairs();

    /**
     * Get the information, including the public key, for a named key pair
     * @param keyPairName the name of the key pair
     * @return A {@link KeyPairInfo} representing this key pair
     */
    KeyPairInfo getKeyPairInfo(String keyPairName);

    /**
     * Create a new key pair managed by this manager
     * @param keyPairName the name of the key pair to create
     */
    void createKeyPair(String keyPairName);

    /**
     * List the named certificate keystores known to this manager.
     * Each keystore contains one certificate and chain
     * @return The list of named keystores
     */
    Collection<String> listKeyStores();

    /**
     * Get the information, including the public key, for a named
     * certificate. The name corresponds to the name of the keystore
     * @param keystoreName the name of the keystore to query
     * @return A DTO representing the certificate in the keystore
     */
    CertificateInfo getCertificateInfo(String keystoreName);

    /**
     * List the named trust stores known to this manager
     * @return The list of named truststores
     */
    Collection<String> listTrustStores();

    /**
     * List the trusted certifcates for a named truststore
     * @param truststoreName the name of the truststore to view
     * @return The certificates known to the named truststore
     */
    Collection<CertificateInfo> getTrustedCertificateInfo(String truststoreName);

    /**
     * Create a certificate signing request for a certificate with the supplied
     * name using the named key pair
     * @param certificateName the name of the certificate to be signed
     * @param keyPairName the name of the key pair that the certificate should represent
     * @return A PEM encoded PKCS10 Certificate Signing Request
     */
    String createNewCertificateSigningRequest(String certificateName, String keyPairName);

    /**
     * Issue a certificate signing request to a remote URL using the supplied trust chain.
     *
     * <p>
     * The request is a POST operation which sends a <code>Paremus-One-Time-Token</code>
     * header containing the one time passcode and the encoded certificates as the body
     * of the request.
     *
     * <p>
     * The request and response content type are both <code>text/plain;charset=utf-8</code>
     *
     * @param url the HTTPS url to which the signing request should be sent
     * @param signingRequest the signing request being made of the certificate to be signed
     * @param oneTimePasscode the one time passcode for this request
     * @param encodedCertificates the DER encoded certificate trust chain to accept for this connection
     * @return A PEM encoded Certificate and trust chain suitable for importing
     */
    String issueCertificateSigningRequest(URL url, String signingRequest,
            String oneTimePasscode, String encodedCertificates);

    /**
     * Create a named trust store which contains the supplied certificates
     * @param trustStoreName the name of the truststore to create
     * @param encodedCertificates a PEM encoded certificate list, with each certificate
     * separated by new lines
     */
    void createTrustStore(String trustStoreName, String encodedCertificates);

    /**
     * Delete the named truststore
     * @param trustStoreName the truststore to delete
     */
    void deleteTrustStore(String trustStoreName);

    /**
     * Delete the named certificate from the named trust store
     * @param trustStore the trust store to delete the certificate from
     * @param certificateAlias the certificate to delete
     */
    void removeTrustedCertificate(String trustStore, String certificateAlias);

    /**
     * Add one or more certificates to the named trust store
     * @param trustStoreName the trust store to add the certificate to
     * @param encodedCertificates a PEM encoded certificate list, with each certificate
     * separated by new lines
     */
    void addTrustedCertificates(String trustStoreName, String encodedCertificates);

    /**
     * Create a keystore for the supplied certificate
     * @param keystoreName The name of the keystore
     * @param keyPairName The name of the key pair corresponding to the certificate
     * @param encodedCertificateAndChain The PEM encoded certificate and trust chain
     */
    void createKeyStore(String keystoreName, String keyPairName, String encodedCertificateAndChain);

    /**
     * Update an existing key store with a new certificate
     * @param keystoreName The name of the keystore
     * @param keyPairName The name of the key pair corresponding to the certificate
     * @param encodedCertificateAndChain The PEM encoded certificate and trust chain
     */
    void updateKeyStore(String keystoreName, String keyPairName, String encodedCertificateAndChain);

    /**
     * Delete the named keystore
     * @param keystoreName the keystore
     */
    void deleteKeyStore(String keystoreName);

    /**
     * Sign a certificate signing request with the named certificate and chain
     * @param keystoreName the certificate chain with which to sign this certificate
     * @param signingRequest The PEM encoded PCKS10 Certificate Signing Request
     * @return The PEM encoded signed certificate, and the PEM encoded chain of signer certificates
     */
    String signCertificateSigningRequest(String keystoreName, String signingRequest);

    /**
     * Retrieve the PEM encoded certificate chain associated with a particular key store
     * @param keystoreName the name of the keytore
     * @return A PEM encoded String containing the certificate trust chain
     */
    String getCertificateChain(String keystoreName);

    /**
     * Creates a new key pair and self-signed certificate suitable for use as a
     * certificate authority (i.e. suitable for signing certificate signing requests)
     * @param certificateName the name of the CA certificate
     */
    void createCertificateAuthority(String certificateName);
}
