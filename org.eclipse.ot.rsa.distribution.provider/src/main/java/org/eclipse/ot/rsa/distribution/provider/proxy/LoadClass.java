package org.eclipse.ot.rsa.distribution.provider.proxy;

public interface LoadClass {
	Class<?> loadClass(String name) throws ClassNotFoundException;
}
