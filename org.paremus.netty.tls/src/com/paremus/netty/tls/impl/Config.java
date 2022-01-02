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
package com.paremus.netty.tls.impl;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition
public @interface Config {

    @AttributeDefinition(description="Set this flag to make the TLS provider insecure. No handlers will be available and all other configuration will be ignored")
    boolean insecure() default false;
    
    @AttributeDefinition(description="The TLS protocol to use")
    String tls_protocol() default "TLSv1.2";

    @AttributeDefinition(description="The TLS protocol to use")
    String dtls_protocol() default "DTLSv1.2";

    @AttributeDefinition(description="The SSL provider to use")
    ProviderType provider() default ProviderType.JRE_DEFAULT;
    
    @AttributeDefinition(description="The Key Manager algorithm to use, defaults to the JRE default")
    String key_manager_algorithm() default "";

    @AttributeDefinition(description="The Trust Manager algorithm to use, defaults to the JRE default")
    String trust_manager_algorithm() default "";

    @AttributeDefinition(description="The type of the key store")
    String keystore_type() default "PKCS12";

    @AttributeDefinition(description="The type of the trust store")
    String truststore_type() default "PKCS12";

    @AttributeDefinition(description="The location of the key store providing the local certificate")
    String keystore_location();

    @AttributeDefinition(description="The password for the key store providing the local certificate")
    String _keystore_password();

    @AttributeDefinition(description="The password for retrieving keys from the key store providing the local certificate. If not set then the keystore password will be used")
    String _keystore_key_password() default "";

    @AttributeDefinition(description="The location of the trust store providing the local trust anchors")
    String truststore_location();
    
    @AttributeDefinition(description="The password of the trust store providing the local trust anchors")
    String _truststore_password();
    
    @AttributeDefinition(description="Configure whether client authentication is required, permitted, or not needed")
    ClientAuth client_auth() default ClientAuth.NEED;
}
