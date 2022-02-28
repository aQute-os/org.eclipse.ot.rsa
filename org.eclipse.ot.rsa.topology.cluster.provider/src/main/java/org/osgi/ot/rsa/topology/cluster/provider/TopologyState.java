package org.osgi.ot.rsa.topology.cluster.provider;

import java.net.InetAddress;
import java.util.Collection;

import org.eclipse.ot.rsa.singlethread.util.SingleThread;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.hooks.service.ListenerHook.ListenerInfo;
import org.osgi.service.remoteserviceadmin.ExportReference;

/**
 * This interface is defined to be used with the {@link SingleThread} class. It
 * defines the state methods of this cluster topology. It will execute all
 * methods on a single thread.
 */
public interface TopologyState extends AutoCloseable {

	/**
	 * Synchronize our service information. This is generally called from a
	 * cluster event. It is relatively cheap and idempotent so it can be called
	 * at any time. When the internal information is up to sync, no actions will
	 * be performed. If not, this might unregister a service or query the
	 * details of a changed service.
	 */
	void discover() throws Exception;

	/**
	 * Called when we receive a message from the UDP info port. This can either
	 * be a query or or an announcement.
	 *
	 * @param from The IP address of the sender
	 * @param port the port of the sender
	 * @param data the data
	 */
	void reply(InetAddress from, int port, byte[] data) throws Exception;

	/**
	 * A new service was detected that matches our criteria to export. This will
	 * export the service with RSA and register it with the cluster.
	 */
	void export(ServiceReference<Object> reference);

	/**
	 * From an RSA event, we might want to update our state
	 */
	void update(ExportReference ed) throws Exception;

	/**
	 * The service has been unregistered, clean up any exports and update the
	 * cluster.
	 */
	void closed(ServiceReference<Object> reference) throws Exception;

	/**
	 * The service has been modified, update the cluster.
	 */
	void modified(ServiceReference<Object> reference);

	void added(Collection<ListenerInfo> listeners);

	void removed(Collection<ListenerInfo> listeners);

	void find(BundleContext context, String name, String filter, boolean allServices,
		Collection<ServiceReference<?>> references);

}
