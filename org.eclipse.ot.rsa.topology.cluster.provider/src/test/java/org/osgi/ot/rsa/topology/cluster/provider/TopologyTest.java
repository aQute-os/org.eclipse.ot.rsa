package org.osgi.ot.rsa.topology.cluster.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.awaitility.Awaitility;
import org.eclipse.ot.rsa.cluster.api.ClusterInformation;
import org.eclipse.ot.rsa.cluster.api.ClusterListener;
import org.eclipse.ot.rsa.constants.RSAConstants;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

import aQute.launchpad.Launchpad;
import aQute.launchpad.LaunchpadBuilder;
import aQute.lib.io.IO;

public class TopologyTest {
	static ScheduledExecutorService	es		= Executors.newScheduledThreadPool(200);
	static LaunchpadBuilder			builder	= new LaunchpadBuilder().bndrun("test.bndrun");
	static {
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void lifecycleTest() throws Exception {

		try (Launchpad a = builder.create("a")) {
			try (Launchpad b = builder.create("b")) {
				try (Launchpad c = builder.create("c")) {
					BundleContext ax = a.getBundleContext();
					BundleContext bx = b.getBundleContext();
					BundleContext cx = c.getBundleContext();
					System.out.println("fw a " + ax.getProperty(Constants.FRAMEWORK_UUID));
					System.out.println("fw b " + bx.getProperty(Constants.FRAMEWORK_UUID));
					System.out.println("fw c " + cx.getProperty(Constants.FRAMEWORK_UUID));
					configure(a, 1800);
					configure(b, 1810, "127.0.0.1:1800");
					configure(c, 1820, "127.0.0.1:1810");

					testBasis(a, 3);
					testBasis(b, 3);
					testBasis(c, 3);

					ClusterTopology ca = setup(a);
					ClusterTopology cb = setup(b);
					ClusterTopology cc = setup(c);

					ServiceRegistration<Supplier> fooreg = a.register(Supplier.class, () -> 42,
						"service.exported.interfaces", Supplier.class.getName());

					Optional<Supplier> s = b.waitForService(Supplier.class, 10_000);
					assertThat(s).isPresent()
						.get();
					Supplier<?> supplier = s.get();
					assertThat(supplier.get()).isEqualTo(42);

					Thread.sleep(2000);
					System.out.println("update the properties");

					Hashtable<String, Object> ht = new Hashtable<>();
					ht.put("service.exported.interfaces", Supplier.class.getName());
					ht.put("foo", "FOO");
					fooreg.setProperties(ht);

					until(() -> {
						ServiceReference<Supplier> ref = bx.getServiceReference(Supplier.class);
						return ref != null && ref.getProperty("foo") != null;
					});

					System.out.println("bring down b importer topology manager");
					cb.deactivate();

					System.out.println("check if b has unimported this service");
					untilNot(() -> bx.getServiceReference(Supplier.class));

					System.out.println("check c is unaffected");
					until(() -> cx.getServiceReference(Supplier.class));

					System.out.println("revive b topology manager");
					cb = setup(b);
					System.out.println("check if b has imported this service again");
					until(() -> bx.getServiceReference(Supplier.class));

					System.out.println("register a second service in b");
					ServiceRegistration<Consumer> s2 = b.register(Consumer.class, (x) -> System.out.println(x),
						"service.exported.interfaces", Consumer.class.getName());
					System.out.println("verify it is in a & c");
					until(() -> ax.getServiceReference(Consumer.class));
					until(() -> cx.getServiceReference(Consumer.class));

					System.out.println("unregister services");
					fooreg.unregister();
					s2.unregister();

					untilNot(() -> bx.getServiceReference(Supplier.class));
					untilNot(() -> cx.getServiceReference(Supplier.class));
					untilNot(() -> bx.getServiceReference(Consumer.class));
					untilNot(() -> ax.getServiceReference(Consumer.class));

					System.out.println("shutdown the provider");
				}
				System.out.println("closing the consumer");
			}
		}
	}

	@SuppressWarnings("rawtypes")
	@Test
	public void manyTest() throws Exception {
		Launchpad[] launchpads = cluster(5);
		ClusterTopology[] cts = new ClusterTopology[launchpads.length];

		for (int i = 0; i < launchpads.length; i++) {
			Launchpad lp = launchpads[i];
			if (i == 0) {
				configure(lp, 1800);
			} else {
				configure(lp, 1800 + i, "127.0.0.1:1800");
			}
		}
		for (int i = 0; i < launchpads.length; i++) {
			Launchpad lp = launchpads[i];
			testBasis(lp, launchpads.length);
			cts[i] = setup(lp);
		}

		CountDownLatch cl = new CountDownLatch(launchpads.length);
		ServiceRegistration<Consumer> fooreg = launchpads[0].register(Consumer.class, (x) -> {
			System.out.println("received from " + x);
			cl.countDown();
		}, "service.exported.interfaces", Consumer.class.getName());

		List<Throwable> errors = new ArrayList<>();
		for (int i = 0; i < launchpads.length; i++) {
			Launchpad lp = launchpads[i];
			es.execute(() -> {
				try {
					Optional<Consumer> s = lp.waitForService(Consumer.class, 10_000);
					assertThat(s).isPresent();
					s.get()
						.accept(lp.getName());
				} catch (Throwable t) {
					errors.add(t);
				}
			});
		}
		cl.await();
		assertThat(errors).isEmpty();
		for (int i = 0; i < launchpads.length; i++) {
			Launchpad lp = launchpads[i];
			ClusterTopology ct = cts[i];
			ct.deactivate();
			if (i == 0) {
				Thread.sleep(1000);
			}
		}
		System.out.println("closing");
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
				String old = Thread.currentThread()
					.getName();
				Thread.currentThread()
					.setName("launchpad create " + n);
				try {
					Launchpad lp = builder.create(name);
					lps[n] = lp;
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					l.countDown();
					Thread.currentThread()
						.setName(old);
				}
			});
		}
		l.await();
		return lps;
	}

	private ClusterTopology setup(Launchpad lp) throws Exception {
		RemoteServiceAdmin rsa = lp.waitForService(RemoteServiceAdmin.class, 10_000)
			.get();
		ClusterInformation ci = lp.waitForService(ClusterInformation.class, 10_000)
			.get();
		ClusterTopology ct = new ClusterTopology(rsa, ci, lp.getBundleContext());
		lp.register(ClusterListener.class, ct);

		lp.register(org.osgi.framework.hooks.service.FindHook.class, ct);
		lp.register(ListenerHook.class, ct);

		return ct;
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

	private boolean testBasis(Launchpad lp, int size) {
		Optional<ClusterInformation> ci = lp.waitForService(ClusterInformation.class, 50_000);
		assertThat(ci).isPresent();
		ClusterInformation cinfo = ci.get();
		System.out.println(cinfo.getClusterName());
		System.out.println(cinfo.getKnownMembers());
		Awaitility.await()
			.timeout(30, TimeUnit.SECONDS)
			.until(() -> cinfo.getKnownMembers()
				.size() == size);
		return true;
	}

	private void configure(Launchpad lp, int port, String... peers) throws Exception {
		ConfigurationAdmin ca = lp.waitForService(ConfigurationAdmin.class, 60_000)
			.get();

		update(ca, RSAConstants.TRANSPORT_TLS_PID, "insecure", true);
		update(ca, RSAConstants.DISTRIBUTION_TRANSPORT_PID);
		update(ca, RSAConstants.DISTRIBUTION_PROVIDER_PID, //
			"server.bind.address", "127.0.0.1", //
			"allow.insecure.transports", true, //
			"server.protocols", "TCP;nodelay=true", //
			"nodelay", true //
		);

		updateFact(ca, RSAConstants.CLUSTER_GOSSIP_PID, "SITE", //
			"cluster.name", "SITE", //
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
