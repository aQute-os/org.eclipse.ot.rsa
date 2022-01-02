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
package com.paremus.dosgi.net.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

public class ServerTestServiceImpl implements ServerTestService {

	private final CharSequence delegate;
	
	public ServerTestServiceImpl(CharSequence delegate) {
		this.delegate = delegate;
	}

	@Override
	public int length() {
		return delegate.length();
	}

	@Override
	public char charAt(int index) {
		return delegate.charAt(index);
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return delegate.subSequence(start, end);
	}

	@Override
	public Future<CharSequence> subSequence(Promise<Integer> p, CompletionStage<Integer> cs) {
		CompletableFuture<Integer> cf = new CompletableFuture<Integer>();
		
		p.then(x -> {cf.complete(p.getValue()); return null;}, 
				x -> cf.completeExceptionally(p.getFailure()));
		
		return cf.thenCombine(cs, (start,end) -> subSequence(start, end));
	}

	@Override
	public PushStream<Character> streamOfCharacters(int failAfter) {
		PushStreamProvider provider = new PushStreamProvider();
		SimplePushEventSource<Character> source = provider.createSimpleEventSource(Character.class);
		
		source.connectPromise().onResolve(() ->
				new Thread(() -> {
					delegate.chars()
						.limit(failAfter)
						.mapToObj(Character::toChars)
						.map(c -> c[0])
						.map(this::slow)
						.forEach(source::publish);
					slow(' ');
					
					if(delegate.length() > failAfter) {
						source.error(new ArrayIndexOutOfBoundsException("Failed after " + failAfter));
					} else {
						source.endOfStream();
					}
					slow(' ');
					source.close();
				}).start());
		
		return provider.createStream(source);
	}
	
	@Override
	public PushEventSource<Character> reusableStreamOfCharacters(int failAfter) {
		return aec -> {
			PushStream<Character> stream = streamOfCharacters(failAfter);
			stream.forEachEvent(aec);
			return stream;
		};
	}

	private Character slow(Character c) {
		try {
			Thread.sleep(200);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return c;
	}
}
