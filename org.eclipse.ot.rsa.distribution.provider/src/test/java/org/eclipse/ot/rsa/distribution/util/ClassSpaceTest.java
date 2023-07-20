package org.eclipse.ot.rsa.distribution.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.ot.rsa.distribution.util.ClassSpace.ActualTypeName;
import org.eclipse.ot.rsa.distribution.util.ClassSpace.Proxied;
import org.junit.jupiter.api.Test;

class ClassSpaceTest {

	@ActualTypeName("java.lang.String")
	interface StringProxy extends Proxied {
		int length();

		char charAt(int n);

		boolean matches(StringProxy regex);

		StringProxy substring(int n);
	}

	@Test
	void testSimple() {
		ClassSpace cs = new ClassSpace(ClassSpaceTest.class);

		StringProxy sp = cs.proxy(StringProxy.class, "hello world");
		assertThat(sp.getActual()).isEqualTo("hello world");
		assertThat(sp.length()).isEqualTo(11);
		assertThat(sp.charAt(5)).isEqualTo(' ');
		assertThat(cs.newInstance(StringProxy.class)).isEqualTo("");
		StringProxy regex = cs.proxy(StringProxy.class, "h.*d");
		assertThat(sp.matches(regex)).isTrue();
		regex = cs.proxy(StringProxy.class, "no way");
		assertThat(sp.matches(regex)).isFalse();

		assertThat(sp.substring(0)).isInstanceOf(StringProxy.class);
	}


}
