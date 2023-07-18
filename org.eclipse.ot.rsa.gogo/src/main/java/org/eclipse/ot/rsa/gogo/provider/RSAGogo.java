package org.eclipse.ot.rsa.gogo.provider;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Formatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.felix.service.command.Converter;
import org.apache.felix.service.command.annotations.GogoCommand;
import org.eclipse.ot.rsa.cluster.api.Action;
import org.eclipse.ot.rsa.cluster.api.ClusterInformation;
import org.eclipse.ot.rsa.cluster.api.ClusterListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointEvent;
import org.osgi.service.remoteserviceadmin.EndpointEventListener;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

@GogoCommand(scope = "rsa", function = {
	"members", "hosts", "cluster", "address", "local", "attributes", "attribute", "update", "rimports", "rexports"
})
@Component(immediate = true, property = "endpoint.listener.scope=(objectClass=*)")
public class RSAGogo implements Converter, EndpointEventListener, ClusterListener {
	@Reference
	RemoteServiceAdmin	admin;
	@Reference
	ClusterInformation	cluster;

	public Collection<UUID> members() {
		return cluster.getKnownMembers();
	}

	public Map<UUID, InetAddress> hosts() {
		return cluster.getMemberHosts();
	}

	public String cluster() {
		return cluster.getClusterName();
	}

	public InetAddress address(UUID member) {
		return cluster.getAddressFor(member);
	}

	public UUID local() {
		return cluster.getLocalUUID();
	}

	public Map<String, byte[]> attributes(UUID member) {
		return cluster.getMemberAttributes(member);
	}

	public byte[] attribute(UUID member, String key) {
		return cluster.getMemberAttribute(member, key);
	}

	public void update(String key, byte[] data) {
		cluster.updateAttribute(key, data);
	}

	public ImportRegistration rimports(EndpointDescription endpoint) {
		return admin.importService(endpoint);
	}

	public Collection<ExportReference> rexports() {
		return admin.getExportedServices();
	}

	public Collection<ImportReference> rimports() {
		return admin.getImportedEndpoints();
	}

	@Override
	public Object convert(Class<?> desiredType, Object in) throws Exception {
		return null;
	}

	@Override
	public CharSequence format(Object target, int level, Converter escape) throws Exception {
		if (target instanceof ExportRegistration) {
			try (Formatter sb = new Formatter()) {
				ExportRegistration t = (ExportRegistration) target;
				switch (level) {
					case Converter.PART :
						sb.format("> %s", t.getExportReference());
						break;
					case Converter.INSPECT :
					case Converter.LINE :
						sb.format("> %s exception=%s", t.getExportReference(), t.getException());
				}

				return sb.toString();
			}
		}
		if (target instanceof ImportReference) {
			try (Formatter sb = new Formatter()) {
				ImportReference t = (ImportReference) target;
				switch (level) {
					case Converter.PART :
						sb.format("< %s", t.getImportedEndpoint());
						break;
					case Converter.INSPECT :
					case Converter.LINE :
						sb.format("%s %s", t.getImportedEndpoint(), t.getImportedService());
				}

				return sb.toString();
			}
		}
		if (target instanceof ExportReference) {
			try (Formatter sb = new Formatter()) {
				ExportReference t = (ExportReference) target;
				switch (level) {
					case Converter.PART :
						sb.format("> %s", t.getExportedEndpoint());
						break;
					case Converter.INSPECT :
					case Converter.LINE :
						sb.format("> %s %s", t.getExportedEndpoint(), t.getExportedService());
				}

				return sb.toString();
			}
		}
		return null;
	}

	@Override
	public void endpointChanged(EndpointEvent event, String filter) {
		System.out.println("endpt event " + event + " " + filter);
	}

	@Override
	public void clusterEvent(ClusterInformation cluster, Action action, UUID id, Set<String> addedKeys,
		Set<String> removedKeys, Set<String> updatedKeys) {
		System.out.println(cluster + " action=" + action + " id=" + id + " add=" + addedKeys + " remove=" + removedKeys
			+ " update" + updatedKeys);

	}

}
