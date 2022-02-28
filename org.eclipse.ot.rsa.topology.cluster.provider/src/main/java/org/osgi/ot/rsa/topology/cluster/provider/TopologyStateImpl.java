package org.osgi.ot.rsa.topology.cluster.provider;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.ot.rsa.logger.util.HLogger;
import org.eclipse.ot.rsa.singlethread.util.SingleThread;
import org.osgi.dto.DTO;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.hooks.service.ListenerHook.ListenerInfo;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteConstants;

import aQute.lib.collections.MultiMap;
import aQute.lib.converter.Converter;
import aQute.lib.exceptions.Exceptions;
import aQute.lib.io.IO;
import aQute.lib.json.JSONCodec;

/*
 * All methods in this calls are executed on a single thread. This class
 * implements the state machine to synchronize the local service state with
 * remote service states. The class handles both discovery sides, both export
 * and import.
 * <p>
 * When a service is successfully exported,
 * {@link #exported(EndpointDescription)} is called. This will register the
 * _service id_ (host, port, service.id) with the cluster. When this service
 * gets unexported, the {@link #unexported(EndpointDescription)} method is
 * called, this will unregister the service id with the cluster.
 * <p>
 * When the cluster reports an event, we check if we already know the service
 * id. If our knowledge does not match the cluster info then we send out a query
 * to get an Endpoint Description. This is a bit cumbersome but the cluster has
 * a very small limit on the information we can send.
 * <p>
 * When we receive a query or announcement over UDP, the
 * {@link #reply(InetAddress, int, byte[])} is called. The payload is a service
 * id. If the service id does not contain properties, we assume it is a query
 * and reply with the service id with the endpoint's properties. Ergo, if we
 * receive a service id with properties, it is the result of a query. If the
 * service id matches our current version, we update the import with the
 * endpoint information. (Or register it if it is the first time.)
 */
class TopologyStateImpl implements TopologyState {
	final static JSONCodec				codec			= new JSONCodec();
	final static String					MAGIC			= ".CLS.";
	final Map<ServiceId, ExportWrapper>	exportWrappers	= new HashMap<>();
	final Map<ServiceId, ImportWrapper>	importWrappers	= new HashMap<>();
	final SingleThread					singleThreadManager;
	final ClusterTopology				cluster;
	final AtomicBoolean					closed			= new AtomicBoolean(false);
	final AtomicBoolean					scheduled		= new AtomicBoolean(false);

	/**
	 * Filters that somebody in our framework is insterested in
	 */
	final MultiMap<String, Filter>		interests		= new MultiMap<>();

	/*
	 * Class identifies a service in the cluster.
	 */
	public static class ServiceId extends DTO {
		public String				host;
		public int					port;
		public long					serviceId;

		/*
		 * Incremented monotonously. With a long that should suffice for the
		 * remaining time in the universe.
		 */
		public long					version;

		/*
		 * If properties are not set, this is just information about the
		 * service. If it is set, the properties contain an endpoint
		 * description. Since the decoder takes default types, the deserialized
		 * values must be fixed up to match the requirements of
		 * EndpointDescription.
		 */
		public Map<String, Object>	properties;

		public ServiceId() {}

		public ServiceId(String host, int port, long serviceId) {
			this.host = host;
			this.port = port;
			this.serviceId = serviceId;
		}

		byte[] serialize() {
			try {
				return codec.enc()
					.put(this)
					.toString()
					.getBytes(StandardCharsets.UTF_8);
			} catch (Exception e) {
				throw Exceptions.duck(e);
			}
		}

		byte[] query() throws Exception {
			ServiceId query = properties == null ? this : copy();
			query.properties = null;

			return codec.enc()
				.put(query)
				.toString()
				.getBytes(StandardCharsets.UTF_8);
		}

		ServiceId fixup() {
			if (properties != null) {
				put(properties, Constants.OBJECTCLASS, String[].class);
				put(properties, RemoteConstants.ENDPOINT_SERVICE_ID, Long.class);
				put(properties, RemoteConstants.ENDPOINT_FRAMEWORK_UUID, String.class);
				put(properties, RemoteConstants.ENDPOINT_ID, String.class);
				put(properties, RemoteConstants.SERVICE_IMPORTED_CONFIGS, String[].class);
			}
			return this;
		}

		private void put(Map<String, Object> out, String key, Class<?> class1) {
			try {
				Object object = out.get(key);
				if (object == null || object.getClass() == class1)
					return;
				Object cnv = Converter.cnv(class1, object);
				out.put(key, cnv);
			} catch (Exception e) {
				throw Exceptions.duck(e);
			}
		}

		@Override
		public int hashCode() {
			return Objects.hash(host, port, serviceId);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ServiceId other = (ServiceId) obj;
			return Objects.equals(host, other.host) && port == other.port && serviceId == other.serviceId;
		}

		public ServiceId copy() {
			ServiceId sid = new ServiceId(host, port, serviceId);
			sid.version = version;
			sid.properties = properties;
			return sid;
		}

