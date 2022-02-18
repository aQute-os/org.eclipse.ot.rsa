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
import java.util.List;

import com.paremus.gossip.cluster.impl.MemberInfo;
import com.paremus.gossip.v1.messages.Snapshot;

import io.netty.util.concurrent.Future;

public interface InternalClusterListener {
	void localUpdate(Snapshot s);
	List<Future<?>> destroy();
	void darkNodes(Collection<MemberInfo> darkNodes);
}
