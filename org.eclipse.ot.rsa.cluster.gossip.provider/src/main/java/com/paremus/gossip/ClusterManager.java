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
package com.paremus.gossip;

import java.util.Collection;
import java.util.UUID;

import org.eclipse.ot.rsa.cluster.api.ClusterListener;
import org.osgi.framework.ServiceReference;

import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.cluster.impl.Update;
import com.paremus.gossip.v1.messages.Snapshot;
import com.paremus.gossip.v1.messages.SnapshotType;

import io.netty.util.concurrent.EventExecutorGroup;

public interface ClusterManager {

	public void leavingCluster(Snapshot update);

	public String getClusterName();

	public Update mergeSnapshot(Snapshot snapshot);

	public Snapshot getSnapshot(SnapshotType type, int hops);

	public Collection<MemberInfo> selectRandomPartners(int max);

	public MemberInfo getMemberInfo(UUID id);

	public Collection<Snapshot> getMemberSnapshots(SnapshotType type);

	public void markUnreachable(MemberInfo member);

	public void destroy();

	public UUID getLocalUUID();

	public void listenerChange(ServiceReference<ClusterListener> ref, int state);

	public EventExecutorGroup getEventExecutorGroup();
}
