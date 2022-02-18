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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class CertificateSigningRequestSubmitter {

    private final TrustStoreManager tsm;
    private final SecureRandom secureRandom;

    public CertificateSigningRequestSubmitter(TrustStoreManager tsm, SecureRandom secureRandom) {
        this.tsm = tsm;
        this.secureRandom = secureRandom;
    }

    public String issueCertificateSigningRequest(URL url, String signingRequest,
            String oneTimePasscode, String encodedCertificates) {
        if(!"https".equals(url.getProtocol())) {
            throw new IllegalArgumentException("The signing URL must be an HTTP URL");
        }

        try {
            KeyStore inMemoryTrustStore = tsm.createInMemoryTrustStore(encodedCertificates);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(inMemoryTrustStore);

            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, tmf.getTrustManagers(), secureRandom);

            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            try {
                conn.setSSLSocketFactory(context.getSocketFactory());
                conn.setDoOutput(true);
                conn.setUseCaches(false);

                conn.disconnect();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Accept", "text/plain;charset=utf-8");
                conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
                conn.setRequestProperty("Paremus-One-Time-Token", oneTimePasscode);
                conn.connect();

                try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), UTF_8))) {
                    bw.write(signingRequest);
                }

                try(BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF_8))) {
                    return br.lines().collect(Collectors.joining("\n"));
                }

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to make a signing request", e);
        }
    }

}
