package biz.aQute.rsa.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import com.paremus.cluster.ClusterInformation;

import aQute.launchpad.Launchpad;
import aQute.launchpad.LaunchpadBuilder;

public class ClusterTest {

	static LaunchpadBuilder builder = new LaunchpadBuilder();

	@Test
	public void simpleTest() throws Exception {

		try (Launchpad consumer = builder.bndrun("test.bndrun").gogo().debug()
				.bundles("biz.aQute.bnd.runtime.snapshot, biz.aQute.gogo.commands.provider").create("consumer")) {
			try (Launchpad provider = builder.bndrun("test.bndrun").create("provider")) {

				configure(provider, 1800, "127.0.0.1:1810");
				configure(consumer, 1810, "127.0.0.1:1800");

				testBasis(provider, 2);
				testBasis(consumer, 2);

				@SuppressWarnings("rawtypes")
				ServiceRegistration<Supplier> fooreg = provider.register(Supplier.class, () -> 42,
						"service.exported.interfaces", Supplier.class.getName());

				Awaitility.await().until(() -> {
					return consumer.getService(Supplier.class).map(s -> (int) s.get()).orElse(-1) == 42;
				});

				fooreg.unregister();
				Awaitility.await().until(() -> {
					return !consumer.getService(Supplier.class).isPresent();
				});

				provider.register(Supplier.class, () -> 21, "service.exported.interfaces", Supplier.class.getName());
				Awaitility.await().until(() -> {
					return consumer.getService(Supplier.class).map(s -> (int) s.get()).orElse(-1) == 21;
				});

				System.out.println("delete the provider cluster");
				delete(provider, "com.paremus.gossip.netty", "DIMC");

				testBasis(consumer, 1);

				System.out.println("check the service is gone");
				Awaitility.await().until(() -> {
					return !consumer.getService(Supplier.class).isPresent();
				});

				System.out.println("reconfigure the provider with cluster");
				configure(provider, 1800, "127.0.0.1:1810");

				System.out.println("see when we have two members again");
				testBasis(consumer, 2);
				System.out.println("see if the service is reregistered");
				Awaitility.await().until(() -> {
					return consumer.getService(Supplier.class).isPresent();
				});

				System.out.println("shutdown the provider");
			}
			System.out.println("check for the member gone");
			testBasis(consumer, 1);
			System.out.println("and the service");
			Awaitility.await().until(() -> {
				return !consumer.getService(Supplier.class).isPresent();
			});
			System.out.println("no more service");
			Thread.sleep(1000);
			System.out.println("closing the consumer");
		}
	}

	private void delete(Launchpad lp, String pid, String local) throws IOException, InvalidSyntaxException {
		ConfigurationAdmin ca = lp.waitForService(ConfigurationAdmin.class, 1000).get();
		if (local == null) {
			ca.getConfiguration(pid, "?").delete();
			return;
		}

		getConfiguration(ca, pid, local).delete();

	}

	private boolean testBasis(Launchpad lp, int size) {
		Optional<ClusterInformation> ci = lp.waitForService(ClusterInformation.class, 5000);
		assertThat(ci).isPresent();
		ClusterInformation cinfo = ci.get();
		System.out.println(cinfo.getClusterName());
		System.out.println(cinfo.getKnownMembers());
		Awaitility.await().timeout(30, TimeUnit.SECONDS).until(() -> cinfo.getKnownMembers().size() == size);
		return true;
	}

	private void configure(Launchpad lp, int port, String... peers) throws Exception {
		ConfigurationAdmin ca = lp.waitForService(ConfigurationAdmin.class, 1000).get();

		update(ca, "com.paremus.netty.tls", "insecure", true);
		update(ca, "com.paremus.dosgi.discovery.cluster", //
				"root.cluster", "DIMC", //
				"infra", false //
		);

		update(ca, "com.paremus.dosgi.net", //
				"server.bind.address", "127.0.0.1", //
				"allow.insecure.transports", true, //
				"server.protocols", "TCP;nodelay=true", //
				"nodelay", true //
		);

		updateFact(ca, "com.paremus.gossip.netty", "DIMC", //
				"cluster.name", "DIMC", //
				"initial.peers", peers, //
				"bind.address", "127.0.0.1", //
				"udp.port", port, //
				"tcp.port", port //
		);

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

}
