package com.paremus.deployment.framework.provider;

import org.osgi.framework.launch.Framework;

public class ChildFrameworkEvent {

	private final String name;
	private final Framework framework;
	private final EventType type;

	public static enum EventType { INITIALIZED, MOVED, DESTROYING, DESTROYED }
	
	public ChildFrameworkEvent(EventType type, String name, Framework framework) {
		this.type = type;
		this.name = name;
		this.framework = framework;
	}

	public String getName() {
		return name;
	}

	public Framework getFramework() {
		return framework;
	}

	public EventType getType() {
		return type;
	}
}