		@Override
		public String toString() {
			return "SID(" + host + ":" + port + "#" + serviceId + "[" + version + "])";
		}
	}

	static ServiceId deserialize(byte[] data) throws Exception {
		return codec.dec()
			.from(data)
			.get(ServiceId.class)
			.fixup();
	}

	/*
	 * Wraps the state of an import
	 */
	class ImportWrapper implements AutoCloseable {
		final HLogger		log;
		final ServiceId		sid;
		EndpointDescription	ed;
		ImportRegistration	importService;
		long				lastSend;

		public ImportWrapper(ServiceId sid) {
			this.log = cluster.log
				.child(() -> "/imp/" + sid.host + ":" + sid.port + "#" + sid.serviceId + "[" + sid.version + "] ");
			this.sid = sid;
		}

		@Override
		public void close() {
			log.info("unimport");
			if (importService != null)
				importService.close();
		}

		void discovered(ServiceId discovered) throws Exception {
			assert sid.equals(discovered) : "must match our id";

			if (this.sid.version == discovered.version && ed != null)
				return;

			if (System.currentTimeMillis() - lastSend > 2000) {
				log.debug("query");
				cluster.send(sid.host, sid.port, sid.query());
				lastSend = System.currentTimeMillis();
			} else {
				log.warn("too busy, delayed");
				scheduleDiscovery();
			}
		}

		void announced(ServiceId announced) {

			assert sid.equals(announced) : "we only work for our own id";
			assert announced.properties != null : "an announcement must have properties";

			ed = new EndpointDescription(announced.properties);

			this.sid.properties = announced.properties;
			this.sid.version = announced.version;
			checkInterests();
		}

		public void checkInterests() {
			if (ed == null)
				return;
			if (!isActive())
				return;

			int s = 0;
			if (shouldBeRegistered(ed)) {
				s = 1;
			}
			if (importService != null) {
				s += 2;
			}
			switch (s) {
				case 0 :
					break;
				case 1 :
					log.info("new import %s", ed);
					importService = cluster.rsa.importService(ed);
					break;
				case 2 :
					log.info("close import %s", ed);
					importService.close();
					importService = null;
					break;
				case 3 :
					log.info("update import %s", ed);
					importService.update(ed);
					break;
				default :
					assert false : "Unknown service import case";
			}
		}

		boolean isActive() {
			return cluster.context.getBundle(0)
				.getState() == Bundle.ACTIVE;
		}

		// TODO:: if this is a bottleneck we need to optimize this one.
		boolean shouldBeRegistered(EndpointDescription description) {
			System.out.println("checking " + description + " against " + interests.keySet());
			for (List<Filter> fl : interests.values()) {
				if (fl.isEmpty()) {
					continue;
				}
				Filter filter = fl.get(0);
				if (filter.matches(description.getProperties())) {
					System.out.println("yes " + filter);
					return true;
				}
			}
			return false;
		}
	}

	/*
	 * Wraps an export. A service can be exported multiple times. This class
	 * will keep one of the exported references as the selected.
	 */
	class ExportWrapper implements AutoCloseable {
		final ServiceReference<?>						ref;
		final ServiceId									sid;
		final Map<ExportReference, ExportRegistration>	exports	= new HashMap<>();
		final HLogger									log;

		ExportWrapper(ServiceReference<?> ref) {
			this.ref = ref;
			this.sid = getServiceId(ref);
			this.log = cluster.log.child(() -> "/exp/#" + sid.serviceId + "[" + sid.version + "] ");
			cluster.rsa.exportService(ref, null)
				.forEach(reg -> {
					this.log.info("export endpoint %s", reg.getExportReference()
						.getExportedEndpoint());
					exports.put(reg.getExportReference(), reg);
				});
		}

		void update(ExportReference ref) throws Exception {
			sid.version++;
			log.debug("inform the cluster");
			cluster.cluster.updateAttribute(getKey(sid.serviceId), sid.query());
		}

		@Override
		public void close() throws Exception {
			log.info("remove from cluster");
			cluster.cluster.updateAttribute(getKey(sid.serviceId), null);
			exports.values()
				.forEach(reg -> reg.close());
		}

		public void announce(InetAddress sender, int port) throws IOException {
			for (ExportRegistration r : exports.values()) {
				if (r.getException() != null)
					continue;

				EndpointDescription ed = r.getExportReference()
					.getExportedEndpoint();
				if (ed == null)
					continue;

				ServiceId sid = this.sid.copy();
				sid.properties = ed.getProperties();
				log.debug("reply to query");
				cluster.send(sender, port, sid.serialize());
				return;
			}
		}

		public void modified() {
			exports.values()
				.forEach(reg -> {
					reg.update(null);
				});
		}

	}

	TopologyStateImpl(ClusterTopology ct, SingleThread st) {
		this.cluster = ct;
		this.singleThreadManager = st;
	}

	@Override
	public void update(ExportReference ref) throws Exception {

		EndpointDescription ed = ref.getExportedEndpoint();
		if (ed == null) {
			// we can ignore this, it happens when the exports gets
			// closed. However, we already handle that from the
			// service tracker that calls closed(ServiceReference).
			return;
		}

		ServiceId sid = new ServiceId(cluster.host.getHostAddress(), cluster.port, ed.getServiceId());
		ExportWrapper w = exportWrappers.get(sid);
		w.update(ref);
	}

