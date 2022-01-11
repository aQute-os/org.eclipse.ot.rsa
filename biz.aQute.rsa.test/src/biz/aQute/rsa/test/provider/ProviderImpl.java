/**
 * Copyright (c) 2012 - 2021 Paremus Ltd., Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors:
 * 		Paremus Ltd. - initial API and implementation
 *      Data In Motion
 */
package biz.aQute.rsa.test.provider;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.propertytypes.ExportedService;
import org.slf4j.LoggerFactory;

import biz.aQute.rsa.test.api.HelloWorld;

/**
 * This class is a component that is exported. By using all defaults, we export to all clusters. Additional
 * properties that can be set:
 * <pre>
 * service_exported_configs = "com.paremus.dosgi.net"
 * property = { "com.paremus.dosgi.scope=global", "com.paremus.dosgi.target.clusters=DIMC" }
 * </pre>
 */

@ExportedService(service_exported_interfaces = HelloWorld.class)
@Component(service = HelloWorld.class, immediate = true)
public class ProviderImpl implements HelloWorld {
	final static org.slf4j.Logger log = LoggerFactory.getLogger(ProviderImpl.class);

	@Activate
	public void activate() {
		log.info("start helloworld provider");
	}

	@Deactivate
	public void deactivate() {
		log.info("stop helloworld provider");
	}

	@Override
	public String sayHello(String name) {
		log.info("called with {}", name);
		return String.format("Hello %s", name);
	}
}
