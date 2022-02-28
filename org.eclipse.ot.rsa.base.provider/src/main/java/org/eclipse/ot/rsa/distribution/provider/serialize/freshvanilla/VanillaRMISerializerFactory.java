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

import org.eclipse.ot.rsa.distribution.provider.serialize.Serializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.SerializerFactory;
import org.freshvanilla.lang.MetaClasses;
import org.osgi.framework.Bundle;

public class VanillaRMISerializerFactory implements SerializerFactory {

	@Override
	public Serializer create(Bundle classSpace) {
		return new VanillaRMISerializer(new MetaClasses(new MetaClassesClassLoader(
						classSpace)));
	}
}
