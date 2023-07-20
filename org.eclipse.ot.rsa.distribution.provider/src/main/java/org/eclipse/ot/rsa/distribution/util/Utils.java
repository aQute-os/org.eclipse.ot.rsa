package org.eclipse.ot.rsa.distribution.util;

public class Utils {
	public interface CallableWithThrowable<T> {
		T call() throws Throwable;
	}

	public static <V> V unchecked(CallableWithThrowable<? extends V> callable) {
		try {
			return callable.call();
		} catch (Throwable t) {
			throw duck(t);
		}
	}


	public static RuntimeException duck(Throwable t) {
		throwsUnchecked(t);
		throw new AssertionError("unreachable");
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void throwsUnchecked(Throwable throwable) throws E {
		throw (E) throwable;
	}

}
