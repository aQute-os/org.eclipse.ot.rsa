package org.osgi.ot.rsa.topology.cluster.provider;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.ot.rsa.cluster.api.Action;
import org.eclipse.ot.rsa.cluster.api.ClusterInformation;
import org.eclipse.ot.rsa.cluster.api.ClusterListener;
import org.eclipse.ot.rsa.logger.util.HLogger;
import org.eclipse.ot.rsa.singlethread.util.SingleThread;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdminEvent;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdminListener;
import org.osgi.util.tracker.ServiceTracker;

import aQute.bnd.exceptions.Exceptions;
import aQute.lib.io.IO;

/**
 * Handles the topology of the a cluster. A gossip bundle is used to connect the
 * nodes in a cluster.
 * <p>
 * This component handles both discovering Services that are exported in a node,
 * are announced through the gossip bundle. The announcement is the service id.
 * When this bundle receives a service id, it will request the exporting node to
 * send the Endpoint Descriptor. (Unfortunately, the end point descriptor is too
 * big to be send via the gossip bundle.)
 * <p>
 */
@Component(service = {
	ClusterListener.class, org.osgi.framework.hooks.service.FindHook.class, ListenerHook.class
}, immediate = true /*
					 * importamt! otherwise the hooks cause this to unregister
					 * continuously
					 */)
