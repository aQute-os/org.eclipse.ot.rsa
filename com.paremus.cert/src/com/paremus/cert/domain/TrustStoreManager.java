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

import static javax.security.auth.x500.X500Principal.CANONICAL;

import java.io.StringReader;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;

import com.paremus.cert.api.CertificateInfo;

public class TrustStoreManager extends AbstractStoreManager {

    public TrustStoreManager(Path truststoreFolder, BouncyCastleProvider provider, SecureRandom secureRandom) {
        super(truststoreFolder, provider, secureRandom);
    }

    @Override
    protected String getStoreFileExtension() {
        return ".truststore";
    }

    public void createTrustStore(String name, Collection<Certificate> certs) {
        createStore(name, (ks, pw) -> addCertificates(certs, ks));
    }
    
    public KeyStore createInMemoryTrustStore(String encodedCertificates) throws KeyStoreException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try {
            ks.load(null, null);
        } catch (Exception e) {
           throw new KeyStoreException("Unable to initialise the keystore", e);
        }
        addCertificates(readCertificates(encodedCertificates), ks);
        return ks;
    }

    private void addCertificates(Collection<Certificate> certs, KeyStore ks) throws KeyStoreException {
        for (Certificate cert : certs) {
            if (ks.getCertificateAlias(cert) != null) {
                continue;
            }

            String alias;
            if (cert instanceof X509Certificate) {
                X509Certificate x509 = (X509Certificate) cert;

                String tmp = getSubject(x509);

                if (tmp == null || ks.containsAlias(tmp)) {
                    tmp = x509.getIssuerX500Principal().getName(CANONICAL) + "_" + x509.getSerialNumber();
                }

                alias = tmp.toLowerCase(Locale.ROOT);
            } else {
                throw new IllegalArgumentException(
                        "Unable to process the certificate " + cert + " as it is not an X509 certificate");
            }
            ks.setCertificateEntry(alias, cert);
        }
    }

    public Map<String, Certificate> getCertificates(String name) {

        return loadFromStore(name, (ks, pw) -> {
            Map<String, Certificate> certs = new HashMap<>();
            Enumeration<String> aliases = ks.aliases();

            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isCertificateEntry(alias)) {
                    certs.put(alias, ks.getCertificate(alias));
                } else {
                    // TODO log this
                }
            }

            return certs;
        });
    }

    public Map<String, Collection<CertificateInfo>> listTrustStores() {
        return listStores(s -> s.collect(Collectors.toMap(Function.identity(), n -> getCertificateInfo(n))));
    }

    public Collection<CertificateInfo> getCertificateInfo(String name) {
        return getCertificates(name).entrySet().stream().map(e -> toCertificateInfo(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public void createTrustStore(String trustStoreName, String encodedCertificates) {
        createTrustStore(trustStoreName, readCertificates(encodedCertificates));
    }

    private List<Certificate> readCertificates(String encodedCertificates) {
        List<Certificate> certs = new ArrayList<>();
        try (PEMParser parser = new PEMParser(new StringReader(encodedCertificates))) {

            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            converter.setProvider(provider);

            X509CertificateHolder cert;
            while ((cert = (X509CertificateHolder) parser.readObject()) != null) {
                certs.add(converter.getCertificate(cert));
            }

        } catch (Exception e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        }
        return certs;
    }

    public void removeTrustedCertificate(String trustStore, String certificateAlias) {
        updateStore(trustStore, (ks, pw) -> ks.deleteEntry(certificateAlias));
    }

    public void addTrustedCertificates(String trustStoreName, String encodedCertificates) {
        updateStore(trustStoreName, (ks, pw) -> addCertificates(readCertificates(encodedCertificates), ks));
    }
}
