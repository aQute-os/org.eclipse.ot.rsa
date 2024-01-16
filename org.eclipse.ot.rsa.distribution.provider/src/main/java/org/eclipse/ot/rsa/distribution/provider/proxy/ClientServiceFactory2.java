package org.eclipse.ot.rsa.distribution.provider.proxy;

import java.lang.reflect.Proxy;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;

public class ClientServiceFactory2 implements ServiceFactory<Object> {
	final RSAImportedContext imported;

	public ClientServiceFactory2(RSAImportedContext imported) {
		this.imported = imported;
	}

	@Override
	public Object getService(Bundle bundle, ServiceRegistration<Object> registration) {
		try {
			RSAProxyContext context = imported.create(bundle::loadClass);
			return context.proxy();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new ServiceException("failed to create proxy", ServiceException.REMOTE, e);
		}
	}

	@Override
	public void ungetService(Bundle bundle, ServiceRegistration<Object> registration, Object service) {
		RSAProxyContext context = (RSAProxyContext) Proxy.getInvocationHandler(service);
		context.close();
	}

}
