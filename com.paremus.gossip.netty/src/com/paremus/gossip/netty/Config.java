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
package com.paremus.gossip.netty;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(factoryPid="com.paremus.gossip")
public @interface Config {

	int udp_port() default 9033;
	int tcp_port() default 9034;
	
	String bind_address() default "0.0.0.0";

	@AttributeDefinition(min="50")
	int gossip_interval() default 300;
	@AttributeDefinition(min="1", max="6")
	int gossip_fanout() default 2;
	@AttributeDefinition(min="1", max="5")
	int gossip_hops() default 3;
	@AttributeDefinition(min="1")
	int gossip_broadcast_rounds() default 20;

	@AttributeDefinition(min="0")
	long sync_interval() default 20000;
	@AttributeDefinition(min="0")
	long sync_retry() default 1000;
	

	String[] initial_peers();
	String cluster_name();
	
	String tls_target() default "";
	
	boolean infra() default false;
	
	@AttributeDefinition(min="0")
	int silent_node_probe_timeout() default 12000;
	@AttributeDefinition(min="0")
	int silent_node_eviction_timeout() default 15000;
}