public class ClusterTopology extends Thread
	implements ClusterListener, RemoteServiceAdminListener, org.osgi.framework.hooks.service.FindHook, ListenerHook {
	final static HLogger								root	= HLogger.root(ClusterTopology.class.getSimpleName());
	final HLogger										log;
	final RemoteServiceAdmin							rsa;
	final AtomicBoolean									busy	= new AtomicBoolean();
	final ClusterInformation							cluster;
	final int											port;
	final DatagramSocket								ds		= new DatagramSocket(0);
	final TopologyState									state;
	final InetAddress									host;
	final ServiceTracker<Object, ServiceReference<?>>	tracker;
	final BundleContext									context;

	@Activate
	public ClusterTopology(@Reference
	RemoteServiceAdmin rsa, @Reference
	ClusterInformation cluster, BundleContext context) throws Exception {
		System.out
			.println("RemoteServiceAdminListener classloader is " + RemoteServiceAdminListener.class.getClassLoader());

		System.out.println("Activating topology manager");
		this.rsa = rsa;
		this.cluster = cluster;
		this.context = context;
		this.port = ds.getLocalPort();
		tracker = new ServiceTracker<Object, ServiceReference<?>>(context,
			context.createFilter("(service.exported.interfaces=*)"), null) {
			@Override
			public ServiceReference<?> addingService(ServiceReference<Object> reference) {
				state.export(reference);
				return reference;
			}

			@Override
			public void modifiedService(ServiceReference<Object> reference, ServiceReference<?> service) {
				state.modified(reference);
			}

			@Override
			public void removedService(ServiceReference<Object> reference, ServiceReference<?> service) {
				try {
					state.closed(reference);
				} catch (Exception e) {
					log.unexpected(e);
				}
			}

		};
		this.log = root.child(() -> ":" + port + " ");
		this.log.register(RemoteServiceAdminEvent.class, this::toString);
		this.host = cluster.getAddressFor(cluster.getLocalUUID());
		this.state = SingleThread.create(TopologyState.class, (SingleThread st) -> new TopologyStateImpl(this, st),
			log);
		this.start();
	}

	@Deactivate
	void deactivate() throws InterruptedException {
		System.out.println("Deactivating topology manager");
		IO.close(state);
		IO.close(ds);
		join(10_000);
	}

	@Override
	public void remoteAdminEvent(RemoteServiceAdminEvent event) {
		try {
			log.debug("rsa event %s", event);
			switch (event.getType()) {

				case RemoteServiceAdminEvent.EXPORT_WARNING :
					log.warn("failed to Export ...", event);
				case RemoteServiceAdminEvent.EXPORT_UPDATE :
				case RemoteServiceAdminEvent.EXPORT_REGISTRATION :
					state.update(event.getExportReference());
					break;

				case RemoteServiceAdminEvent.EXPORT_ERROR :
					log.warn("failed to Export ...", event);
				case RemoteServiceAdminEvent.EXPORT_UNREGISTRATION :
					state.update(event.getExportReference());
					break;

				case RemoteServiceAdminEvent.IMPORT_ERROR :
				case RemoteServiceAdminEvent.IMPORT_WARNING :
					log.warn("%s", event);
					break;

				case RemoteServiceAdminEvent.IMPORT_REGISTRATION :
				case RemoteServiceAdminEvent.IMPORT_UNREGISTRATION :
				case RemoteServiceAdminEvent.IMPORT_UPDATE :
				default :
					log.debug("ignored event %s", event);
					break;
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw Exceptions.duck(e);
		}
	}

	@Override
	public void clusterEvent(ClusterInformation cluster, Action action, UUID id, Set<String> addedKeys,
		Set<String> removedKeys, Set<String> updatedKeys) {
		log.debug("cluster event ...", cluster, action, id, addedKeys, removedKeys);
		try {
			state.discover();
		} catch (Exception e) {
			throw Exceptions.duck(e);
		}
	}

	void send(String host, int port, byte[] request) throws IOException {
		InetAddress address = InetAddress.getByName(host);
		send(address, port, request);
	}

	void send(InetAddress host, int port, byte[] request) throws IOException {
		DatagramPacket dp = new DatagramPacket(request, request.length);
		dp.setAddress(host);
		dp.setPort(port);
		ds.send(dp);
	}

	@Override
	public String toString() {
		return "ClusterTopology[port=" + port + "] ";
	}

	/**
	 *
	 */
	@Override
	public void run() {
		System.out.println("starting UDP info provider");
		log.info("starting UDP info provider");
		tracker.open();
		ServiceRegistration<RemoteServiceAdminListener> registration = context
			.registerService(RemoteServiceAdminListener.class, this, null);

		try {
			byte[] data = new byte[20_000];
			DatagramPacket dp = new DatagramPacket(data, data.length);
			while (!isInterrupted())
				try {
					ds.receive(dp);
					byte[] x = new byte[dp.getLength()];
					System.arraycopy(dp.getData(), dp.getOffset(), x, 0, dp.getLength());
					InetAddress host = dp.getAddress();
					int port = dp.getPort();

					state.reply(host, port, x);
				} catch (SocketException t) {
					return;
				} catch (Throwable t) {
					log.error("unexpected %s", t);
				}
		} finally {
			System.out.println("quiting UDP info provider");
			log.info("quiting UDP info provider");
			registration.unregister();
			IO.close(() -> tracker.close());
		}
	}

	private String toString(RemoteServiceAdminEvent event) {
		StringBuilder sb = new StringBuilder();
		sb.append("RSA Event ")
			.append(eventType(event.getType()))
			.append(" ");
		if (event.getExportReference() != null) {
			sb.append(event.getExportReference()
				.getExportedService())
				.append("#")
				.append(event.getExportReference()
					.getExportedService()
					.getProperty(Constants.SERVICE_ID))
				.append(" ");
		}
		if (event.getImportReference() != null) {
			sb.append(event.getImportReference()
				.getImportedEndpoint())
				.append(" ");
		}
		if (event.getException() != null) {
			sb.append(event.getException());
		}
		return sb.toString();
	}

	String eventType(int rsae) {
		switch (rsae) {
			case RemoteServiceAdminEvent.EXPORT_WARNING :
				return "EXPORT_WARNING";
			case RemoteServiceAdminEvent.EXPORT_UPDATE :
				return "EXPORT_UPDATE";
			case RemoteServiceAdminEvent.EXPORT_REGISTRATION :
				return "EXPORT_REGISTRATION";
			case RemoteServiceAdminEvent.EXPORT_ERROR :
				return "EXPORT_ERROR";
			case RemoteServiceAdminEvent.EXPORT_UNREGISTRATION :
				return "EXPORT_UNREGISTRATION";
			case RemoteServiceAdminEvent.IMPORT_REGISTRATION :
				return "IMPORT_REGISTRATION";
			case RemoteServiceAdminEvent.IMPORT_UNREGISTRATION :
				return "IMPORT_UNREGISTRATION";
			case RemoteServiceAdminEvent.IMPORT_ERROR :
				return "IMPORT_ERROR";
			case RemoteServiceAdminEvent.IMPORT_WARNING :
				return "IMPORT_WARNING";
			case RemoteServiceAdminEvent.IMPORT_UPDATE :
				return "IMPORT_UPDATE";
		}
		return "?" + rsae;
	}

	@Override
	public void added(Collection<ListenerInfo> listeners) {
		state.added(listeners);

	}

	@Override
	public void removed(Collection<ListenerInfo> listeners) {
		state.removed(listeners);

	}

	@Override
	public void find(BundleContext context, String name, String filter, boolean allServices,
		Collection<ServiceReference<?>> references) {
		state.find(context, name, filter, allServices, references);
	}

}
