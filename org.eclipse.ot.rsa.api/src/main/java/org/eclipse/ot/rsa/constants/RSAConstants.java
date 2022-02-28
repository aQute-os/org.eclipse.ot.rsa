package org.eclipse.ot.rsa.constants;

import org.osgi.framework.Constants;

public interface RSAConstants {
	/**
	 * Distribution provider main configuration, see DistributionConfig
	 */
	String	DISTRIBUTION_PROVIDER_PID		= "org.eclipse.ot.rsa.distribution";
	/**
	 * This is the configuration type that can be used to configure the
	 * distribution provider. If not set, it will use defaults. It is used in
	 * {@link Constants#SERVICE_EXPORTED_CONFIGS} and
	 * {@link Constants#SERVICE_IMPORTED_CONFIGS}. It should be set as a service
	 * property on the exporting services.
	 */

	String	DISTRIBUTION_CONFIGURATION_TYPE	= "org.eclipse.ot.rsa.distribution.config";
	String	DISTRIBUTION_METHODS			= "org.eclipse.ot.rsa.distribution.methods";
	String	DISTRIBUTION_SERIALIZATION		= "org.eclipse.ot.rsa.distribution.serialization";
	String	DISTRIBUTION_ENDPOINT_MARKER	= "org.eclipse.ot.rsa.distribution.endpoint.marker";

	/**
	 * Distribution provider transport configuration, see TransportConfig
	 */
	String	DISTRIBUTION_TRANSPORT_PID		= "org.eclipse.ot.rsa.distribution.transport";

	/**
	 * Transport TLS provider configuration see TransportTLSConfig
	 */
	String	TRANSPORT_TLS_PID				= "org.eclipse.ot.rsa.tls.netty";
	String	CLUSTER_GOSSIP_PID				= "org.eclipse.ot.rsa.cluster.gossip";

}
