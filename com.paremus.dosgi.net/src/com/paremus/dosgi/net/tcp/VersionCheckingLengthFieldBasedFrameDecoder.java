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

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

public class VersionCheckingLengthFieldBasedFrameDecoder extends ByteToMessageDecoder {

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception {
		while (buf.readableBytes() > 4) {
			final int offset = buf.readerIndex();
			final short version = buf.getUnsignedByte(offset);
	        if(version > 2) {
	        	throw new CorruptedFrameException("Unacceptable message version (" + version + ")"); 
	        }
	        final int length = buf.getUnsignedMedium(offset + 1);
	        
	        if(!buf.isReadable(length + 4)) {
	        	break;
	        }
	        
        	out.add(buf.retainedSlice(offset + 4, length));
        	buf.skipBytes(length + 4); 
        }
	}
}
