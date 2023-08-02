package org.eclipse.ot.rsa.distribution.util;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Utils {

	static final public File	work	= new File(System.getProperty("user.dir"));
	static final public File	home	= new File(System.getProperty("user.home"));

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

	public static String limit(String message, int limit) {
		int length = message.length();
		if (length < limit)
			return message;

		StringBuilder sb = new StringBuilder();
		sb.append(message, 0, limit / 2)
			.append(" ... ")
			.append(message, length - (limit - limit / 2), length);
		return sb.toString();
	}

	public static File getFile(String file) {
		return getFile(work, file);
	}

	public static File getFile(File base, String file) {
		StringRover rover = new StringRover(file);
		if (rover.startsWith("~/")) {
			rover.increment(2);
			if (!rover.startsWith("~/")) {
				return getFile(home, rover.substring(0));
			}
		}
		if (rover.startsWith("~")) {
			return getFile(home.getParentFile(), rover.substring(1));
		}

		File f = new File(rover.substring(0));
		if (f.isAbsolute()) {
			return f;
		}

		if (base == null) {
			base = work;
		}

		for (f = base.getAbsoluteFile(); !rover.isEmpty();) {
			int n = rover.indexOf('/');
			if (n < 0) {
				n = rover.length();
			}
			if ((n == 0) || ((n == 1) && (rover.charAt(0) == '.'))) {
				// case "" or "."
			} else if ((n == 2) && (rover.charAt(0) == '.') && (rover.charAt(1) == '.')) {
				// case ".."
				File parent = f.getParentFile();
				if (parent != null) {
					f = parent;
				}
			} else {
				String segment = rover.substring(0, n);
				f = new File(f, segment);
			}
			rover.increment(n + 1);
		}

		return f.getAbsoluteFile();
	}

	public static class StringRover implements CharSequence {
		private final String	string;
		private int				offset;

		public StringRover(String string) {
			this.string = requireNonNull(string);
			offset = 0;
		}

		private StringRover(String string, int offset) {
			this.string = string;
			this.offset = offset;
		}

		@Override
		public int length() {
			return string.length() - offset;
		}

		public boolean isEmpty() {
			return string.length() <= offset;
		}

		@Override
		public char charAt(int index) {
			return string.charAt(offset + index);
		}

		public StringRover increment() {
			return increment(1);
		}

		public StringRover increment(int increment) {
			int new_offset = offset + increment;
			if (new_offset <= 0) {
				offset = 0;
			} else {
				int len = string.length();
				offset = (new_offset >= len) ? len : new_offset;
			}
			return this;
		}

		public StringRover reset() {
			offset = 0;
			return this;
		}

		public StringRover duplicate() {
			return new StringRover(string, offset);
		}

		public int indexOf(int ch, int from) {
			int index = string.indexOf(ch, offset + from) - offset;
			return (index < 0) ? -1 : index;
		}

		public int indexOf(int ch) {
			return indexOf(ch, 0);
		}

		public int indexOf(CharSequence str, int from) {
			final int length = length();
			final int size = str.length();
			if (from >= length) {
				return (size == 0) ? length : -1;
			}
			if (from < 0) {
				from = 0;
			}
			if (size == 0) {
				return from;
			}
			final char first = str.charAt(0);
			outer: for (int limit = offset + (length - size), i = offset + from; i <= limit; i++) {
				if (string.charAt(i) == first) {
					final int end = i + size;
					for (int j = i + 1, k = 1; j < end; j++, k++) {
						if (string.charAt(j) != str.charAt(k)) {
							continue outer;
						}
					}
					return i - offset;
				}
			}
			return -1;
		}

		public int indexOf(CharSequence str) {
			return indexOf(str, 0);
		}

		public int lastIndexOf(int ch, int from) {
			int index = string.lastIndexOf(ch, offset + from) - offset;
			return (index < 0) ? -1 : index;
		}

		public int lastIndexOf(int ch) {
			return lastIndexOf(ch, length() - 1);
		}

		public int lastIndexOf(CharSequence str, int from) {
			final int length = length();
			final int size = str.length();
			if (from < 0) {
				return -1;
			}
			final int right = length - size;
			if (from > right) {
				from = right;
			}
			if (size == 0) {
				return from;
			}
			final int end = size - 1;
			final char last = str.charAt(end);
			outer: for (int limit = offset + end, i = limit + from; i >= limit; i--) {
				if (string.charAt(i) == last) {
					final int start = i - end;
					for (int j = start, k = 0; j < i; j++, k++) {
						if (string.charAt(j) != str.charAt(k)) {
							continue outer;
						}
					}
					return start - offset;
				}
			}
			return -1;
		}

		public int lastIndexOf(CharSequence str) {
			return lastIndexOf(str, length());
		}

		public String substring(int start) {
			return string.substring(offset + start);
		}

		public String substring(int start, int end) {
			return string.substring(offset + start, offset + end);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return substring(start, end);
		}

		@Override
		public String toString() {
			return string.substring(offset);
		}

		public boolean startsWith(CharSequence prefix, int from) {
			int size = prefix.length();
			if ((from < 0) || (from > (length() - size))) {
				return false;
			}
			for (int source = offset + from, target = 0; size > 0; size--, source++, target++) {
				if (string.charAt(source) != prefix.charAt(target)) {
					return false;
				}
			}
			return true;
		}

		public boolean startsWith(CharSequence prefix) {
			return startsWith(prefix, 0);
		}

		public boolean endsWith(CharSequence suffix) {
			return startsWith(suffix, length() - suffix.length());
		}
	}

	public static String toString(Object o) {
		return toString(o, Integer.MAX_VALUE);
	}

	public static String toString(Object o, int limit) {

		StringBuilder sb = new StringBuilder();
		toString(sb, o, new HashSet<>());
		return limit(sb.toString(), 300);
	}

	@SuppressWarnings({
		"rawtypes", "unchecked"
	})
	private static void toString(StringBuilder sb, Object o, Set visited) {
		if (o == null) {
			sb.append("null");
			return;
		}
		if (!visited.add(o)) {
			sb.append("<cycle>");
			return;
		}

		Class type = o.getClass();

		if (Iterable.class.isAssignableFrom(type)) {
			sb.append("[");
			String del = "";
			for (Object oo : (Iterable) o) {
				sb.append(del);
				toString(sb, oo, visited);
				del = ",";
			}
			sb.append("]");
			return;
		}
		if (Map.class.isAssignableFrom(type)) {
			sb.append("{");
			String del = "";
			Map map = (Map) o;
			map.forEach((k, v) -> {
				toString(sb, k, visited);
				sb.append(":");
				toString(sb, v, visited);
			});
			sb.append("}");
			return;
		}

		if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
			sb.append(o);
			return;
		}

		if (type == String.class) {
			String s = (String) o;
			sb.append("\"");
			s = limit(s, 100);
			sb.append(escape(s));
			sb.append("\"");
			return;
		}
		if (type.isArray()) {
			if (type == byte[].class) {
				String s = toHex((byte[]) o);
				s = limit(s, 100);
				sb.append(s);
				return;
			}
			sb.append("[");
			String del = "";
			int n = Array.getLength(o);
			for (int i = 0; i < n; i++) {
				Object oo = Array.get(o, i);
				sb.append(del);
				toString(sb, oo, visited);
				del = ",";
			}
			sb.append("]");
			return;
		}

		sb.append(o);
	}

	private static String digits = "0123456789abcdef";

	public static String toHex(byte[] data) {
		StringBuffer buf = new StringBuffer();
		for (int i = 0; i < data.length; i++) {
			int v = data[i] & 0xff;
			buf.append(digits.charAt(v >> 4));
			buf.append(digits.charAt(v & 0xf));
		}
		return buf.toString();
	}

	public static String escape(String s) {
		return s;
	}

	@SuppressWarnings("unchecked")
	public static <T> T printer(Class<T> type, Appendable out) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[] {
			type
		}, (p, m, a) -> {
			out.append(m.getName())
				.append("(");
			String del = "";
			for (Object o : a) {
				String s = Utils.toString(o);
				out.append(del)
					.append(s);
				del = ",";
			}
			out.append(")");
			return null;
		});
	}

}
