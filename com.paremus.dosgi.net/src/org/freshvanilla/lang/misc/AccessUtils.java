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

package org.freshvanilla.lang.misc;

import java.lang.reflect.Field;

public class AccessUtils {
    static final Accessor delegate;

    private AccessUtils() {
        // not used
    }

    static {
    	Accessor toUse;
        try {
            toUse = new Unsafe();
        }
        catch (Throwable t) {
        	if (t instanceof OutOfMemoryError) {
        		throw t;
        	}
        	toUse = new SafeAccessor();
        }
        delegate = toUse;
    }

    public static <T> T newInstance(Class<T> clazz) throws InstantiationException {
        return (T)delegate.newInstance(clazz);
    }

    @SuppressWarnings("rawtypes")
	public static FieldAccessor getFieldAccessor(Field field) {
        return delegate.getFieldAccessor(field);
    }

	public static boolean isSafe() {
		return delegate instanceof SafeAccessor;
	}
}
