package com.paremus.deployment.framework.provider;

import java.util.List;
import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;

@ProviderType
public interface ChildFrameworkManager {
	
	/**
	 * Get a snapshot of the known framework IDs.
	 * @return
	 */
	List<String> listFrameworkIds();

	/**
	 * Gets an existing framework from the manager
	 * @param id
	 * @return The existing framework, or <code>null</code> if no framework exists for this id
	 */
	Framework getFramework(String id);
	
	/**
	 * Creates a framework for the given id
	 * @param id
	 * @param override properties for configuring the framework
	 * @return A new, empty, initialized framework. Note that the framework will not be started.
	 * 
	 * @throws BundleException if there was an error initializing the framework
	 * @throws IllegalStateException if there was already a framework registered with the supplied id
	 */
	Framework createFramework(String id, Map<String, String> props) throws IllegalStateException, BundleException;
	
	/**
	 * Destroys the framework with the supplied id
	 * @param id
	 */
	void dispose(String id);

	Framework renameFramework(String currentId, String newId);
}
