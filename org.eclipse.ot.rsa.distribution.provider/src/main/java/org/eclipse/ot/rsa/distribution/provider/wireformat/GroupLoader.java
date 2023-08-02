package org.eclipse.ot.rsa.distribution.provider.wireformat;

import java.util.Arrays;

@SuppressWarnings("rawtypes")
public class GroupLoader extends ClassLoader {

	final Class[] interfaces;

	public GroupLoader(Class<?>... interfaces) {
		super(GroupLoader.class.getClassLoader());
		this.interfaces = interfaces;
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException {
		for (Class c : interfaces)
			try {
				return c.getClassLoader()
					.loadClass(name);
			} catch (ClassNotFoundException e) {}
		throw new ClassNotFoundException(name + " in " + Arrays.toString(interfaces));
	}

}
