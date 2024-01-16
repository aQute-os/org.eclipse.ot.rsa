package org.eclipse.ot.rsa.distribution.provider.proxy;

import static org.eclipse.ot.rsa.distribution.util.Utils.invert;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.ot.rsa.distribution.provider.proxy.RSAProxyContext.Action;
import org.eclipse.ot.rsa.distribution.provider.wireformat.Protocol;

import io.netty.channel.Channel;

public class RSAImportedContext {
	final RSAContext			context;
	final UUID					uuid;
	final List<String>			interfaces;
	final Protocol				protocol;
	final AtomicLong			timeout	= new AtomicLong();
	final Map<String, Integer>	signatureToIndex;
	final Channel				channel;

	RSAImportedContext(RSAContext context, Channel channel, UUID uuid, List<String> interfaces,
		Map<Integer, String> methodMapping,
		Protocol protocol, long timeout) {
		this.channel = channel;
		this.signatureToIndex = invert(methodMapping);
		this.interfaces = new ArrayList<>(interfaces);
		this.context = context;
		this.uuid = uuid;
		this.protocol = protocol;
		this.timeout.set(timeout);
	}

	public RSAProxyContext create(LoadClass loader) throws ClassNotFoundException {
		RSAProxyContext pc = new RSAProxyContext(this, loader);
		context.specialHandlers.forEach(h -> h.newProxy(pc));
		return pc;
	}

	public Action getAction(Method m, Integer index) {
		Action distribute = (p,a) -> {
			int callId = context.callId.incrementAndGet();
			protocol.callWithReturn(uuid, callId, index, a);
			return null;
		};
		return null;
	}

}
