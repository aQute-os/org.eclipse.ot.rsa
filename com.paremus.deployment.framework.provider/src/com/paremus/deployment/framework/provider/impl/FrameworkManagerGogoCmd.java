package com.paremus.deployment.framework.provider.impl;

import java.util.List;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.paremus.deployment.framework.provider.ChildFrameworkManager;

@Component(
		name = "com.paremus.deployment.framework.provider.commands",
		immediate = true,
		service = Object.class,
		property = {
			"osgi.command.scope=childfwk",
			"osgi.command.function=list"
		})
public class FrameworkManagerGogoCmd {

	private ChildFrameworkManager manager;

	@Reference
	void setFrameworkManager(ChildFrameworkManager manager) {
		this.manager = manager;
	}
	
	public void list() {
		List<String> ids = manager.listFrameworkIds();
		System.out.printf("%d known child framework(s):%n", ids.size());
		for (String id : ids) {
			System.out.printf(" * %s%n", id);
		}
		System.out.println(" -- END");
	}
}
