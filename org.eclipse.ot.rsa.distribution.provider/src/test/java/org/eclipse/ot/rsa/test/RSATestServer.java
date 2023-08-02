package org.eclipse.ot.rsa.test;

import static io.netty.util.ResourceLeakDetector.Level.PARANOID;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.eclipse.ot.rsa.distribution.config.TransportConfig;
import org.eclipse.ot.rsa.distribution.provider.client.ClientConnectionManager;
import org.eclipse.ot.rsa.distribution.provider.impl.ImportRegistrationImpl;
import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.java.JavaSerializer;
import org.eclipse.ot.rsa.distribution.provider.server.RemotingProvider;
import org.eclipse.ot.rsa.distribution.provider.server.ServerConnectionManager;
import org.eclipse.ot.rsa.distribution.provider.server.ServiceInvoker;
import org.eclipse.ot.rsa.distribution.provider.test.TestResourceLeakDetector;
import org.eclipse.ot.rsa.distribution.provider.wireformat.MethodIndexes;
import org.eclipse.ot.rsa.distribution.provider.wireformat.RSAChannel;
import org.eclipse.ot.rsa.distribution.util.Utils;
import org.eclipse.ot.rsa.tls.netty.provider.tls.NettyTLS;
import org.mockito.Mockito;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.util.converter.Converters;
import org.slf4j.LoggerFactory;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ResourceLeakDetector;

public class RSATestServer implements AutoCloseable {

	public static class Builder {
		final Map<String, Object>	properties		= new HashMap<>();
		Function<URI, ByteChannel>	channelFunction	= RSATestServer::plain;
		NettyTLS					tls				= Mockito.mock(NettyTLS.class);
		Serializer					serializer		= new JavaSerializer();
		boolean						ssl;
		StringBuilder				name			= new StringBuilder();

		public Builder(String name) {
			this.name.append(name);
			properties.put("server.bind.address", "127.0.0.1");
		}

		public Builder set(String key, Object value) {
			properties.put(key, value);
			return this;
		}

		public Builder tcp() {
			name.append("-tcp");
			channelFunction = RSATestServer::plain;
			return this;
		}

		public Builder ssl(boolean auth) {
			name.append("-ssl");
			if (auth) {
				name.append("-auth");
			}
			SSLChannel c = new SSLChannel("test-resources/fabric.keystore", "paremus".toCharArray(),
				"test-resources/fabric.truststore", "paremus".toCharArray());

			Mockito.when(tls.hasCertificate())
				.thenReturn(true);

			Mockito.when(tls.getTLSServerHandler())
				.then(i -> c.getServer());

			channelFunction = c.getClientChannel(auth);
			return this;
		}

		public Builder serializer(Serializer serializer) {
			name.append("-")
				.append(serializer);
			this.serializer = serializer;
			return this;
		}

		public RSATestServer build() {
			TransportConfig config = Converters.standardConverter()
				.convert(properties)
				.to(TransportConfig.class);
			return new RSATestServer(name.length() == 0 ? "test" : name.toString(), config, tls, serializer,
				channelFunction);
		}

	}

	final NioEventLoopGroup				ioWorker	= new NioEventLoopGroup(1);
	final DefaultEventLoop				worker		= new DefaultEventLoop(Executors.newSingleThreadExecutor());
	final HashedWheelTimer				timer		= new HashedWheelTimer();
	final ServerConnectionManager		serviceConnectionManager;
	final RemotingProvider				remotingProvider;
	final Serializer					serializer;
	final Function<URI, ByteChannel>	channelFactory;
	final String						name;
	private ClientConnectionManager		clientConnectionManager;

	public RSATestServer(String name, TransportConfig config, NettyTLS tls, Serializer serializer,
		Function<URI, ByteChannel> channelFactory) {
		this.name = name;
		this.channelFactory = channelFactory;
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("io.netty.leakDetection.maxRecords", "100");
		System.setProperty("io.netty.customResourceLeakDetector", TestResourceLeakDetector.class.getName());
		TestResourceLeakDetector.addResourceTypeToIgnore(HashedWheelTimer.class);

		ResourceLeakDetector.setLevel(PARANOID);
		LoggerFactory.getLogger(getClass())
			.info("Beginning test {}", name);

		this.serviceConnectionManager = new ServerConnectionManager(config, tls, PooledByteBufAllocator.DEFAULT,
			ioWorker, timer);
		this.remotingProvider = serviceConnectionManager.getConfiguredProviders()
			.get(0);
		this.serializer = Mockito.spy(serializer);
		this.clientConnectionManager = new ClientConnectionManager(Converters.standardConverter()
			.convert(config)
			.to(TransportConfig.class), tls, PooledByteBufAllocator.DEFAULT, ioWorker, worker, timer);

	}

	public class Reg<T> implements AutoCloseable {
		final Collection<URI>	uris;
		public final UUID		uuid;
		final MethodIndexes		mi;
		final T					mocked;

		public Reg(UUID uuid, ServiceInvoker invoker, T mocked, MethodIndexes mi) {
			this.uuid = uuid;
			this.mocked = mocked;
			this.mi = mi;
			uris = remotingProvider.registerService(uuid, invoker);
		}

		public URI[] getEndpoints() {
			return uris.stream()
				.toArray(URI[]::new);
		}

		public int getIndex(String name, Class<?>... parameters) {
			return mi.getIndex(name, parameters);
		}

		@Override
		public void close() throws Exception {
			remotingProvider.unregisterService(uuid);
		}

		public UUID getUUID() {
			return uuid;
		}

		Collection<URI> getURIs() {
			return uris;
		}

		public T getService() {
			return mocked;
		}
	}

	public <T> Reg<T> exported(T service, Class<T> primary, Class<?>... aux) {
		UUID uuid = UUID.randomUUID();
		T mocked = Mockito.spy(service);

		MethodIndexes mi = new MethodIndexes(primary, aux);
		ServiceInvoker invoker = new ServiceInvoker(remotingProvider, uuid, serializer, mocked, mi.getMappings(),
			worker, timer);

		return new Reg<>(uuid, invoker, mocked, mi);
	}

	public RSAChannel getChannel(URI uri) {
		ByteChannel channel = channelFactory.apply(uri);
		return new RSAChannel(channel, serializer);
	}

	@Override
	public void close() throws Exception {

	}

	static ByteChannel plain(URI uri) {
		try {
			SocketChannel sc = SocketChannel.open();
			sc.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
			sc.configureBlocking(false);
			return sc;
		} catch (Exception e) {
			throw Utils.duck(e);
		}
	}

	@Override
	public String toString() {
		return name;
	}

	public Channel getChannelFor(URI uri, UUID uuid) {
		// TODO Auto-generated method stub
		return null;
	}

	public ImportRegistration imported(URI uri, UUID uuid) {
		ImportRegistrationImpl ir = Mockito.mock(ImportRegistrationImpl.class);
		Channel ch = getChannelFor(uri, uuid);
		Mockito.when(ir.getChannel())
			.thenReturn(ch);
		clientConnectionManager.addImportRegistration(ir);
		return ir;
	}
}
