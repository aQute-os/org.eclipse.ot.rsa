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
package com.paremus.fabric.v2.dto;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.paremus.dto.api.struct;
import com.paremus.entire.attributes.api.AD.Unit;
import com.paremus.entire.attributes.api.ADA;
import com.paremus.entire.attributes.api.Sub;
import com.paremus.fabric.v1.dto.FibreExt;

/**
 * The following type defines the data we receive about a fibre. This type is
 * extended in {@link FibreExt} because it hides lots of important info in
 * attributes.
 * 
 */
public class Fibre extends struct {











	private static final long	serialVersionUID	= 1L;

	public final static String	HIDE_G				= "hide";
	public final static String	INFO_G				= "info";
	public final static String	ENVIRONMENT_G		= "environment";
	public final static String	FIBRE_G				= "fibre";
	public final static String	FILESYS_G				= "filesys";
	public final static String	NETWORK_G			= "network";
	public final static String	MACHINE_G			= "machine";
	public final static String	JVM_G				= "jvm";
	public final static String	PART_G				= "part";
	public final static String	BUNDLES_G			= "bundles";
	public final static String	SERVICES_G			= "services";

	

	/**
	 * @formatter:off
	 * 
	 * FABRIC/FIBRE section
	 */
	
	@ADA(
		groups=HIDE_G, 
		description = "The identity of a fibre, this "
					+ "name is unique in a Fabric")
																		public String					id;
	@ADA(
		groups=HIDE_G,
		description="Current fibre status, this based on "
				+ "frequent samples of the fibre")
																		public Status					status					= Status.UNKNOWN;
	
	@ADA(
			groups=INFO_G,
			description="Infrastructure fibre")
																		public boolean					infrastructure 			= false;
		

	@ADA(
			groups=INFO_G,
			description="Leading Infrastructure fibre")
																		public boolean					leader 			= false;
		
	@ADA(
			groups=FIBRE_G,
			description="Framework UUID")
																		public String					frameworkUUID;
		

	@ADA(
			groups=FIBRE_G, 
			description="Fabric Version")
																		public String					version;
	
	@ADA(
		groups=FIBRE_G, 
		description = "Qualified host name of the fibre")																	
																		public String					hostname;
	@ADA(
			groups=FIBRE_G, 
			unit = Unit.time,
			viewer="age",
			description = "Boot time of the fibre")																	
																		public long						bootTime;

	@ADA(
		groups=HIDE_G, 
		deflt="0",
		description = "The instance id of the machine. Each fibre "
				+ "has an instance id that is used to discriminate "
				+ "between multiple fibres on the same"
				+ "machine.")																	
																		public int 						instance;
	
	@ADA(
		groups=FIBRE_G, 
		deflt="false",
		description = "True if security is on, False if off")																	
																		public boolean					security;
	
	@ADA(
		groups=HIDE_G, 
		description = "URN of the fibre.")
																		public URI						uri;


	
	@ADA(
		groups=HIDE_G, 
		description = "The Fabric name")
																		public String					fabric;

	@ADA(
		groups=HIDE_G,
		description = "Systems associated to this fibre")
																		public Set<String>				systems			= set();

	@ADA(
			groups=FIBRE_G,
			description = "A system message")
																		public String					message;
		
	@ADA(
			groups={FIBRE_G,JVM_G},
			description = "Java version")
																		public String					java;
		
	@ADA(
			groups=HIDE_G, 
			description = "Fibre features are properties set per Fibre.", diff=true)
																			public Map<String, Object>		features				= map();

	@ADA(
			groups=FIBRE_G,
			viewer="map",
			description = "Read only fibre features", diff=true)
																			public Map<String, Object>		systemParts				= map();

	@ADA(
		groups=FIBRE_G,
		description = "Number of recent log errors (moving average)")
																		public long						logErrors;

	
	@ADA(
		groups=FIBRE_G,
		unit = Unit.time,
		viewer="age",
		description = "The time this Fibre was started.")
																		public long						startTime;
	/**************************************
	 * MACHINE
	 **************************************/

	@ADA(
		groups=HIDE_G, 
		viewer="summary",
		description="Machine information")
																		public Asset					machine				= new Asset();

	
	
	@ADA(
		groups=MACHINE_G, 
		viewer="summary",
		description="Operating System information.")
																		public Asset					os					= new Asset();
	@ADA(
		groups=MACHINE_G, 
		viewer="summary",
		description="Central Processing Unit information")
																		public Asset					cpu					= new Asset();
	@ADA(
		groups=MACHINE_G, 
		description="Processor architecture")
																		public String					architecture;

