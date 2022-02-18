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

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static javax.security.auth.x500.X500Principal.CANONICAL;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.paremus.cert.api.CertificateInfo;

public abstract class AbstractStoreManager {

    private static final String SECRET_FILE_EXTENSION = ".secret";

    private final Path storeFolder;
    protected final BouncyCastleProvider provider;
    protected final SecureRandom secureRandom;

    public AbstractStoreManager(Path storeFolder, BouncyCastleProvider provider, SecureRandom secureRandom) {
        this.storeFolder = storeFolder;
        this.provider = provider;
        this.secureRandom = secureRandom;
    }

    protected abstract String getStoreFileExtension();

    protected static interface StoreAction {
        public void accept(KeyStore ks, char[] pw) throws Exception;
    }

    protected static interface StoreFunction<T> {
        public T apply(KeyStore ks, char[] pw) throws Exception;
    }

    static class StoreInfo {
        final String location;

        final String type;

        final String password;

        private StoreInfo(String location, String type, String password) {
            this.location = location;
            this.type = type;
            this.password = password;
        }
    }

    protected void createStore(String name, StoreAction action) {

        Path store = storeFolder.resolve(name + getStoreFileExtension());
        Path secret = storeFolder.resolve(name + SECRET_FILE_EXTENSION);

        char[] pw = new char[16];
        try (OutputStream os = Files.newOutputStream(store, CREATE_NEW);
             BufferedWriter bw = Files.newBufferedWriter(secret, CREATE_NEW)) {

            StringBuilder hexString = new StringBuilder(Long.toHexString(secureRandom.nextLong()));

            while(hexString.length() < 16) {
                hexString.insert(0, '0');
            }

            hexString.getChars(0, 16, pw, 0);

            bw.write(pw);
            bw.flush();

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);

            action.accept(ks, pw);

            ks.store(os, pw);

        } catch (Exception e) {
            RuntimeException re = new RuntimeException(e);
            try {
                Files.deleteIfExists(store);
            } catch (IOException e1) {
                re.addSuppressed(e1);
            }
            try {
                Files.deleteIfExists(secret);
            } catch (IOException e1) {
                re.addSuppressed(e1);
            }
            throw re;
        } finally {
            Arrays.fill(pw, (char) 0);
        }
    }

    protected <T> T loadFromStore(String name, StoreFunction<T> action) {

        Path store = storeFolder.resolve(name + getStoreFileExtension());
        Path secret = storeFolder.resolve(name + SECRET_FILE_EXTENSION);

        char[] pw = new char[16];
        try (InputStream is = Files.newInputStream(store);
             BufferedReader br = Files.newBufferedReader(secret)) {

            int read = 0;
            do {
                read = br.read(pw, read, pw.length - read);
            } while(read < pw.length);

            KeyStore ks = KeyStore.getInstance("PKCS12");

            ks.load(is, pw);

            return action.apply(ks, pw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            Arrays.fill(pw, (char) 0);
        }
    }

    protected void updateStore(String name, StoreAction action) {

        Path store = storeFolder.resolve(name + getStoreFileExtension());

        loadFromStore(name, (ks, pw) -> {
                try (OutputStream os = Files.newOutputStream(store)) {
                    action.accept(ks, pw);
                    ks.store(os, pw);
                }
                return null;
            });
    }

    protected <T> T listStores(Function<Stream<String>, T> action) {

        try(Stream<Path> s = Files.list(storeFolder)) {
            return action.apply(s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(getStoreFileExtension()))
                    .map(n -> n.substring(0, n.length() - getStoreFileExtension().length())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected CertificateInfo toCertificateInfo(String alias, Certificate cert) {

        CertificateInfo ci = new CertificateInfo();

        ci.alias = alias;
        ci.type = cert.getType();
        ci.subject =  cert instanceof X509Certificate ? getSubject((X509Certificate) cert) :
            "<unknown certificate type>";
        ci.algorithm = cert.getPublicKey().getAlgorithm();
        ci.publicKey = cert.getPublicKey().getEncoded();

        return ci;
    }

    protected String getSubject(X509Certificate x509) {
        X500Name x500name;
        try {
            x500name = new JcaX509CertificateHolder(x509).getSubject();
        } catch (CertificateEncodingException e) {
           throw new IllegalArgumentException("Not a valid certificate", e);
        }
        RDN[] cns = x500name.getRDNs(BCStyle.CN);

        String tmp = null;
        if(cns.length > 0) {
            tmp = IETFUtils.valueToString(cns[0].getFirst().getValue());
        } else {
            tmp = x509.getSubjectX500Principal().getName(CANONICAL);
        }
        return tmp;
    }

    public StoreInfo getStoreFor(String name, String pid) {


        Path store = storeFolder.resolve(name + getStoreFileExtension());

        return loadFromStore(name, (ks, pw) -> new StoreInfo(store.toAbsolutePath().toString(),
                ks.getType(), String.valueOf(pw)));
    }

    public void deleteStore(String name) {
        Path store = storeFolder.resolve(name + getStoreFileExtension());
        Path secret = storeFolder.resolve(name + SECRET_FILE_EXTENSION);

        try {
            Files.deleteIfExists(store);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        try {
            Files.deleteIfExists(secret);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
