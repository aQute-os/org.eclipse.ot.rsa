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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.crypto.Mac;

class VerifiableOutputStream extends FilterOutputStream {
	private static final int BLOCK_SIZE = 1024;
	private static final byte[] PADDING = new byte[BLOCK_SIZE];
	
	final Mac mac;
	int pos;
	boolean closed;
	
	public VerifiableOutputStream(OutputStream os, Mac mac) {
		super(os);
		this.mac = mac;
	}

	@Override
	public synchronized void write(int b) throws IOException {
		if(closed) throw new IOException("Stream closed");
		
		if(pos == 0) {
			doWrite(0);
		}
		
		doWrite(b);
		pos++;
		if(pos == 128) {
			digestCompleteBlock();
		}
	}

	private void doWrite(int b) throws IOException {
		mac.update((byte) b);
		out.write(b);
	}

	private void doWrite(byte[] b, int off, int length) throws IOException {
		mac.update(b, off, length);
		out.write(b, off, length);
	}
	
	private void digestCompleteBlock() throws IOException {
		doWrite(1);
		out.write(mac.doFinal());
		pos = 0;
	}

	@Override
	public synchronized void flush() throws IOException {
		if(pos != 0) {
			//Pad with Zeros
			int padding = BLOCK_SIZE - pos;
			doWrite(PADDING, 0, padding);
			doWrite(2);
			doWrite(padding >> 8);
			doWrite(padding);
			out.write(mac.doFinal());
			super.flush();
		}
		pos = 0;
	}

	@Override
	public synchronized void write(byte[] b, int off, int len) throws IOException {
		if(closed) throw new IOException("Stream closed");
		
		if(len == 0)
			return;
		if(len < 0) 
			throw new IndexOutOfBoundsException("Length cannot be negative");
		if((off + len) > b.length)
			throw new IndexOutOfBoundsException("Not enough space in the array");
		if(off < 0) 
			throw new IndexOutOfBoundsException("Offset cannot be negative");
		
		while(len > 0) {
			if(pos == 0) {
				doWrite(0);
			}
			
			int toWrite = Math.min(len, BLOCK_SIZE - pos);
			
			doWrite(b, off, toWrite);
			
			len -= toWrite;
			off += toWrite;
			pos += toWrite;
			
			if(pos == BLOCK_SIZE) {
				digestCompleteBlock();
			}
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if(pos != 0){
			flush();
		}
		closed = true;
		super.close();
	}
}