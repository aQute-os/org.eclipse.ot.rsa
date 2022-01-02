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

import static java.util.Locale.ROOT;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import com.paremus.cert.api.CertificateInfo;

public class KeyStoreManager extends AbstractStoreManager {

    public KeyStoreManager(Path keystoreFolder, BouncyCastleProvider provider, SecureRandom secureRandom) {
        super(keystoreFolder, provider, secureRandom);
    }

    @Override
    protected String getStoreFileExtension() {
        return ".keystore";
    }

    public void createKeyStore(String name, KeyPair keyPair, Certificate[] chain) {
        createStore(name, (ks, pw) -> ks.setKeyEntry(name.toLowerCase(ROOT), keyPair.getPrivate(), pw, chain));
    }

    public Certificate getCertificate(String keystoreName) {
        String alias = keystoreName.toLowerCase(Locale.ROOT);
        return loadFromStore(keystoreName, (ks, pw) -> ks.getCertificate(alias));
    }

    public Map<String, CertificateInfo> listKeyStores() {
        return listStores(s -> s.collect(Collectors.toMap(Function.identity(), n -> getCertificateInfo(n))));
    }

    public CertificateInfo getCertificateInfo(String keystoreName) {
        return toCertificateInfo(keystoreName.toLowerCase(Locale.ROOT), getCertificate(keystoreName));
    }

    public void createKeyStore(String keystoreName, KeyPair keyPair, String encodedCertificateAndChain) {
        try (PEMParser parser = new PEMParser(new StringReader(encodedCertificateAndChain))) {

            X509CertificateHolder cert = (X509CertificateHolder) parser.readObject();

            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            converter.setProvider(provider);
            X509Certificate certificate = converter.getCertificate(cert);

            List<X509Certificate> certs = new ArrayList<>();
            
            certs.add(certificate);

            while ((cert = (X509CertificateHolder) parser.readObject()) != null) {
                certs.add(converter.getCertificate(cert));
            }

            createKeyStore(keystoreName, keyPair, certs.toArray(new Certificate[0]));
        } catch (Exception e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        }
    }

    public void updateKeyStore(String keystoreName, KeyPair keyPair, String encodedCertificateAndChain) {
        try (PEMParser parser = new PEMParser(new StringReader(encodedCertificateAndChain))) {

            X509CertificateHolder cert = (X509CertificateHolder) parser.readObject();

            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            converter.setProvider(provider);
            X509Certificate certificate = converter.getCertificate(cert);

            List<X509Certificate> certs = new ArrayList<>();

            while ((cert = (X509CertificateHolder) parser.readObject()) != null) {
                certs.add(converter.getCertificate(cert));
            }

            updateStore(keystoreName, (ks, pw) -> {
                    String alias = keystoreName.toLowerCase(Locale.ROOT);
                    ks.setCertificateEntry(alias, certificate);
                    ks.setKeyEntry(alias, keyPair.getPrivate(), pw, certs.toArray(new Certificate[0]));
                });
        } catch (Exception e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        }
    }
    
    public static class SignerInfo {
        public final PrivateKey privateKey;
        
        public final Certificate[] trustChain;

        public SignerInfo(PrivateKey privateKey, Certificate[] trustChain) {
            this.privateKey = privateKey;
            this.trustChain = trustChain;
        }
    }
    
    
    public SignerInfo getSignerInfo(String keystoreName) {
        String alias = keystoreName.toLowerCase(Locale.ROOT);
        return loadFromStore(keystoreName, (ks, pw) ->  new SignerInfo((PrivateKey) ks.getKey(alias, pw), 
                ks.getCertificateChain(alias)));
    }
    
    public String getCertificateChain(String keystoreName) {
        String alias = keystoreName.toLowerCase(Locale.ROOT);
        return loadFromStore(keystoreName, (ks, pw) ->  {
            Certificate[] certificateChain = ks.getCertificateChain(alias);
            
            StringWriter writer = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
                for(Certificate c : certificateChain) {
                    pemWriter.writeObject(c);
                    pemWriter.write("\n");
                    pemWriter.flush();
                }
            }
            return writer.toString();
        });
    }
}
