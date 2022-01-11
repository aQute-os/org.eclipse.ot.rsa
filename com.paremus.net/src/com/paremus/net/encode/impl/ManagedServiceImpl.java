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
package com.paremus.net.encode.impl;

import java.util.Dictionary;
import java.util.UUID;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.util.converter.Converter;
import org.osgi.util.converter.Converters;


import com.paremus.net.encode.EncodingSchemeFactory;

public class ManagedServiceImpl implements ManagedService {
	private final Converter converter = Converters.standardConverter();

	private final UUID frameworkUUID;
	
	private final BundleContext context;

	private ServiceRegistration<EncodingSchemeFactory> reg;
	
	public ManagedServiceImpl(UUID frameworkUUID, BundleContext context) {
		this.frameworkUUID = frameworkUUID;
		this.context = context;
	}
	
	@Override
	public void updated(Dictionary<String, ?> properties)
			throws ConfigurationException {
		
		ServiceRegistration<EncodingSchemeFactory> oldReg = null;
		try {
			if(properties == null) {
				synchronized (frameworkUUID) {
					oldReg = reg;
					reg = null;
				}
				return;
			}
			
			ServiceRegistration<EncodingSchemeFactory> newReg;
			Config config = properties == null ? null : converter.convert(properties).to(Config.class);
			newReg = context.registerService(EncodingSchemeFactory.class, new EncodingSchemeFactoryImpl(config), null);
			
			synchronized (frameworkUUID) {
				oldReg = reg;
				reg = newReg;
			}
		} finally {
			if(oldReg != null) {
				oldReg.unregister();
			}
		}
	}
	

	public void destroy() {
		ServiceRegistration<EncodingSchemeFactory> oldReg;
		synchronized (frameworkUUID) {
			oldReg = reg;
			reg = null;
		}
		if(oldReg != null) {
			oldReg.unregister();
		}
	}
}
