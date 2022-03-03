# RSA Test

This is a small test of the distributed OSGi implementation. It contains a number of components working together
to show how to create the smallest possible cluster.

* The base is a _provider_, this is a very simple service that registers a Hello World service and exports
  it with the @ExportedService annotation. The distribution provider does not require any configuration.
*.The consumer bundle is a gogo bundle that can call the Hello World service. It runs in both the provider
  (where it calls the real implementation) and the consumer framework, where it calls the remote version.
* The API is a separate bundle.

The consumer contains a configurator file for the base setup, see configs/consumer-config.json. Notice that
the bundle must require the configurator for this to work.

Since the consumer runs in the both frameworks, we need to make sure that the cluster setup uses proper
ports. In the bndrun files for the consumer and provider framework, we set the `udp.port` and `tcp.port`
that are then used in the activator of the ConsumerImpl to configure the netty cluster controller.

Since the consumer and provider frameworks have so much in common, the shared information is placed in
shared.bndrun. 

## Bundles

A short explanation of some of the used bundles:

* org.eclipse.ot.rsa.api  – The API bundle for the HelloWorld service
* org.eclipse.ot.rsa.example.provider – The implementation of the HelloWorld service
* org.eclipse.ot.rsa.example.consumer – A gogo bundle that configures the cluster can call the hello world service
* org.eclipse.ot.rsa.example.api – API
* org.eclipse.ot.rsa.gogo – A number of commands to query the Remote Service Admin `members` and cluster info. Especially `rexports` and `rimports` are cool.
* org.eclipse.ot.rsa.api – Provides the cluster & distributed APIs
* org.eclipse.ot.rsa.cluster.gossip.api.provider – Provides a cluster discovery
* org.eclipse.ot.rsa.distribution.provider – The implementation of the distribution provider
* org.eclipse.ot.rsa.topology.promiscuous.provider – API to the topology manager
* org.eclipse.ot.rsa.topology.cluster.provider – A topology manager based on the cluster API
* org.eclipse.ot.rsa.tls.netty.provider – Communication provider for DOSGI and the gossup discovery. This allows central handling of certificates
* biz.aQute.gogo.commands.provider – Provides useful gogo commands, especially srv! 

