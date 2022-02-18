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
package com.paremus.cert.domain;

import static com.paremus.cert.domain.CertificateGenerator.SignatureAlgorithm.SHA384WITHRSA;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.StringReader;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyPairGeneratorSpi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paremus.cert.domain.KeyStoreManager.SignerInfo;

public class CertificateGeneratorTest {

    private KeyPair kp;

    private KeyPair kp2;

    @BeforeEach
    public void setup() {
        KeyPairGenerator kpg = new KeyPairGeneratorSpi();
        kpg.initialize(2048, new SecureRandom());
        kp = kpg.generateKeyPair();
        kp2 = kpg.generateKeyPair();
    }

    @Test
    public void testCSR() throws Exception {

        CertificateGenerator certificateGenerator = new CertificateGenerator(new BouncyCastleProvider(), new SecureRandom());
        String certSigningRequest = certificateGenerator.generateCertificateSigningRequest(kp, "TEST", SHA384WITHRSA);

        PKCS10CertificationRequest req;
        try(PEMParser parser = new PEMParser(new StringReader(certSigningRequest))) {
            req = (PKCS10CertificationRequest) parser.readObject();
        }

        JcaContentVerifierProviderBuilder jcvpb = new JcaContentVerifierProviderBuilder();

        assertArrayEquals(kp.getPublic().getEncoded(), req.getSubjectPublicKeyInfo().getEncoded());
        assertTrue(req.isSignatureValid(jcvpb.build(kp.getPublic())));

        assertEquals("CN=TEST", req.getSubject().toString());
    }

    @Test
    public void testSelfSigned() throws Exception {
        CertificateGenerator certificateGenerator = new CertificateGenerator(new BouncyCastleProvider(), new SecureRandom());
        Certificate cert = certificateGenerator.generateRootCertificate(kp, "TEST", Duration.ofDays(2));

        cert.verify(kp.getPublic());

        assertArrayEquals(kp.getPublic().getEncoded(), cert.getPublicKey().getEncoded());

        assertTrue(cert instanceof X509Certificate);

        X509Certificate x509 = (X509Certificate) cert;

        assertEquals("CN=TEST", x509.getIssuerX500Principal().toString());
        assertEquals("CN=TEST", x509.getSubjectX500Principal().toString());

        x509.checkValidity(new Date(Instant.now().plus(Duration.ofDays(2)).minus(Duration.ofMinutes(1)).toEpochMilli()));

        try {
            x509.checkValidity(new Date(Instant.now().minus(Duration.ofMinutes(1)).toEpochMilli()));
            fail("Should not be valid in the past");
        } catch (CertificateNotYetValidException cnyve) {

        }
    }


    @Test
    public void testSelfSignedCanSignCSR() throws Exception {
        CertificateGenerator certificateGenerator = new CertificateGenerator(new BouncyCastleProvider(), new SecureRandom());
        Certificate cert = certificateGenerator.generateRootCertificate(kp, "TEST", Duration.ofDays(2));

        cert.verify(kp.getPublic());

        String certSigningRequest = certificateGenerator.generateCertificateSigningRequest(kp2, "TEST2", SHA384WITHRSA);

        String signed = certificateGenerator.signCertificate(new SignerInfo(kp.getPrivate(),
                new Certificate[] {cert}), certSigningRequest);

        X509CertificateHolder cert2;
        X509CertificateHolder chain;
        try(PEMParser parser = new PEMParser(new StringReader(signed))) {
            cert2 = (X509CertificateHolder) parser.readObject();
            chain = (X509CertificateHolder) parser.readObject();
            assertNull(parser.readObject());
        }

        assertArrayEquals(kp.getPublic().getEncoded(), cert.getPublicKey().getEncoded());
        assertArrayEquals(kp.getPublic().getEncoded(), chain.getSubjectPublicKeyInfo().getEncoded());
        assertArrayEquals(kp2.getPublic().getEncoded(), cert2.getSubjectPublicKeyInfo().getEncoded());

        X509Certificate x509 = new JcaX509CertificateConverter().getCertificate(cert2);

        assertEquals("CN=TEST", x509.getIssuerX500Principal().toString());
        assertEquals("CN=TEST2", x509.getSubjectX500Principal().toString());

        x509.verify(kp.getPublic());
        x509.checkValidity(new Date(Instant.now().plus(Duration.ofDays(2)).minus(Duration.ofMinutes(1)).toEpochMilli()));

        try {
            x509.checkValidity(new Date(Instant.now().minus(Duration.ofMinutes(1)).toEpochMilli()));
            fail("Should not be valid in the past");
        } catch (CertificateNotYetValidException cnyve) {

        }
    }
}
