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

import org.osgi.service.metatype.annotations.AttributeDefinition;


public interface Config {

	@AttributeDefinition(required=false, defaultValue="1")
	byte publish_version();
	
	@AttributeDefinition(required=false)
	String signature_keystore();
	@AttributeDefinition(required=false, defaultValue="jks")
	String signature_keystore_type();
	@AttributeDefinition(required=false)
	String _signature_keystore_password();
	@AttributeDefinition
	String signature_key_alias();
	@AttributeDefinition(required=false)
	String signature_cert_alias();
	@AttributeDefinition(required=false)
	String _signature_key_password();
	@AttributeDefinition(required=false)
	String key_manager_algorithm();
	@AttributeDefinition(required=false)
	String trust_manager_algorithm();
	
	@AttributeDefinition
	String signature_truststore();
	@AttributeDefinition(required=false, defaultValue="jks")
	String signature_truststore_type();
	@AttributeDefinition(required=false)
	String _signature_truststore_password();
	
	/**
	 * What encryption should we use?
	 */
	@AttributeDefinition(required=false, defaultValue="AES")
	String encryption_algorithm();
	@AttributeDefinition(required=false, defaultValue="CBC/PKCS5Padding")
	String encryption_transform();
	@AttributeDefinition(required=false, defaultValue="HmacSHA1")
	String mac_algorithm();

	/**
	 * Supply a key directly 
	 */
	@AttributeDefinition(required=false)
	String _encryption_key();
	
	/**
	 * Supply a key in a key store
	 */
	
	@AttributeDefinition(required=false)
	String encryption_keystore();
	@AttributeDefinition(required=false, defaultValue="jks")
	String encryption_keystore_type();
	@AttributeDefinition(required=false)
	String _encryption_keystore_password();
	String encryption_key_alias();
	@AttributeDefinition(required=false)
	String _encryption_key_password();

	/**
	 * Generate a key
	 */
	@AttributeDefinition(required=false, defaultValue="128")
	int encryption_key_length();
	@AttributeDefinition(required=false, defaultValue="60", min="0")
	long encryption_key_expiry();
	@AttributeDefinition(required=false, defaultValue="MINUTES")
	TimeUnit encryption_key_expiry_unit();

	
	public static enum ClientAuth {
		NONE, WANT, NEED;
	}

	@AttributeDefinition(required=false, defaultValue="TLSv1.2")
	List<String> socket_protocols();
	@AttributeDefinition(required=false, defaultValue="TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256")
	List<String> socket_ciphers();
	@AttributeDefinition(required=false, defaultValue="NEED")
	ClientAuth socket_client_auth();
	

}
