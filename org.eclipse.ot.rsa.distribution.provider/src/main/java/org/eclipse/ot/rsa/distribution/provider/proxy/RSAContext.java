package org.eclipse.ot.rsa.distribution.provider.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol;

import io.netty.channel.Channel;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;

public class RSAContext {

	final AtomicInteger			callId			= new AtomicInteger(1000);
	final Timer					timer;
	final EventExecutorGroup	executor;
	final Future<Boolean>		TRUE;
	final Future<Boolean>		FALSE;
	final List<SpecialHandler>	specialHandlers	= new ArrayList<>();

	public interface SpecialHandler {
		void newProxy(RSAProxyContext imported);

		void newImported(RSAImportedContext imported);

	}

	public RSAContext(EventExecutorGroup executor, Timer timer) {
		this.executor = executor;
		this.timer = timer;
		this.TRUE = executor.next()
			.newSucceededFuture(true);
		this.FALSE = executor.next()
			.newSucceededFuture(false);
	}

	public RSAImportedContext newImported(UUID id, List<String> interfaces, Map<Integer, String> methodMappings,
		Protocol protocol, Channel channel) {
		RSAImportedContext imported = new RSAImportedContext(this, channel, id, interfaces, methodMappings, protocol,
			0);
		specialHandlers.forEach(h -> h.newImported(imported));
		return imported;
	}
}