	@Override
	public void close() throws Exception {
		if (closed.getAndSet(true))
			return;

		importWrappers.values()
			.forEach(IO::close);
		exportWrappers.values()
			.forEach(IO::close);
	}

	@Override
	public void reply(InetAddress sender, int port, byte[] data) throws Exception {
		ServiceId sid = deserialize(data);

		if (sid.properties == null) {
			assert sid.port == cluster.port;
			query(sender, port, sid);
		} else {
			announcement(sid);
		}
	}

	private void announcement(ServiceId sid) {

		assert sid.port != cluster.port : "an announcement can never be for ourselves";
		assert sid.properties != null : "an announcement must have properties for the endpoint description";

		ImportWrapper w = importWrappers.get(sid);
		if (w == null) {
			cluster.log.info("missing %s for an announcement", sid);
			return; // race condition
		}
		w.announced(sid);
	}

	private void query(InetAddress sender, int port, ServiceId sid) throws Exception {
		assert sid.port == cluster.port : "queries must not be send to the local host";
		ExportWrapper w = exportWrappers.get(sid);
		if (w != null) {
			w.announce(sender, port);
		} else {
			cluster.log.warn("query for unknown export %s", sid);
		}
	}

	@Override
	public void discover() throws Exception {
		Set<ServiceId> all = new HashSet<>(importWrappers.keySet());

		for (UUID member : cluster.cluster.getKnownMembers()) {

			if (member.equals(cluster.cluster.getLocalUUID()))
				continue;

			InetAddress host = cluster.cluster.getAddressFor(member);

			for (Map.Entry<String, byte[]> e : cluster.cluster.getMemberAttributes(member)
				.entrySet()) {

				if (!isService(e.getKey()))
					continue;

				ServiceId sid = deserialize(e.getValue());
				all.remove(sid);

				assert sid.properties == null;

				ImportWrapper w = importWrappers.computeIfAbsent(sid, ImportWrapper::new);
				w.discovered(sid);
			}
		}
		all.forEach(sid -> {
			ImportWrapper removed = importWrappers.remove(sid);
			assert removed != null;
			removed.close();
		});
	}

	private boolean isService(String key) {
		return key.startsWith(MAGIC);
	}

	private String getKey(long serviceId) {
		return MAGIC + serviceId;
	}

	@Override
	public void export(ServiceReference<Object> reference) {
		ExportWrapper ew = new ExportWrapper(reference);
		exportWrappers.put(ew.sid, ew);
	}

	@Override
	public void modified(ServiceReference<Object> reference) {
		ServiceId sid = getServiceId(reference);
		ExportWrapper ew = exportWrappers.get(sid);
		ew.modified();
	}

	@Override
	public void closed(ServiceReference<Object> reference) throws Exception {
		ServiceId sid = getServiceId(reference);
		ExportWrapper removed = exportWrappers.remove(sid);
		if (removed != null) {
			removed.close();
		}
	}

	private ServiceId getServiceId(ServiceReference<?> ref) {
		long serviceId = (long) ref.getProperty(Constants.SERVICE_ID);
		return new ServiceId(cluster.host.getHostAddress(), cluster.port, serviceId);
	}

	private void scheduleDiscovery() {
		if (scheduled.getAndSet(true))
			return;

		singleThreadManager.schedule(() -> {
			scheduled.set(false);
			discover();
		}, 3000);
	}

	private void checkInterests() {
		importWrappers.values()
			.forEach(v -> v.checkInterests());
	}

	@Override
	public void added(Collection<ListenerInfo> listeners) {
		int originalSize = interests.size();
		listeners.forEach(l -> {
			String filter = l.getFilter();
			if (filter != null)
				try {
					Filter f = FrameworkUtil.createFilter(filter);
					interests.add(filter, f);
				} catch (InvalidSyntaxException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		});
		if (originalSize != interests.size())
			checkInterests();
	}

	@Override
	public void removed(Collection<ListenerInfo> listeners) {
		int originalSize = interests.size();
		listeners.forEach(l -> {
			String filter = l.getFilter();
			if (filter == null)
				return;
			List<Filter> list = interests.get(filter);
			if (list != null) {
				list.remove(0);
				if (list.isEmpty()) {
					interests.remove(filter);
				}
			}
		});
		if (originalSize != interests.size())
			checkInterests();
	}

	@Override
	public void find(BundleContext context, String name, String filter, boolean allServices,
		Collection<ServiceReference<?>> references) {
		StringBuilder sb = new StringBuilder();
		sb.append("(objectClass=")
			.append(name)
			.append(")");
		if (filter != null) {
			sb.insert(0, "(&");
			sb.append(filter)
				.append(")");
		}
		String flt = sb.toString();
		try {
			Filter f = FrameworkUtil.createFilter(flt);
			interests.add(flt, f);
			checkInterests();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
