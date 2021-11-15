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
package com.paremus.net.encode;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

public interface EncodingSchemeFactory {

	public abstract EncodingScheme createEncodingScheme();

	public abstract EncodingScheme createEncodingScheme(Runnable onKeyRegeneration);
	
	public abstract TrustManagerFactory getSSLTrustManagerFactory();
	
	public abstract KeyManagerFactory getSSLKeyManagerFactory();
	
	public abstract ServerSocketFactory getServerSocketFactory();

	public abstract SocketFactory getSocketFactory();
	
}
