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
package com.paremus.dosgi.discovery.cluster.comms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.osgi.framework.Constants.FRAMEWORK_UUID;
import static org.osgi.framework.Constants.OBJECTCLASS;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Version;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class EndpointSerializerTest {

	@Test
	public void testRoundTrip() throws IOException {
		EndpointDescription ed = getTestEndpointDescription();
		
		ByteBuf buf = Unpooled.buffer();
		
		EndpointSerializer.serialize(ed, buf);
		
		System.out.println("Serialized size was: " + buf.readableBytes());
		
		EndpointDescription ed2 = EndpointSerializer.deserializeEndpoint(buf);
		
		Map<String, Object> originalProps = ed.getProperties();
		Map<String, Object> roundTrippedProps = ed2.getProperties();
		
		assertEquals(originalProps.size(), roundTrippedProps.size());
		
		for(String key : originalProps.keySet()) {
			Object orig = originalProps.get(key);
			Object roundTripped = roundTrippedProps.get(key);
			if(orig instanceof Collection) {
				assertEquals(new ArrayList<Object>((Collection<?>)orig), roundTripped);
			} else if (orig instanceof Map){
				assertEquals(new LinkedHashMap<Object, Object>((Map<?, ?>)orig), roundTripped);
			} else {
				assertEquals(orig.getClass(), roundTripped.getClass());
				if(orig.getClass().isArray()) {
					int length = Array.getLength(orig);
					for(int i = 0; i < length;i ++) {
						assertEquals(Array.get(orig, i), Array.get(roundTripped, i));
					}
				} else {
					assertEquals(orig, roundTripped);
				}
			}
		}
	}

    private EndpointDescription getTestEndpointDescription() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();

        // required
        m.put(OBJECTCLASS, new String[]{"com.acme.HelloService", "some.other.Service"});
        m.put(RemoteConstants.ENDPOINT_FRAMEWORK_UUID, FRAMEWORK_UUID);
        m.put(RemoteConstants.ENDPOINT_ID, "http://myhost:8080/commands");
        m.put(RemoteConstants.ENDPOINT_SERVICE_ID, Long.valueOf(42));
        m.put(RemoteConstants.SERVICE_IMPORTED_CONFIGS, "my.config.type");

        // custom values
        m.put("long", 9223372036854775807L);
        m.put("Long2", -1L);
        m.put("double", 1.7976931348623157E308);
        m.put("Double2", 1.0d);
        m.put("float", 42.24f);
        m.put("Float2", 1.0f);
        m.put("int", 17);
        m.put("Integer2", 42);
        m.put("byte", (byte)127);
        m.put("Byte2", (byte)-128);
        m.put("boolean", true);
        m.put("Boolean2", false);
        m.put("short", (short)99);
        m.put("Short2", (short)-99);
        m.put("char", '@');
        m.put("Character2", 'X');
        
        m.put("version", new Version("1.2"));
        m.put("Version2", new Version("2.3"));

        // collections and arrays
        List<Boolean> boolList = new ArrayList<Boolean>();
        boolList.add(true);
        boolList.add(false);
        m.put("bool-list", boolList);
        m.put("empty-set", new HashSet<Object>());

        Set<String> stringSet = new LinkedHashSet<String>();
        stringSet.add("Hello there");
        stringSet.add("How are you?");
        m.put("string-set", stringSet);

        boolean[] boolArray = new boolean[]{true, false};
        m.put("boolean-array", boolArray);
        byte[] byteArray = new byte[]{1, 2};
        m.put("byte-array", byteArray);
        short[] shortArray = new short[]{3, 4};
        m.put("short-array", shortArray);
        char[] charArray = new char[]{5, 6};
        m.put("char-array", charArray);
        int[] intArray = new int[]{7, 8};
        m.put("int-array", intArray);
        float[] floatArray = new float[]{9, 10};
        m.put("float-array", floatArray);
        long[] longArray = new long[]{11, 12};
        m.put("long-array", longArray);
        double[] doubleArray = new double[]{13, 14};
        m.put("double-array", doubleArray);

        Boolean[] BoolArray = new Boolean[]{true, false};
        m.put("Boolean-array2", BoolArray);
        Byte[] ByteArray = new Byte[]{1, 2};
        m.put("Byte-array2", ByteArray);
        Short[] ShortArray = new Short[]{3, 4};
        m.put("Short-array2", ShortArray);
        Character[] CharArray = new Character[]{5, 6};
        m.put("Char-array2", CharArray);
        Integer[] IntArray = new Integer[]{7, 8};
        m.put("Int-array2", IntArray);
        Float[] FloatArray = new Float[]{9f, 10f};
        m.put("Float-array2", FloatArray);
        Long[] LongArray = new Long[]{11l, 12l};
        m.put("Long-array2", LongArray);
        Double[] DoubleArray = new Double[]{13d, 14d};
        m.put("Double-array2", DoubleArray);

        String[] stringArray = new String[]{"foo", "bar"};
        m.put("string-array", stringArray);
       
        Version[] versionArray = new Version[]{new Version("1.2.3"), new Version("2.3.4")};
        m.put("version-array", versionArray);
        
        m.put("map", Collections.singletonMap("foo", "bar"));

        
        // raw XML for good measure (must remain parseable with namespaces!)
        String LF = "\n";
        String xml = "<xml>" + LF + "<t1 xmlns=\"http://www.acme.org/xmlns/other/v1.0.0\">" + LF
                     + "<foo type='bar'>haha</foo>" + LF + "</t1>" + LF + "</xml>";
        m.put("someXML", xml);

        return new EndpointDescription(m);
    }
}
