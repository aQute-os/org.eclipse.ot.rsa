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
package com.paremus.gossip.activator;

import java.util.Set;

import aQute.bnd.annotation.metatype.Meta.AD;

public interface Config {

	@AD(deflt="9033", required=false)
	int base_udp_port();
	@AD(deflt="9034", required=false)
	int base_tcp_port();
	@AD(deflt="100", required=false)
	int port_increment();
	@AD(deflt="10", required=false)
	int max_members();
	
	@AD(deflt="0.0.0.0", required=false)
	String bind_address();

	@AD(deflt="300", required=false, min="50")
	int gossip_interval();
	@AD(deflt="2", required=false, min="1", max="6")
	int gossip_fanout();
	@AD(deflt="3", required=false, min="1", max="5")
	int gossip_hops();
	@AD(deflt="20", required=false, min="1")
	int gossip_broadcast_rounds();

	@AD(deflt="4000", required=false, min="0")
	long sync_interval();
	@AD(deflt="1000", required=false, min="0")
	long sync_retry();
	

	@AD
	Set<String> initial_peers();
	@AD
	String cluster_name();
	
	@AD(deflt="false", required=false)
	boolean infra();
	
	@AD(deflt="12000", required=false, min="0")
	int silent_node_probe_timeout();
	@AD(deflt="15000", required=false, min="0")
	int silent_node_eviction_timeout();
}
