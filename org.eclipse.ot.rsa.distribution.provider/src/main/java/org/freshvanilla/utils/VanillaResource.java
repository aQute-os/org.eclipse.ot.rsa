/*
 Copyright 2008-2011 the original author or authors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.freshvanilla.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VanillaResource implements SimpleResource {

	private final String		_name;
	private volatile boolean	_closed	= false;

	public VanillaResource(String name) {
		_name = name;
	}

	@Override
	public String getName() {
		return _name;
	}

	@Override
	public boolean isClosed() {
		return _closed;
	}

	@Override
	public void close() {
		_closed = true;
	}

	public void checkedClosed() throws IllegalStateException {
		if (_closed) {
			throw new IllegalStateException(_name + " closed!");
		}
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			if (!_closed) {
				close();
			}
		} finally {
			super.finalize();
		}
	}

	protected Logger getLog() {
		return LoggerFactory.getLogger(getClass());
	}

}
