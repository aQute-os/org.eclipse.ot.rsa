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
package com.paremus.dosgi.net.tcp;

import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import com.paremus.net.encode.EncodingScheme;

import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import io.netty.util.internal.EmptyArrays;

/**
 * An {@link TrustManagerFactory} that trusts an X.509 trusted by the EncodingScheme.
 */
public final class EncodingSchemeFactoryTrustManager extends SimpleTrustManagerFactory {

    private final TrustManager tm = new X509ExtendedTrustManager() {
		
		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			 throw new CertificateException("No remote address was supplied for the certificate trust check");
		}
		
		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			es.isAcceptable(chain[0], socket.getInetAddress());
		}
		
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			 throw new CertificateException("No remote address was supplied for the certificate trust check");
		}
		
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			es.isAcceptable(chain[0], socket.getInetAddress());
		}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String s) throws CertificateException {
        	 throw new CertificateException("No remote address was supplied for the certificate trust check");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String s) throws CertificateException {
        	 throw new CertificateException("No remote address was supplied for the certificate trust check");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return EmptyArrays.EMPTY_X509_CERTIFICATES;
        }
    };
    
	private EncodingScheme es;


    /**
     * Creates a new instance.
     *
     * @param fingerprints a list of SHA1 fingerprints
     */
    public EncodingSchemeFactoryTrustManager(EncodingScheme es) {
		if (es == null) {
            throw new NullPointerException("EncodingScheme");
        }
		this.es = es;
    }

    @Override
    protected void engineInit(KeyStore keyStore) throws Exception { }

    @Override
    protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception { }

    @Override
    protected TrustManager[] engineGetTrustManagers() {
        return new TrustManager[] { tm };
    }
}