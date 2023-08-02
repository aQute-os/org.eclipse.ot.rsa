package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class RSAChannel implements AutoCloseable {
	final ByteChannel			channel;
	final Serializer			serializer;
	final List<AutoCloseable>	onClose	= new ArrayList<>();
	final AtomicInteger			counter	= new AtomicInteger();
	final Protocol				protocol;

	private static void write(Msg msg, ByteChannel channel) {
		try {
			ByteBuf b = msg.copy();
			System.out.println("write " + Msg.report(b));
			ByteBuffer nioBuffer = b.nioBuffer();
			do {
				int n = channel.write(nioBuffer);
			} while (nioBuffer.hasRemaining());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public RSAChannel(ByteChannel channel, Serializer serializer) {
		this.channel = channel;
		this.serializer = serializer;
		protocol = new Protocol(serializer, msg -> {
			write(msg, channel);
		});
	}

	public Server server(Client callbacks) {
		onClose(readloop(msg -> protocol.dispatch(msg, callbacks)));
		return protocol;
	}

	public Client client(Server callbacks) {
		onClose(readloop(msg -> protocol.dispatch(msg, callbacks)));
		return protocol;
	}

	@SuppressWarnings("unchecked")
	static <T> T[] merge(T primary, T... aux) {
		return Stream.concat(Stream.of(primary), Stream.of(aux))
			.toArray(n -> (T[]) Array.newInstance(primary.getClass(), n));
	}

	AutoCloseable readloop(Consumer<Msg> write) {

		Thread thread = new Thread() {
			@Override
			public void run() {
				try {
					while (!isInterrupted())
						try {
							ByteBuffer header = ByteBuffer.allocate(4);
							System.out.println("waiting for length");
							waitFor(header, 4);
							header.flip();
							int version = header.get(0);
							if (version != 1 && version != 2) {
								fatal("invalid version");
							}
							int size = 0;
							for (int i = 1; i < 4; i++) {
								int v = 0xFF & header.get(i);
								size = size * 256 + v;
							}
							ByteBuffer buffer = ByteBuffer.allocate(size + 4);
							buffer.put(header);

							System.out.println("waiting for " + size);
							waitFor(buffer, size + 4);
							buffer.flip();

							ByteBuf copy = Unpooled.wrappedBuffer(buffer);
							System.out.println("received " + copy + "\n" + Msg.report(copy));
							Msg msg = new Msg(protocol, copy);
							System.out.println("got " + msg);
							write.accept(msg);
							counter.incrementAndGet();
						} catch (InterruptedException e) {
							System.out.println("Interrupted");
							return;
						} catch (Exception e) {
							e.printStackTrace();
						}
				} finally {
					System.out.println("Exiting read loop");
				}
			}

			private void fatal(String string) {
				System.err.println("Fatal error " + string);
			}
		};
		thread.start();
		return () -> {
			channel.close();
			thread.interrupt();
			thread.join();
		};
	}

	private void waitFor(ByteBuffer buffer, int i) throws IOException, InterruptedException {
		while (true) {
			channel.read(buffer);
			if (buffer.position() >= i)
				return;

			Thread.sleep(50);
		}
	}

	void onClose(AutoCloseable object) {
		onClose.add(object);
	}

	@Override
	public void close() throws Exception {
		onClose.forEach(c -> {
			try {
				c.close();
			} catch (Exception e) {}
		});
	}

	public void await(int n) throws InterruptedException, TimeoutException {
		long deadline = System.nanoTime();
		while (counter.get() < n) {
			if (System.nanoTime() - deadline > TimeUnit.SECONDS.toNanos(5))
				throw new TimeoutException();
			Thread.sleep(10);
		}
	}

}
