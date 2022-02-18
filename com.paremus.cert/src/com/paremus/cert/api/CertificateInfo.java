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

/**
 * This DTO encapsulates the information about a certificate
 */
public class CertificateInfo {

    /**
     * The alias (name) for the certificate
     */
    public String alias;

    /**
     * The type of the certificate
     */
    public String type;

    /**
     * The subject for the certificate
     */
    public String subject;

    /**
     * The algorithm used by the certificate
     */
    public String algorithm;

    /**
     * The public key for the certificate
     */
    public byte[] publicKey;
}
