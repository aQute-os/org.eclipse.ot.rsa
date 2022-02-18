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

import static com.paremus.cert.domain.KeyPairManager.Algorithm.EC;
import static java.nio.file.StandardOpenOption.CREATE_NEW;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi.EC;
import org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyPairGeneratorSpi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import com.paremus.cert.api.KeyPairInfo;

public class KeyPairManager {

    public static enum Algorithm {
        EC, RSA;
    }

    private final Path keysFolder;
    private final BouncyCastleProvider provider;
    private final SecureRandom secureRandom;

    public KeyPairManager(Path keysFolder, BouncyCastleProvider provider, SecureRandom secureRandom) {
        this.keysFolder = keysFolder;
        this.provider = provider;
        this.secureRandom = secureRandom;
    }


    private KeyPair generateKeyPair(Algorithm algorithm, int keySize) {
        KeyPairGenerator generator;

        switch(algorithm) {
            case EC:
                generator = new EC();
                break;
            case RSA:
                generator = new KeyPairGeneratorSpi();
                break;
            default:
                throw new IllegalArgumentException("Unknown certificate algorithm type " + algorithm);
        }

        generator.initialize(keySize, secureRandom);

        return generator.generateKeyPair();
    }


    public KeyPair newKeyPair(String name) {
        return newKeyPair(name, EC, 384);
    }

    public KeyPair newKeyPair(String name, Algorithm algorithm, int keyLength) {

        Path keyFile = keysFolder.resolve(name);
        try(JcaPEMWriter pw = new JcaPEMWriter(Files.newBufferedWriter(keyFile, CREATE_NEW))) {
            KeyPair keyPair = generateKeyPair(algorithm, keyLength);
            pw.writeObject(keyPair.getPrivate());
            return keyPair;
        } catch(FileAlreadyExistsException fae) {
            throw new IllegalArgumentException("There is already a key pair with name " + name);
        } catch (Exception e) {
            RuntimeException re = new RuntimeException(e);
            try {
                Files.delete(keyFile);
            } catch (IOException e1) {
               re.addSuppressed(e1);
            }
            throw re;
        }
    }

    public KeyPair getKeyPair(String name) {
        Path keyFile = keysFolder.resolve(name);
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(keyFile))) {

            PEMKeyPair keyPair = (PEMKeyPair) parser.readObject();

            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(provider);
            return new KeyPair(converter.getPublicKey(keyPair.getPublicKeyInfo()),
                    converter.getPrivateKey(keyPair.getPrivateKeyInfo()));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, KeyPairInfo> listKeyPairs() {

        try(Stream<Path> s = Files.list(keysFolder)) {
            return s.map(p -> p.getFileName().toString())
                .collect(Collectors.toMap(Function.identity(), this::getKeyPairInfo));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public KeyPairInfo getKeyPairInfo(String name) {
        KeyPair pair = getKeyPair(name);

        KeyPairInfo kpi = new KeyPairInfo();

        kpi.name = name;
        kpi.algorithm = pair.getPublic().getAlgorithm();
        kpi.publicKey = pair.getPublic().getEncoded();

        return kpi;
    }
}
