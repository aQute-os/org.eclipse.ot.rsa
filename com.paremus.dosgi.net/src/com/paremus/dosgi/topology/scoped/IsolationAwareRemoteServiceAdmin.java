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
package com.paremus.dosgi.topology.scoped;

import java.util.Collection;
import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.service.remoteserviceadmin.ImportReference;
import org.osgi.service.remoteserviceadmin.ImportRegistration;
import org.osgi.service.remoteserviceadmin.RemoteServiceAdmin;

@ProviderType
public interface IsolationAwareRemoteServiceAdmin extends RemoteServiceAdmin {

	ImportRegistration importService(Framework framework, EndpointDescription e);

	Collection<ExportRegistration> exportService(Framework framework, ServiceReference<?> ref, Map<String, ?> props);

	Collection<ExportReference> getExportedServices(Framework framework);

	Collection<ImportReference> getImportedEndpoints(Framework framework);
	
	Collection<ExportReference> getAllExportedServices();
	
	Collection<ImportReference> getAllImportedEndpoints();
}
