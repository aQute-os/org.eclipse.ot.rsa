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
package io.netty.buffer;

import java.io.DataInput;
import java.io.DataOutput;

import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.CharsetUtil;

public class StringHelper {
    /**
     * Decode a {@link String} in <a href="http://en.wikipedia.org/wiki/UTF-8">UTF-8</a> as
     * written by {@link DataOutput#writeUTF(String)}
     *
     * This method returns the String read from the buffer
     */
    public static String readLengthPrefixedUtf8(ByteBuf buf) {
        if (buf == null) {
            throw new NullPointerException("buf");
        }
        if (buf instanceof AbstractByteBuf) {
            // Fast-Path
            AbstractByteBuf buffer = (AbstractByteBuf) buf;
            
            int readerIndex = buffer.readerIndex;
            int readable = buffer.readableBytes();
            
            if(readable < 2) {
            	throw new IllegalArgumentException(String.format(
                        "minReadableBytes: %d (expected: >= 2)", readable));
            }
            
            int len = 0xFFFF & buffer._getShort(readerIndex);
            readerIndex +=2;
            if(len == 0) {
            	return "";
            }
            
            if(readable < 2 + len) {
            	throw new IllegalArgumentException(String.format(
                        "minWritableBytes: %d (expected: >= %d)", readable, 2 + len));
            }
            
            // Len is worst case, assuming only one-byte encoded characters. This is actually a pretty
            // good estimate most of the time.
            StringBuilder sb = new StringBuilder(len);
            
            // We can use the _get methods as these not need to do any index checks and reference checks.
            // This is possible as we called readableBytes() before.
            for (int i = 0; i < len; i++) {
                byte b = buffer._getByte(readerIndex++);
                if (b < 0x80) {
                    sb.append((char) b);
                } else if(b < 0xE0) {
                	//A 2 byte encoding
                	byte b2 = buffer._getByte(readerIndex++);
                	sb.append((char) (b << 6) + b2 - 0x3080);
                } else {
                	byte b2 = buffer._getByte(readerIndex++);
                	byte b3 = buffer._getByte(readerIndex++);
                	sb.append((char) (b << 12) + (b2 << 6) + b3 - 0xE2080);
                }
            }
            // update the readerIndex without any extra checks for performance reasons
            buffer.readerIndex = readerIndex;
            return sb.toString();
        } else {
            // Maybe we could also check if we can unwrap() to access the wrapped buffer which
            // may be an AbstractByteBuf. But this may be overkill so let us keep it simple for now.
        	int len = buf.readUnsignedShort();
        	int readerIndex = buf.readerIndex();
        	
            String s = buf.toString(readerIndex, len, CharsetUtil.UTF_8);
            buf.readerIndex(readerIndex + len); 
            return s;
        }
    }

    /**
     * Write a length-prefixed UTF-8 string suitable for use in {@link DataInput#readUTF()}
     * @param buf
     * @param seq
     */
    public static void writeLengthPrefixedUtf8(ByteBuf buf, CharSequence seq) {
    	int writerIndex = buf.writerIndex();
    	buf.ensureWritable(2 + 3*seq.length());
    	buf.writerIndex(writerIndex + 2); 
    	int written = ByteBufUtil.writeUtf8(buf, seq);
		buf.setShort(writerIndex, written);
		buf.writerIndex(writerIndex + 2 + written);
    }
}