	@ADA(
		description = "Average load of the CPU. Moving average.",
		groups = MACHINE_G,
		unit = Unit.percentage,
		diff=true,
		threshold=5)
																		public int						cpuLoadAvg;

	@ADA(
		groups=MACHINE_G,
		description = "Average load per core, moving average",
		viewer="bars")
	@Sub(@ADA(unit=Unit.percentage))
																		public List<Integer>			coreLoadAvg			= list();

	@ADA(
		groups = MACHINE_G,
		description = "Number of cores in the CPU")
																		public int						coreCount;

	@ADA(
		groups = MACHINE_G,
		unit = Unit.bytes,
		description = "Total amount of Random Access Memory")
																		public long						ramTotal;
	@ADA(
		groups = MACHINE_G,
		unit = Unit.bytes,
		description = "Used amount of Random Access Memory")
																		public long						ramUsed;

	@ADA(
		groups = MACHINE_G,
		unit = Unit.hertz,
		description = "Clock speed")
																		public long						clockSpeed;


	
	@ADA(
		groups=FILESYS_G,
		viewer = "machine-filesys",
		name = "File Systems",
		description="File Systems.")
																		public List<FibreFileSystem>	fileSystems			= list();

	@ADA(
		groups=NETWORK_G,
		viewer = "machine-network",
		name = "Network Interfaces",
		description="Network Interfaces that are available on "
				+ "the fibre.")
																		public List<FibreNetworkInterface>	networkInterfaces	= list();
	@ADA(
		groups=NETWORK_G,
		description="IP Address of the gateway")
																		public String				gateway;

	@ADA(
		groups=NETWORK_G,
		name = "Primary DNS",
		description="Primary DNS of this fibre")
																		public String				dns1;
	@ADA(
		groups=NETWORK_G,
		name = "Secondary DNS",
		description="Secondary DNS of this fibre")
																		public String				dns2;

	/**************************************
	 * JVM
	 **************************************/
	
	@ADA(
		name = "JVM",
		description = "Identification of the VM",
		groups = JVM_G,
		viewer = "summary")
																		public Asset				jvm						= new Asset();
	
	@ADA(
		name = "Java Specification",
		description = "Provides the detail of what Java "
				+ "specification this VM implements",
		groups = JVM_G,
		viewer = "summary")
																		public Asset				jvmSpecification		= new Asset();
	
	@ADA(
		name = "Garbage Collectors",
		description = "Identification of the VM",
		groups = JVM_G,
		viewer = "fibre-gc")
																		public List<FibreGCInfo>	jvmGarbageCollectors	= list();
	
	@ADA(
		description = "Available Java VM heap memory",
		viewer = "stacked",
		groups = JVM_G,
		diff = true)
																		public FibreMemoryInfo		jvmHeap					= new FibreMemoryInfo();
	
	@ADA(
		description = "Available Java VM non-heap memory",
		viewer = "stacked",
		groups = JVM_G,
		diff = true)
																		public FibreMemoryInfo		jvmNonHeap				= new FibreMemoryInfo();
	
	@ADA(
		name = "Thread Pools",
		description = "Thread Pool running on the Fibre",
		viewer = "thread-pools",
		groups = JVM_G,
		diff = true)
																		public List<FibreThreadPoolInfo>jvmThreadPools			= list();


	@ADA(
		name = "Deadlocked threads",
		description = "Number of threads that are in a deadlock "
				+ "cycle. This should normally be 0",
		groups = JVM_G,
		min = 0,
		high = 1, 
		threshold= 1)
																		public List<String>			deadlockedThread		= list();

	@ADA(
		groups="hide",
		description = "Detected system properties related "
				+ "to the fibre/container")

																		public SystemProperties 	systemProperties;

	@ADA(
			groups=HIDE_G, 
			unit = Unit.time,
			description = "Time this sample was taken")
																		public long	sampleTime;
	@ADA(
			groups=HIDE_G, 
			description = "The id of this sample this is a monotonically"
					+ "increasing number that increments each time a new"
					+ "sample is made.")	
																		public int	sampleId;

	@ADA(
			groups=HIDE_G, 
			unit = Unit.ms,
			description = "Duration between this and previous sample")
																		public long	sampleInterval;
	
	@ADA(
			groups=FIBRE_G,
			diff=true,
			threshold=10000,
			name="No Contact",
			description = "Time last contact")
	public long	noContact;

	@ADA(
			groups=FIBRE_G,
			description = "Serial number of the certificates found in the SSL trust store")
	public List<String>	certificateSerialNumbers;


	@ADA(
			groups=FIBRE_G,
			description = "The term of the leader, -1 if no leader found", diff=true, unit=Unit.time)
	public long leaderTerm;
}
