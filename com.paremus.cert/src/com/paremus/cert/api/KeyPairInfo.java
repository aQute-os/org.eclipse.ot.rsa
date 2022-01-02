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
 * This DTO encapsulates the information about a key pair 
 */
public class KeyPairInfo {

    /**
     * The name of the key pair
     */
    public String name;
    
    /**
     * The algorithm used by this key pair
     */
    public String algorithm;
    
    /**
     * The public key for this key pair
     */
    public byte[] publicKey;
    
}
