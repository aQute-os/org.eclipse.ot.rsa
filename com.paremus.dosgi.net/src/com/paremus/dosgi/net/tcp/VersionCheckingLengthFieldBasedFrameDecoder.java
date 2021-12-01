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
package com.paremus.dosgi.net.tcp;

import java.nio.ByteOrder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

public class VersionCheckingLengthFieldBasedFrameDecoder extends LengthFieldBasedFrameDecoder {

	public VersionCheckingLengthFieldBasedFrameDecoder(int maxFrameLength, int lengthFieldOffset,
			int lengthFieldLength) {
		super(maxFrameLength, lengthFieldOffset, lengthFieldLength, 0, lengthFieldLength + 1);
	}

	@Override
	protected long getUnadjustedFrameLength(ByteBuf buf, int offset, int length, ByteOrder order) {
		
		short version = buf.getUnsignedByte(offset - 1);
        if(version > 1) {
        	throw new CorruptedFrameException("Unacceptable message version (" + version + ")"); 
        }
		return super.getUnadjustedFrameLength(buf, offset, length, order);
	}

	

}
