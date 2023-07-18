package org.eclipse.ot.rsa.integration.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.eclipse.ot.rsa.cluster.api.ClusterInformation;
import org.eclipse.ot.rsa.constants.RSAConstants;
import org.junit.Test;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

import aQute.launchpad.Launchpad;
import aQute.launchpad.LaunchpadBuilder;
import aQute.lib.io.IO;

public class ClusterTest {
	static ScheduledExecutorService	es		= Executors.newScheduledThreadPool(200);
	static LaunchpadBuilder			builder	= new LaunchpadBuilder().bndrun("test.bndrun");
	static {
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
	}

	@Test
	public void starTopologyTest() throws Exception {

		try (Launchpad a = builder.create("a")) {
			try (Launchpad b = builder.create("b")) {
				try (Launchpad c = builder.create("c")) {

					System.out.println("configure the 3 frameworks, do not fully connect the peers");
					configure(a, 1900, true, "127.0.0.1:1910", "127.0.0.1:1920", "127.0.0.1:1930", "127.0.0.1:1940");
					configure(b, 1910, true);
					configure(c, 1920, true);

					System.out.println("synchronize until all frameworks see their 3 peers");
					waitForClusterInformation(a, 3);
					waitForClusterInformation(b, 3);
					waitForClusterInformation(c, 3);

					System.out.println("Start a framework cc");
					try (Launchpad cc = builder.create("cc")) {
						System.out.println("Configure cc");
						configure(cc, 1940, true);
						System.out.println("wait until it has its peers");
						waitForClusterInformation(cc, 4);
						System.out.println("close cc");
					}
					System.out.println("close c (already done)");
				}
				System.out.println("close b");
			}
			System.out.println("close a");
		}
	}

	@Test
	public void clusterTest() throws Exception {

		try (Launchpad a = builder.create("a")) {
			try (Launchpad b = builder.create("b")) {
				try (Launchpad c = builder.create("c")) {

					System.out.println("configure the 3 frameworks, do not fully connect the peers");
					configure(a, 1900, true);
					configure(b, 1910, true, "127.0.0.1:1900");
					configure(c, 1920, true, "127.0.0.1:1910", "127.0.0.1:1930");

					System.out.println("synchronize until all frameworks see their 3 peers");
					waitForClusterInformation(a, 3);
					waitForClusterInformation(b, 3);
					waitForClusterInformation(c, 3);

					ClusterInformation aci = a.getService(ClusterInformation.class)
						.get();
					ClusterInformation bci = b.getService(ClusterInformation.class)
						.get();
					ClusterInformation cci = c.getService(ClusterInformation.class)
						.get();

					System.out.println("check the name of the cluster");

					assertThat(aci.getClusterName()).isEqualTo("SITE");
					assertThat(bci.getClusterName()).isEqualTo("SITE");
					assertThat(cci.getClusterName()).isEqualTo("SITE");

					System.out.println("verify the member UUIDs");
					UUID auid = aci.getLocalUUID();
					UUID buid = bci.getLocalUUID();
					UUID cuid = cci.getLocalUUID();

					assertThat(aci.getKnownMembers()).contains(auid, buid, cuid);
					assertThat(bci.getKnownMembers()).contains(auid, buid, cuid);
					assertThat(cci.getKnownMembers()).contains(auid, buid, cuid);

					System.out.println("ensure the address matches what we told");
					Map<UUID, InetAddress> hosts = aci.getMemberHosts();
					hosts.values()
						.forEach((ia) -> assertThat(ia.isLoopbackAddress()).isTrue());

					System.out.println("set an attribute on framework a, and check b & c");

					aci.updateAttribute("foo", "HELLO".getBytes(StandardCharsets.UTF_8));

					Awaitility.await()
						.until(() -> aci.getMemberAttribute(auid, "foo") != null);
					Awaitility.await()
						.until(() -> bci.getMemberAttribute(auid, "foo") != null);
					Awaitility.await()
						.until(() -> cci.getMemberAttribute(auid, "foo") != null);

					assertThat(aci.getMemberAttribute(auid, "foo")).asString()
						.isEqualTo("HELLO");
					assertThat(bci.getMemberAttribute(auid, "foo")).asString()
						.isEqualTo("HELLO");
					assertThat(cci.getMemberAttribute(auid, "foo")).asString()
						.isEqualTo("HELLO");

					System.out.println("remove the attribute, and check it is removed everywhere");
					aci.updateAttribute("foo", null);
					Awaitility.await()
						.until(() -> aci.getMemberAttribute(auid, "foo") == null);
					Awaitility.await()
						.until(() -> bci.getMemberAttribute(auid, "foo") == null);
					Awaitility.await()
						.until(() -> cci.getMemberAttribute(auid, "foo") == null);

					System.out.println("close framework c, check if the others detect this");
					c.close();

					Thread.sleep(1000);
					assertThat(aci.getKnownMembers()).contains(auid, buid);
					assertThat(bci.getKnownMembers()).contains(auid, buid);

					System.out.println("Start a framework cc");
					try (Launchpad cc = builder.create("cc")) {
						System.out.println("Configure cc");
						configure(cc, 1940, true, "127.0.0.1:1900");
						System.out.println("wait until it has its peers");
						waitForClusterInformation(cc, 3);
						ClusterInformation ccci = cc.getService(ClusterInformation.class)
							.get();
						UUID ccuid = ccci.getLocalUUID();
						Thread.sleep(1000);
						System.out.println("check the cluster is complete");
						assertThat(aci.getKnownMembers()).contains(auid, buid, ccuid);
						assertThat(bci.getKnownMembers()).contains(auid, buid, ccuid);
						assertThat(ccci.getKnownMembers()).contains(auid, buid, ccuid);

						System.out.println("close cc");
					}
					System.out.println("close c (already done)");
				}
				System.out.println("close b");
			}
			System.out.println("close a");
		}
	}

