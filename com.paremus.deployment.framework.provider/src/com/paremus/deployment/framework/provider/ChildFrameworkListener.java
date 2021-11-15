package com.paremus.deployment.framework.provider;

import org.osgi.annotation.versioning.ConsumerType;

@ConsumerType
public interface ChildFrameworkListener {
	
	public void childFrameworkEvent(ChildFrameworkEvent event);

}
