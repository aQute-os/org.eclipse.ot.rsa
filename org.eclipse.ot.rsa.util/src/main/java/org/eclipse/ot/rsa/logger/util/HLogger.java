package org.eclipse.ot.rsa.logger.util;

import java.lang.reflect.Array;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Formattable;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface HLogger {

	HLogger child(Consumer<Formatter> id);

	Locale locale();

	default HLogger child(Supplier<String> id) {
		return child(f -> f.format("%s", id.get()));
	}

	default HLogger child(Object object) {
		return child(f -> f.format("%s", object));
	}

	Logger logger();

	Consumer<Formatter> id();

	static HLogger root(String name) {
		return new LoggerBuilder(LoggerFactory.getLogger(name)).root();
	}

	static HLogger root(Class<?> name) {
		return new LoggerBuilder(LoggerFactory.getLogger(name)).root();
	}

	static HLogger root(Object name) {
		return new LoggerBuilder(LoggerFactory.getLogger(name.getClass())).root();
	}

	class ChildLogger implements HLogger {
		final Logger									logger;
		final Locale									locale;
		final Consumer<Formatter>						id;
		final boolean									debug;
		final boolean									info;
		final Map<Class<?>, Function<Object, String>>	hooks	= new HashMap<>();

		ChildLogger(Logger logger, Locale locale, Consumer<Formatter> id) {
			this.logger = logger;
			this.locale = locale;
			this.debug = logger.isDebugEnabled();
			this.info = logger.isInfoEnabled();
			this.id = id;
		}

		@Override
		public HLogger child(Consumer<Formatter> id) {
			return new ChildLogger(logger, locale, combine(this.id, id));
		}

		private Consumer<Formatter> combine(Consumer<Formatter> a, Consumer<Formatter> b) {
			if (a == null)
				return b;

			return (f) -> {
				a.accept(f);
				b.accept(f);
			};
		}

		@Override
		public Logger logger() {
			return logger;
		}

		@Override
		public Consumer<Formatter> id() {
			return id;
		}

		@Override
		public HLogger debug(String format, Object... args) {
			if (debug) {
				logger.debug(format(format, args));
			}
			return this;
		}

		@Override
		public HLogger info(String format, Object... args) {
			if (info) {
				logger.info(format(format, args));
			}
			return this;
		}

		@Override
		public Locale locale() {
			return locale;
		}

		@Override
		public boolean isDebug() {
			return debug;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> void register(Class<T> class1, Function<T, String> object) {
			hooks.put(class1, (Function<Object, String>) object);
		}

		@Override
		public String hooked(Object o) {
			return hooks.entrySet()
				.stream()
				.filter(e -> e.getKey()
					.isInstance(o))
				.map(Map.Entry::getValue)
				.findAny()
				.map(f -> f.apply(o))
				.orElse(null);
		}

	}

	class LoggerBuilder {
		Logger	logger;
		Locale	locale;

		LoggerBuilder(Logger logger) {
			this.logger = logger;
		}

		public LoggerBuilder locale(Locale locale) {
			this.locale = locale;
			return this;
		}

		public HLogger root() {
			return new ChildLogger(logger, locale == null ? Locale.US : locale, null);
		}
	}

	default String format(String format, Object... args) {

		try (Formatter f = new Formatter(locale())) {
			id().accept(f);
			if (args == null || args.length == 0) {
				f.format(format);
			} else {
				List<Object> notused = new ArrayList<>();
				Formattable[] fs;
				if (format.endsWith("...")) {
					StringBuilder sb = new StringBuilder(format);
					sb.delete(format.length() - 3, format.length());
					format = sb.append("%")
						.append(args.length)
						.append("$s")
						.toString();
					fs = new Formattable[args.length + 1];
					fs[args.length] = (formatter, flags, width, precision) -> {
						String del = "";
						for (Object o : notused) {
							if (o != null) {
								formatter.format("%s%s", del, 0);
								del = ",";
							}
						}
					};
				} else {
					fs = new Formattable[args.length];
				}

				for (int i = 0; i < args.length; i++) {
					int n = i;
					notused.add(args[n]);
					if (isDebug() && args[n] instanceof Throwable) {
						Throwable t = (Throwable) args[n];
						t.printStackTrace();
					}
					fs[i] = (formatter, flags, width, precision) -> {
						Object o = args[n];
						notused.set(n, null);
						format(o, formatter, flags, width, precision);
					};
				}
				f.format(format, (Object[]) fs);
			}
			return f.toString();
		} catch (Exception e) {
			logger().error("coding error {}", e, e);
			return format + " " + e.toString();
		}
	}

	boolean isDebug();

	@SuppressWarnings({
		"rawtypes", "unchecked"
	})
	default void format(Object o, Formatter formatter, int flags, int width, int precision) {
		if (o == null) {
			formatter.format("null");
			return;
		}
		String hooked = hooked(o);
		if (hooked != null) {
			formatter.format("%s", hooked);
			return;
		}

		if (o instanceof Iterable) {
			formatter.format("[");
			String del = "";
			for (Object oo : ((Iterable) o)) {
				format(oo, formatter, 0, 0, 0);
				formatter.format(del);
				del = ",";
			}
			formatter.format("]");
			return;
		}
		if (o.getClass()
			.isArray()) {
			int l = Array.getLength(o);
			String del = "";
			formatter.format("[");
			for (int i = 0; i < l; i++) {
				Object oo = Array.get(o, i);
				format(oo, formatter, 0, 0, 0);
				formatter.format(del);
				del = ",";
			}
			formatter.format("]");
			return;
		}
		if (o instanceof Map) {
			formatter.format("{");
			String del = "";
			Map<Object, Object> map = (Map) o;
			for (Map.Entry<Object, Object> oo : map.entrySet()) {
				format(oo.getKey(), formatter, 0, 0, 0);
				formatter.format("=");
				format(oo.getValue(), formatter, 0, 0, 0);
				formatter.format(del);
				del = ",";
			}
			formatter.format("}");
			return;
		}
		if (o instanceof Socket) {
			Socket s = (Socket) o;
			format(s.getLocalSocketAddress(), formatter);
			formatter.format(">");
			format(s.getRemoteSocketAddress(), formatter);
			return;
		}
		if (o instanceof ServerSocket) {
			ServerSocket s = (ServerSocket) o;
			format(s.getLocalSocketAddress(), formatter);
			formatter.format("<?");
			return;
		}
		if (o instanceof InetSocketAddress) {
			InetSocketAddress isa = (InetSocketAddress) o;
			format(isa.getAddress(), formatter);
			formatter.format(":%s", isa.getPort());
			return;
		}
		if (o instanceof InetAddress) {
			InetAddress address = (InetAddress) o;
			String hostAddress = address.getHostAddress(); // no DNS lookup
			formatter.format("%s", hostAddress);
			return;
		}
		formatter.format("%s", o);
	}

	default void format(Object o, Formatter f) {
		format(o, f, 0, 0, 0);
	}

	default HLogger debug(String format, Object... args) {
		Logger l = logger();
		if (l.isDebugEnabled()) {
			l.debug(format(format, args));
		}
		return this;
	}

	default HLogger trace(String format, Object... args) {
		Logger l = logger();
		if (l.isTraceEnabled()) {
			l.trace(format(format, args));
		}
		return this;
	}

	default HLogger info(String format, Object... args) {
		Logger l = logger();
		if (l.isInfoEnabled()) {
			l.info(format(format, args));
		}
		return this;
	}

	default HLogger warn(String format, Object... args) {
		Logger l = logger();
		if (l.isWarnEnabled()) {
			l.warn(format(format, args));
		}
		return this;
	}

	default HLogger error(String format, Object... args) {
		Logger l = logger();
		if (l.isErrorEnabled()) {
			l.warn(format(format, args));
		}
		return this;
	}

	default String format(Object o) {
		try (Formatter f = new Formatter()) {
			format(o, f);
			return f.toString();
		}
	}

	<T> void register(Class<T> class1, Function<T, String> object);

	String hooked(Object o);

	default void unexpected(Throwable e) {
		error("unexpected error %s", e);
		e.printStackTrace();
	}

}
