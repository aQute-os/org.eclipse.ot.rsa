package org.eclipse.ot.rsa.distribution.provider.wireformat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.ot.rsa.distribution.provider.serialize.java.JavaSerializer;
import org.junit.jupiter.api.Test;

class MsgTest {
	UUID uuid = new UUID(0xAA, 0xBB);

	@Test
	void test() {

		testOutput(p -> p.callWithoutReturn(uuid, 1000, 1, false),
			"callWithoutReturn(00000000-0000-00aa-0000-0000000000bb,1000,1,[false])");
		testOutput(p -> p.callWithReturn(uuid, 1000, 1, true),
			"callWithReturn(00000000-0000-00aa-0000-0000000000bb,1000,1,[true])");

		testOutput(p -> p.asyncMethodParamClose(uuid, 1000, 5),
			"asyncMethodParamClose(00000000-0000-00aa-0000-0000000000bb,1000,5)");

		testOutput(p -> p.cancel(uuid, 1000, false), "cancel(00000000-0000-00aa-0000-0000000000bb,1000,false)");

	}

	void testOutput(Consumer<Protocol> c, String expected) {
		AtomicReference<Msg> value = new AtomicReference<>();
		Protocol p = new Protocol(new JavaSerializer(), value::set);
		c.accept(p);

		String s = value.get()
			.toString();
		assertThat(s).isEqualTo(expected);

	}

}