	public static class Foo {

	}

	@Test
	public void importExportTest() throws Exception {

		System.out.println("start a");
		try (Launchpad a = builder.create("a")) {
			System.out.println("start b");
			try (Launchpad b = builder.create("b")) {
				try (Launchpad c = builder // .gogo()
					.create("c")) {

					configure(a, 0, false);
					configure(b, 0, false);

					RemoteServiceAdmin arsa = a.waitForService(RemoteServiceAdmin.class, 4000)
						.get();
					RemoteServiceAdmin brsa = b.waitForService(RemoteServiceAdmin.class, 4000)
						.get();

					ServiceRegistration<Foo> afoo = a.register(Foo.class, new Foo(),
						Constants.SERVICE_EXPORTED_INTERFACES, "*");
					ExportRegistration afooe = arsa.exportService(afoo.getReference(), null)
						.iterator()
						.next();
					ExportReference afoor = afooe.getExportReference();
					EndpointDescription afooep = afoor.getExportedEndpoint();

					ImportRegistration bfooi = brsa.importService(afooep);
					assertThat(bfooi.getException()).isNull();

				}
				System.out.println("close b");
			}
			System.out.println("close a");
		}
	}

	static interface Node {
		void send(int launchpad);
	}

	// @Test
	public void longrunnning() throws Throwable {

		Launchpad[] launchpads = cluster(2);
		try {
			Thread[] threads = new Thread[launchpads.length];

			AtomicReference<Throwable> throwable = new AtomicReference<>();

			for (int i = 0; i < launchpads.length; i++) {
				Launchpad lp = launchpads[i];
				if (i == 0) {
					configure(lp, 1900, true);
				} else {
					configure(lp, 1900 + i, true, "127.0.0.1:1900");
				}
			}
			for (Launchpad lp : launchpads) {
				waitForClusterInformation(lp, launchpads.length);
			}
			for (int i = 0; i < launchpads.length; i++) {
				Launchpad lp = launchpads[i];
				int n = i;
				threads[i] = new Thread(() -> {
					play(lp, n, throwable);
				}, "player " + i);
				threads[i].start();
			}

			while (throwable.get() == null) {
				Thread.sleep(100);
			}
			throw throwable.get();
		} finally {
			close(launchpads);
		}
	}

	void play(Launchpad lp, int n, AtomicReference<Throwable> throwable) {
		try {
			if ((n & 1) == 0) {
				while (true) {
					System.out.println("Waiting for a node in  " + n);
					Optional<Node> service = lp.waitForService(Node.class, 10_000_000);
					System.out.println("Got service  " + service);
					assertThat(service).isPresent();
					Node c = service.get();
					System.out.println("tx " + n);
					c.send(n);
					Thread.sleep(1000 + n);
				}
			} else {
				System.out.println("Registering node " + n);
				ServiceRegistration<Node> register = lp.register(Node.class, (i) -> {
					System.out.println("rx " + i);
				}, Constants.SERVICE_EXPORTED_INTERFACES, "*");
				while (true) {
					Thread.sleep(1000 + n);
					Hashtable<String, Object> ht = new Hashtable<>();
					ht.put("x", System.nanoTime());
					register.setProperties(ht);
				}
			}
		} catch (Throwable t) {
			System.out.println("Leaving lp " + n + " due to " + t);
			throwable.set(t);
		}
	}

