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
