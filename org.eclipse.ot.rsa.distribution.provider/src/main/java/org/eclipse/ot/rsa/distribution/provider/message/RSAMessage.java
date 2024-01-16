package org.eclipse.ot.rsa.distribution.provider.message;

import org.eclipse.ot.rsa.distribution.provider.message.AbstractRSAMessage.CacheKey;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.Promise;

public interface RSAMessage {
	CacheKey getKey();

	interface MsgHandler {

		void fire(ByteBuf data);

		Promise<?> send(ByteBuf data);
	}

	boolean received(ByteBuf buffer);

	void send(MsgHandler msgHandler);

}
