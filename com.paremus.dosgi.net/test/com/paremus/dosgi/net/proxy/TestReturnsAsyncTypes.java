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
package com.paremus.dosgi.net.proxy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.osgi.util.promise.Promise;

public interface TestReturnsAsyncTypes {
	Promise<Boolean> coprime(long a, long b);
	
	Future<Boolean> isPrime(long x);
	
	CompletableFuture<Boolean> countGrainsOfSand(String location);
}