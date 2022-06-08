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
package org.eclipse.ot.rsa.cluster.gossip.api;

import java.util.Collection;
import java.util.UUID;

import org.eclipse.ot.rsa.cluster.api.ClusterListener;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.Snapshot;
import org.eclipse.ot.rsa.cluster.gossip.v1.messages.SnapshotType;
import org.eclipse.ot.rsa.cluster.manager.provider.MemberInfo;
import org.eclipse.ot.rsa.cluster.manager.provider.Update;
import org.osgi.framework.ServiceReference;

import io.netty.util.concurrent.EventExecutorGroup;

public interface ClusterManager {

	void leavingCluster(Snapshot update);

	String getClusterName();

	Update mergeSnapshot(Snapshot snapshot);

	Snapshot getSnapshot(SnapshotType type, int hops);

	Collection<MemberInfo> selectRandomPartners(int max);

	MemberInfo getMemberInfo(UUID id);

	Collection<Snapshot> getMemberSnapshots(SnapshotType type);

	void markUnreachable(MemberInfo member);

	void destroy();

	UUID getLocalUUID();

	void listenerChange(ServiceReference<ClusterListener> ref, int state);

	EventExecutorGroup getEventExecutorGroup();

	Collection<MemberInfo> getMembers();
}
