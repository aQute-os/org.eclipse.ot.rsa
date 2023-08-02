package org.eclipse.ot.rsa.distribution.provider.wireformat;

public interface Dispatcher extends Server, Client {
	Msg dispatch(Msg msg, Server server);

	Msg dispatch(Msg msg, Client client);

}
