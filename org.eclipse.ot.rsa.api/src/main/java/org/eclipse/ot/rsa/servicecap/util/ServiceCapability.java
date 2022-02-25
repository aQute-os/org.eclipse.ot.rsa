package org.eclipse.ot.rsa.servicecap.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.osgi.annotation.bundle.Attribute;
import org.osgi.annotation.bundle.Capability;

@Capability(namespace = "osgi.service", effective = "active")
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ServiceCapability {
	@Attribute("objectClass")
	Class<?>[] value();
}