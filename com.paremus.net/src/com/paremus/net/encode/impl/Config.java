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
package com.paremus.net.encode.impl;

import java.util.List;
import java.util.concurrent.TimeUnit;

import aQute.bnd.annotation.metatype.Meta.AD;

public interface Config {

	@AD(required=false, deflt="1")
	byte publish_version();
	
	@AD(required=false)
	String signature_keystore();
	@AD(required=false, deflt="jks")
	String signature_keystore_type();
	@AD(required=false)
	String _signature_keystore_password();
	@AD
	String signature_key_alias();
	@AD(required=false)
	String signature_cert_alias();
	@AD(required=false)
	String _signature_key_password();
	@AD(required=false)
	String key_manager_algorithm();
	@AD(required=false)
	String trust_manager_algorithm();
	
	@AD
	String signature_truststore();
	@AD(required=false, deflt="jks")
	String signature_truststore_type();
	@AD(required=false)
	String _signature_truststore_password();
	
	/**
	 * What encryption should we use?
	 */
	@AD(required=false, deflt="AES")
	String encryption_algorithm();
	@AD(required=false, deflt="CBC/PKCS5Padding")
	String encryption_transform();
	@AD(required=false, deflt="HmacSHA1")
	String mac_algorithm();

	/**
	 * Supply a key directly 
	 */
	@AD(required=false)
	String _encryption_key();
	
	/**
	 * Supply a key in a key store
	 */
	
	@AD(required=false)
	String encryption_keystore();
	@AD(required=false, deflt="jks")
	String encryption_keystore_type();
	@AD(required=false)
	String _encryption_keystore_password();
	String encryption_key_alias();
	@AD(required=false)
	String _encryption_key_password();

	/**
	 * Generate a key
	 */
	@AD(required=false, deflt="128")
	int encryption_key_length();
	@AD(required=false, deflt="60", min="0")
	long encryption_key_expiry();
	@AD(required=false, deflt="MINUTES")
	TimeUnit encryption_key_expiry_unit();

	
	public static enum ClientAuth {
		NONE, WANT, NEED;
	}

	@AD(required=false, deflt="TLSv1.2")
	List<String> socket_protocols();
	@AD(required=false, deflt="TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256")
	List<String> socket_ciphers();
	@AD(required=false, deflt="NEED")
	ClientAuth socket_client_auth();
	

}
