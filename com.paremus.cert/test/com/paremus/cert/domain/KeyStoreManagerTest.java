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

import static java.time.Duration.ofHours;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Map;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.paremus.cert.api.CertificateInfo;

public class KeyStoreManagerTest {

    @TempDir
    public File tempFolder;

    private final BouncyCastleProvider provider = new BouncyCastleProvider();
    private final SecureRandom secureRandom = new SecureRandom();

    private KeyPairManager keyPairManager;
    private CertificateGenerator certificateGenerator;


    @BeforeEach
    public void setUp() throws IOException {
        keyPairManager = new KeyPairManager(tempFolder.toPath(), provider, secureRandom);
        certificateGenerator = new CertificateGenerator(provider, secureRandom);
    }

    @org.junit.jupiter.api.Test
    public void testCreateKeyStore() throws IOException {

        KeyStoreManager ksm = new KeyStoreManager(tempFolder.toPath(), provider, secureRandom);

        KeyPair keyPair = keyPairManager.newKeyPair("TEST");

        Certificate certificate = certificateGenerator.generateRootCertificate(keyPair, "TEST_CERT", ofHours(1));

        assertTrue(ksm.listKeyStores().isEmpty());

        ksm.createKeyStore("TEST_STORE", keyPair, new Certificate[] {certificate});

        Map<String, CertificateInfo> stores = ksm.listKeyStores();
        assertEquals(1, stores.size());

        CertificateInfo info = stores.get("TEST_STORE");
        assertNotNull(info);

        assertEquals("test_store", info.alias);
        assertEquals("TEST_CERT", info.subject);
        assertEquals(keyPair.getPublic().getAlgorithm(), info.algorithm);
        assertArrayEquals(keyPair.getPublic().getEncoded(), info.publicKey);
    }
}
