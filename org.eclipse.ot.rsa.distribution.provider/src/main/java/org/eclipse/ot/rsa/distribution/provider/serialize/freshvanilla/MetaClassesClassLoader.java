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
package org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla;

import org.osgi.framework.Bundle;

public class MetaClassesClassLoader extends ClassLoader {

	private static final class BundleToClassLoader extends ClassLoader {
		private final Bundle classSpace;

		public BundleToClassLoader(Bundle classSpace) {
			this.classSpace = classSpace;
		}

		@Override
		protected Class<?> findClass(String className) throws ClassNotFoundException {
			return classSpace.loadClass(className);
		}
	}


	public MetaClassesClassLoader(Bundle classSpace) {
		super(new BundleToClassLoader(classSpace));
	}

	@Override
	protected Class<?> findClass(String className) throws ClassNotFoundException {
		return MetaClassesClassLoader.class.getClassLoader().loadClass(className);
	}
}
