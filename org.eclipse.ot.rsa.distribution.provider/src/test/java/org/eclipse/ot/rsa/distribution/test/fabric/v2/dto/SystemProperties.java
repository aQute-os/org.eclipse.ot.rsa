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
package org.eclipse.ot.rsa.distribution.test.fabric.v2.dto;

import java.net.URI;

import org.eclipse.ot.rsa.distribution.test.dto.api.struct;

public class SystemProperties extends struct {
	private static final long serialVersionUID = 1L;

	public SystemProperties() throws Exception {}

	public int		basePort;												// 9000;
	public int		clientCdsDiscoveryTimeout;								// 20000;
	public int		discardWait;											// 50000;
	public String	eventLogLevel;											// "WARN";
	public String	instanceDir;											// "/Ws/paremus/sf/dsf/build/generated/dist/var/0";
	public String	jiniPublishAddress;										// "10.0.1.33";
	public String	fabricName;												// "AQUTE";
	public String	features;												// "/Ws/paremus/sf/dsf/build/generated/dist/var//fibreCapabilities.0";
	public int		instance;												// 0;
	public String	jmxHost;												// "10.0.1.33";
	public int		jmxPort;												// 9001;
	public String	keyStorePassword;										// "paremus";
	public String	keyStorePath;											// "/Ws/paremus/sf/dsf/build/generated/dist/etc/fabric.keystore";
	public int		locationPort;											// 49150;
	public String	logDir;													// "/Ws/paremus/sf/dsf/build/generated/dist/var/0";
	public String	logLevel;												// "INFO";
	public URI		lookupServiceURLs;										// new
																			// URI("jini://127.0.0.1");
	public int		maxComposites;											// -1;
	public int		maxPort;												// 9099;
	public int		maxLeaseDuration;										// 30000;
	public int		minPort;												// 9010;
	public boolean	old_dds;												// (old.dds)
																			// false;
	public String	persistDir;												// "/Ws/paremus/sf/dsf/build/generated/dist/var/";

	/*
	 * Definitions in org.cauldron.newton.provisioner.ProvisionerImpl
	 */
	public int		provisionerDistributionBatchSize;						// 100;
	public int		provisionerRemoteBatchSize;								// 1;
	public int		provisionerRemoteContainerEvaluationPendingThreshold;	// 1;
	public int		provisionerRemoteContainerEvaluationThreshold;			// 1;
	public int		provisionerRemoteInstallTimeout;						// 60000;
	public int		provisionerRemoteNegotiationSnapshotSize;				// 1;
	public int		provisionerRemoteNegotiationTimeout;					// 60000;
	// the following is used in config.osg but not used in ProvisionerImpl
	public boolean	provisionerRemoteRebalancing;							// true;
	// this is the one used in org.cauldron.newton.provisioner.ProvisionerImpl
	public boolean	provisionerRemoteRebalance;								// true;

	public boolean	provisionerRemoteBoundary;								// true;
	public boolean	provisionerRemoteWaitHosted;							// true;
	public boolean	provisionerRemoteTicketDelta;							// true;
	public int		provisionerRemoteResolveTimeout;						// 60000;
	public int		provisionerRemoteStateTimeout;							// 30000;
	public int		provisionerRemoteTimeout;								// 60000;
	public int		proxyCdsDiscoveryTimeout;								// 120000;
	public int		registryPingInterval;									// 30000;
	public String	repositoryBlacklist;									// "file:*,http://badrepo.acme.com/*";
	public String	repositoryWhitelist;									// "http://badrepo.acme.com/allowed/*";
	public boolean	security;												// false;
	public boolean	server;													// false;
	public String	serverCdsBindAddress;									// "10.0.1.33";
	public int		socketTimeout;											// 90000;
	public int		systemManagerRemoteGCPeriod;							// 20000;
	public int		systemManagerRemoteInitialStartDelay;					// 10000;
	public int		systemManagerRemoteRecheckPeriod;						// 120000;
	public int		systemManagerRemoteRestartDelay;						// 10000;
	public int		ticketDelta;											// 0;
	public boolean	verbose;												// false;
}
