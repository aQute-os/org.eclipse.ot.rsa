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
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

@Sharable
public class LengthFieldPopulator extends MessageToMessageEncoder<ByteBuf> {

	@Override
	protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
		//We have a four byte header
		final int pos = msg.readerIndex();
		final byte version = msg.getByte(pos);

		if(version > 1) {
			throw new IllegalArgumentException(
					"The version (" + version + ") of the message is not supported");
		}
		
		final int length = msg.readableBytes() - 4;
		
        if (length < 0) {
            throw new IllegalArgumentException(
                    "Adjusted frame length (" + length + ") is less than zero");
        } else if (length >= 16777216) {
            throw new IllegalArgumentException(
                    "length does not fit into a medium integer: " + length);
        }
        
        msg.setMedium(pos + 1, length);
       
        out.add(msg.retain());
	}

}
