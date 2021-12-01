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
package com.paremus.dosgi.net.serialize;

import java.io.IOException;

import io.netty.buffer.ByteBuf;

public interface Serializer {

	void serializeArgs(ByteBuf buffer, Object[] args) throws IOException;

	Object[] deserializeArgs(ByteBuf buffer) throws ClassNotFoundException, IOException;
	
	void serializeReturn(ByteBuf buffer, Object o) throws IOException;
	
	Object deserializeReturn(ByteBuf buffer) throws ClassNotFoundException, IOException;
	
}