	@Test
	public void manyTest() throws Exception {
		Launchpad[] launchpads = cluster(10);

		for (int i = 0; i < launchpads.length; i++) {
			Launchpad lp = launchpads[i];
			if (i == 0) {
				configure(lp, 1900, true);
			} else {
				configure(lp, 1900 + i, true, "127.0.0.1:1900");
			}
		}
		for (Launchpad lp : launchpads) {
			waitForClusterInformation(lp, launchpads.length);
		}

		close(launchpads);
	}

	private void close(Launchpad[] launchpads) throws InterruptedException {
		CountDownLatch l = new CountDownLatch(launchpads.length);

		for (Launchpad lp : launchpads) {
			es.execute(() -> {
				IO.close(lp);
				l.countDown();
			});
		}
		l.await();
	}

	private Launchpad[] cluster(int count) throws InterruptedException {
		Launchpad[] lps = new Launchpad[count];
		CountDownLatch l = new CountDownLatch(count);
		for (int i = 0; i < count; i++) {
			String name = "" + i;
			int n = i;
			es.execute(() -> {
				Launchpad lp = n == 1 ? builder.create(name) : builder.create(name);
				System.out.println("build " + lp.getBundleContext());
				lps[n] = lp;
				l.countDown();
			});
		}
		l.await();
		return lps;
	}

	void delete(Launchpad lp, String pid, String local) throws IOException, InvalidSyntaxException {
		ConfigurationAdmin ca = lp.waitForService(ConfigurationAdmin.class, 1000)
			.get();
		if (local == null) {
			ca.getConfiguration(pid, "?")
				.delete();
			return;
		}

		getConfiguration(ca, pid, local).delete();

	}

	private boolean waitForClusterInformation(Launchpad lp, int size) {
		Optional<ClusterInformation> ci = lp.waitForService(ClusterInformation.class, 30_000);
		assertThat(ci).isPresent();
		ClusterInformation cinfo = ci.get();
		System.out.println(cinfo.getClusterName());
		System.out.println(cinfo.getKnownMembers());
		Awaitility.await()
			.timeout(60, TimeUnit.SECONDS)
			.until(() -> cinfo.getKnownMembers()
				.size() == size);
		return true;
	}

	private void configure(Launchpad lp, int port, boolean cluster, String... peers) throws Exception {
		ConfigurationAdmin ca = lp.waitForService(ConfigurationAdmin.class, 1000)
			.get();

		update(ca, RSAConstants.TRANSPORT_TLS_PID, "insecure", true);
		update(ca, RSAConstants.DISTRIBUTION_TRANSPORT_PID);
		update(ca, RSAConstants.DISTRIBUTION_PROVIDER_PID, //
			"server.bind.address", "127.0.0.1", //
			"allow.insecure.transports", true, //
			"server.protocols", "TCP;nodelay=true", //
			"nodelay", true //
		);

		if (cluster) {
			updateFact(ca, RSAConstants.CLUSTER_GOSSIP_PID, "SITE", //
				"cluster.name", "SITE", //
				"initial.peers", peers, //
				"bind.address", "127.0.0.1", //
				"udp.port", port, //
				"tcp.port", port //
			);
		}
	}

	private void update(ConfigurationAdmin cm, String pid, Object... args) throws IOException {
		assert (1 & args.length) == 0;
		Hashtable<String, Object> properties = new Hashtable<>();
		for (int i = 0; i < args.length; i += 2) {
			String key = (String) args[i];
			properties.put(key, args[i + 1]);
		}
		Configuration cnf = cm.getConfiguration(pid, "?");
		cnf.update(properties);
	}

	private void updateFact(ConfigurationAdmin cm, String pid, String local, Object... args) throws Exception {
		assert (1 & args.length) == 0;

		Hashtable<String, Object> properties = new Hashtable<>();
		properties.put(".local.name", local);
		for (int i = 0; i < args.length; i += 2) {
			String key = (String) args[i];
			properties.put(key, args[i + 1]);
		}
		Configuration cnf = getConfiguration(cm, pid, local);
		cnf.update(properties);
	}

	private Configuration getConfiguration(ConfigurationAdmin cm, String pid, String local)
		throws IOException, InvalidSyntaxException {
		Configuration[] list = cm.listConfigurations("(&(.local.name=" + local + ")(service.factoryPid=" + pid + "))");
		Configuration cnf;
		if (list == null || list.length == 0) {
			cnf = cm.createFactoryConfiguration(pid, "?");
		} else {
			assert list.length == 1;
			cnf = list[0];
		}
		return cnf;
	}

	void until(Callable<Object> bs) {
		Awaitility.await()
			.until(() -> isTrue(bs.call()));
	}

	void untilNot(Callable<Object> bs) {
		Awaitility.await()
			.until(() -> !isTrue(bs.call()));
	}

	private boolean isTrue(Object call) {
		return call != null || Boolean.TRUE.equals(call) || (call instanceof String && !"".equals(call));
	}

}
