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
package biz.aQute.rsa.test.consumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.apache.felix.service.command.annotations.GogoCommand;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import biz.aQute.rsa.test.api.HelloWorld;

@GogoCommand(scope = "hello", function = "hello")
@Component(immediate = true, service = ConsumerImpl.class)
public class ConsumerImpl {

	@Reference(cardinality = ReferenceCardinality.OPTIONAL)
	volatile HelloWorld			hello;
	final ConfigurationAdmin	cm;
	final BundleContext			context;

	@Activate
	public ConsumerImpl(
			@Reference ConfigurationAdmin cm,
			BundleContext context) throws IOException {
		this.cm = cm;
		this.context = context;

		configure(cm, context);
	}

	// {
	// "initial.peers": ["127.0.0.1:9033", "127.0.0.1:9035"],
	// "bind.address": "127.0.0.1",
	// "udp.port": 9033,
	// "tcp.port": 9034,
	// "cluster.name": "DIMC"
	// },
	static void configure(ConfigurationAdmin cm, BundleContext context) throws IOException {
		Hashtable<String, Object> properties = new Hashtable<>();

		List<String> peers = new ArrayList<>();
		for (int i = 1; i < 100; i++) {
			String s = context.getProperty("peer." + i);
			if (s == null)
				break;
			peers.add(s);
		}
		properties.put("cluster.name", "DIMC");
		properties.put("initial.peers", peers);
		properties.put("bind.address", "127.0.0.1");
		properties.put("udp.port", Integer.parseInt(context.getProperty("udp.port")));
		properties.put("tcp.port", Integer.parseInt(context.getProperty("tcp.port")));
		Configuration configuration = cm.getConfiguration("com.paremus.gossip.netty", "?");
		configuration.update(properties);
	}

	@Activate
	public void activate() {
		System.out.println("activate consumer");
	}

	@Deactivate
	public void deactivate() {
		System.out.println("deactivate consumer");
	}

	public String hello(String name) {
		return hello.sayHello(name);
	}
}
