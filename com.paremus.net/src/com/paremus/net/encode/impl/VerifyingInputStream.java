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
package com.paremus.net.encode.impl;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamCorruptedException;
import java.util.Arrays;

import javax.crypto.Mac;

class VerifyingInputStream extends FilterInputStream {
	private final byte[] block = new byte[1024];
	private final byte[] digest;

	private final byte[] paddingRead = new byte[2];
	
	private final Mac mac;
	
	int pos;
	int length;
	
	boolean endOfStream;
	
	public VerifyingInputStream(InputStream is, Mac mac) {
		super(is);
		this.mac = mac;
		digest = new byte[mac.getMacLength()];
	}

	@Override
	public synchronized int read() throws IOException {
		ensureBytes();
		
		if(endOfStream)
			return -1;
		
		return 0xFF & block[pos++];
	}

	@Override
	public synchronized int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public synchronized int read(byte[] b, int off, int len) throws IOException {
		if(len == 0)
			return 0;
		if(len < 0) 
			throw new IndexOutOfBoundsException("Length cannot be negative");
		if((off + len) > b.length)
			throw new IndexOutOfBoundsException("Not enough space in the array");
		if(off < 0) 
			throw new IndexOutOfBoundsException("Offset cannot be negative");
		
		ensureBytes();
		
		if(endOfStream)
			return -1;
		
		int toRead = Math.min(len, length - pos);
		
		System.arraycopy(block, pos, b, off, toRead);
		
		pos += toRead;
		
		return toRead;
	}

	@Override
	public synchronized long skip(long n) throws IOException {
		ensureBytes();
		
		if(endOfStream)
			return 0;
		
		long skipped = Math.min(length - pos, n);
		
		pos += skipped;
		
		return skipped;
	}

	private void ensureBytes() throws IOException {
		if(endOfStream) return;
		
		if(pos == length) {
			try {
				switch(super.read()) {
					case 0  : mac.update((byte) 0); break;
					case -1 : endOfStream = true; return;
					default : throw new StreamCorruptedException("Invalid data block header");
				}
				
				readFully(block);
				mac.update(block);
				
				int digestHeader = super.read();
				switch(digestHeader) {
					case 1  : {
						mac.update((byte) 1);
						length = block.length; break;
					}
					case 2  : {
						mac.update((byte) 2);
						readFully(paddingRead);
						mac.update(paddingRead);
						int padding = ((0xFF & paddingRead[0]) << 8) + (0xFF & paddingRead[1]);
						length = block.length - padding; 
						break;
					}
					case -1 : throw new EOFException("The stream unexpectedly finished");
					default : throw new StreamCorruptedException("Invalid verification block header");
					
				}
				
				readFully(digest);
				pos = 0;
				
				if(!Arrays.equals(digest, mac.doFinal())) {
					length = 0;
					throw new StreamCorruptedException("The Stream data is invalid");
				}
			} finally {
				mac.reset();
			}
		}
	}

	private void readFully(byte[] bytes) throws IOException, EOFException {
		int totalRead = 0;
		while(totalRead < bytes.length) {
			int read = super.read(bytes, totalRead, bytes.length - totalRead);
			if(read == -1) throw new EOFException("The stream unexpectedly finished");
			totalRead += read;
		}
	}

	@Override
	public synchronized int available() throws IOException {
		return endOfStream ? 0 : length - pos;
	}

	@Override
	public synchronized void close() throws IOException {
		endOfStream = true;
		super.close();
	}
}