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
package com.paremus.dosgi.discovery.gossip.scope;

import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_CLUSTERS_ATTRIBUTE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_CLUSTERS_EXTRA_ATTRIBUTE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPES_ATTRIBUTE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_GLOBAL;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_TARGETTED;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_SCOPE_UNIVERSAL;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_TARGETTED_ATTRIBUTE;
import static com.paremus.dosgi.discovery.scoped.Constants.PAREMUS_TARGETTED_EXTRA_ATTRIBUTE;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.osgi.service.remoteserviceadmin.EndpointDescription;


public class EndpointFilter {

	private final String rootCluster;
	
	private final Set<String> clusters = new HashSet<>();

	private final Set<String> scopes = new HashSet<>();

	public EndpointFilter(String rootCluster) {
		this.rootCluster = rootCluster;
		this.clusters.add(rootCluster);
	}
	
	public static EndpointFilter createFilter(DataInput input) {
		try {
			if(input.readUnsignedByte() != 1) {
				throw new IllegalStateException("An unknown endpoint descriptor format");
			}
			
			String rootCluster = input.readUTF();
			
			EndpointFilter ef = new EndpointFilter(rootCluster);

			int size = input.readUnsignedShort();
			for(int i = 0; i < size; i++) {
				ef.addCluster(input.readUTF());
			}
			size = input.readUnsignedShort();
			for(int i = 0; i < size; i++) {
				ef.addScope(input.readUTF());
			}
			return ef;
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	public void writeOut(DataOutput dos) {
		try {
			dos.writeByte(1);
			dos.writeUTF(rootCluster);
			dos.writeShort(clusters.size() -1);
			clusters.stream()
				.filter(s -> !rootCluster.equals(s))
				.forEach(s -> {
					try { 
						dos.writeUTF(s); 
					} catch (IOException ioe) {
						throw new RuntimeException(ioe);
					}
				});
			dos.writeShort(scopes.size());
			scopes.stream()
				.forEach(s -> {
					try { 
						dos.writeUTF(s); 
					} catch (IOException ioe) {
						throw new RuntimeException(ioe);
					}
				});
				
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}
	
	public void addCluster(String clusterName) {
		clusters.add(clusterName);
	}

	public void removeCluster(String clusterName) {
		if(!rootCluster.equals(clusterName)) {
			clusters.remove(clusterName);
		}
	}

	public void addScope(String scope) {
		scopes.add(scope);
	}
	
	public void removeScope(String scope) {
		scopes.remove(scope);	
	}
	
	public boolean accept(EndpointDescription ed) {
		Map<String, Object> properties = ed.getProperties();
		
		Collection<String> scopes = getOrDefault(properties, PAREMUS_SCOPES_ATTRIBUTE, 
				PAREMUS_SCOPE_GLOBAL);
		
		// Universal is everywhere
		if(scopes.contains(PAREMUS_SCOPE_UNIVERSAL)) {
			return true;
		}
		
		Collection<String> endpointTargetClusters = concat(getOrDefault(properties, 
				PAREMUS_CLUSTERS_ATTRIBUTE, rootCluster).stream(), getOrDefault(properties, 
				PAREMUS_CLUSTERS_EXTRA_ATTRIBUTE, emptySet()).stream()).collect(toSet());
		
		// Not universal, so if it doesn't match our cluster then it can't match
		if(Collections.disjoint(clusters, endpointTargetClusters)) {
			return false;
		}
		
		// It matches our cluster and is global
		if(scopes.contains(PAREMUS_SCOPE_GLOBAL)) {
			return true;
		}

		Collection<String> endpointTargetScopes = concat(getOrDefault(properties, 
				PAREMUS_TARGETTED_ATTRIBUTE, emptySet()).stream(), getOrDefault(properties, 
				PAREMUS_TARGETTED_EXTRA_ATTRIBUTE, emptySet()).stream()).collect(toSet());
		
		//If it's targetted and any targets match then yes
		if(scopes.contains(PAREMUS_SCOPE_TARGETTED) && 
				!Collections.disjoint(this.scopes, endpointTargetScopes)) {
			return true;
		}
		
		// No matching, so not acceptable
		return false;
	}
	
	private Collection<String> getOrDefault(Map<String, Object> map, String key, Object defaultValue) {
		Object o = map.getOrDefault(key, defaultValue);
		if(o instanceof String) {
			return Collections.singleton(o.toString());
		} else if (o instanceof Collection) {
			return ((Collection<?>) o).stream()
					.filter(x -> x != null)
					.map(Object::toString)
					.collect(toSet());
		} else if (o instanceof String[]) {
			return Arrays.stream((String[]) o)
					.filter(x -> x != null)
					.collect(toSet());
		}
		return Collections.singleton(String.valueOf(o));
	}

	public Set<String> getClusters() {
		return clusters.stream().collect(toSet());
	}

	public Set<String> getScopes() {
		return scopes.stream().collect(toSet());
	}
	
}
