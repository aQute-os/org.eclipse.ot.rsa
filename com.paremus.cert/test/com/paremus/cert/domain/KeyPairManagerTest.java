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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.paremus.cert.api.KeyPairInfo;

public class KeyPairManagerTest {

    @TempDir
    public File tempFolder;
    
    @Test
    public void testCreateKey() throws IOException {
        
        KeyPairManager manager = new KeyPairManager(tempFolder.toPath(), new BouncyCastleProvider(), new SecureRandom());

        assertNotNull(manager.newKeyPair("test"));
        
        KeyPairInfo info = manager.getKeyPairInfo("test");
        
        assertEquals("test", info.name);
        assertEquals("ECDSA", info.algorithm);
        assertNotNull(info.publicKey);
    }

}
