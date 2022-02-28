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
package org.eclipse.ot.rsa.cluster.gossip;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.UUID;

import org.eclipse.ot.rsa.cluster.gossip.v1.messages.Snapshot;
import org.eclipse.ot.rsa.cluster.manager.provider.MemberInfo;

public interface Gossip {

	public abstract void handleMessage(InetSocketAddress sender, GossipMessage content);

	public abstract void merge(Snapshot snapshot);

	public abstract Collection<Snapshot> getAllSnapshots();

	public abstract MemberInfo getInfoFor(UUID id);

	public abstract void ping(InetSocketAddress udpAddress);

}
