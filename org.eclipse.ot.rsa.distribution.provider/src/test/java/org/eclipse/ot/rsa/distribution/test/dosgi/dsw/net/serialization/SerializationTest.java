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
package org.eclipse.ot.rsa.distribution.test.dosgi.dsw.net.serialization;

import static org.eclipse.ot.rsa.distribution.test.dosgi.dsw.net.serialization.MyEnum.BAR;
import static org.eclipse.ot.rsa.distribution.test.dosgi.dsw.net.serialization.MyEnum.FOO;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.MetaClassesClassLoader;
import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializer;
import org.eclipse.ot.rsa.distribution.provider.serialize.freshvanilla.VanillaRMISerializerFactory;
import org.freshvanilla.lang.MetaClasses;
import org.freshvanilla.net.BinaryWireFormat;
import org.freshvanilla.net.VersionAwareVanillaPojoSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleWiring;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SerializationTest {

	@Mock
	private Bundle bundle;
	@Mock
	private BundleWiring wiring;

	VanillaRMISerializer serializer;

	@BeforeEach
	public void setUp() throws Exception {

		serializer = (VanillaRMISerializer) new VanillaRMISerializerFactory().create(bundle);
	}

	@Test
	public void testPrimitivePojo() throws IOException, ClassNotFoundException {
		PrimitivePojo pp = new PrimitivePojo(true, (byte) 2, (short) 3, 'a', 4, 5, 6, 7);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, pp);

		PrimitivePojo pp2 = (PrimitivePojo) serializer.deserializeReturn(bb);
		assertEquals(pp.booleanField, pp2.booleanField);
		assertEquals(pp.byteField, pp2.byteField);
		assertEquals(pp.charField, pp2.charField);
		assertEquals(pp.doubleField, pp2.doubleField, 0.0);
		assertEquals(pp.floatField, pp2.floatField, 0.0);
		assertEquals(pp.intField, pp2.intField, 0.0);
		assertEquals(pp.longField, pp2.longField);
		assertEquals(pp.shortField, pp2.shortField);
	}

	@Test
	public void testWrapperPojo() throws IOException, ClassNotFoundException {
		WrapperPojo wp = new WrapperPojo(true, (byte) 2, (short) 3, 'a', 4, (float) 5, (long) 6, (double) 7, "foo");

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, wp);

		WrapperPojo wp2 = (WrapperPojo) serializer.deserializeReturn(bb);
		assertEquals(wp.booleanField, wp2.booleanField);
		assertEquals(wp.byteField, wp2.byteField);
		assertEquals(wp.charField, wp2.charField);
		assertEquals(wp.doubleField, wp2.doubleField, 0.0);
		assertEquals(wp.floatField, wp2.floatField, 0.0);
		assertEquals(wp.intField, wp2.intField, 0.0);
		assertEquals(wp.longField, wp2.longField);
		assertEquals(wp.shortField, wp2.shortField);
	}

	@Test
	public void testEnumPojo() throws IOException, ClassNotFoundException {
		EnumPojo ep = new EnumPojo();

		ep.setMyEnum(MyEnum.BAR);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, ep);

		EnumPojo ep2 = (EnumPojo) serializer.deserializeReturn(bb);
		assertSame(MyEnum.BAR, ep2.getMyEnum());
	}

	@Test
	public void testNestedEnumPojo() throws IOException, ClassNotFoundException {

		EnumPojo ep = new EnumPojo();
		ep.setMyEnum(MyEnum.BAR);

		NestedEnumPojo nep = new NestedEnumPojo();
		nep.setPojo(ep);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, nep);

		NestedEnumPojo nep2 = (NestedEnumPojo) serializer.deserializeReturn(bb);
		assertSame(MyEnum.BAR, nep2.getPojo().getMyEnum());
	}

	@Test
	public void testMapCycle() throws IOException, ClassNotFoundException {
		Map<String, Object> map = new HashMap<>();
		map.put("foo", map);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, map);

		@SuppressWarnings("unchecked")
		Map<String, Object> map2 = (Map<String, Object>) serializer.deserializeReturn(bb);
		assertSame(map2, map2.get("foo"));
	}

	@Test
	public void testListCycle() throws IOException, ClassNotFoundException {
		List<Object> list = new ArrayList<>();
		list.add(list);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, list);

		@SuppressWarnings("unchecked")
		List<Object> list2 = (List<Object>) serializer.deserializeReturn(bb);
		assertSame(list2, list2.get(0));
	}

	@Test
	public void testSetCycle() throws IOException, ClassNotFoundException {
		Set<Object> set = new HashSet<>();
		set.add(set);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, set);

		@SuppressWarnings("unchecked")
		Set<Object> set2 = (Set<Object>) serializer.deserializeReturn(bb);
		assertSame(set2, set2.iterator().next());
	}

	@Test
	public void testPojoCycle() throws IOException, ClassNotFoundException {
		CyclePojo cp = new CyclePojo();
		CyclePojo cp2 = new CyclePojo();
		cp.setPojo(cp2);
		cp2.setPojo(cp);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, cp);

		CyclePojo cp3 = (CyclePojo) serializer.deserializeReturn(bb);
		assertSame(cp3, cp3.getPojo().getPojo());
	}

	@Test
	public void testTightPojoCycle() throws IOException, ClassNotFoundException {
		CyclePojo cp = new CyclePojo();
		cp.setPojo(cp);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, cp);

		CyclePojo cp3 = (CyclePojo) serializer.deserializeReturn(bb);
		assertSame(cp3, cp3.getPojo());
	}

	@Test
	public void testArray() throws IOException, ClassNotFoundException {
		CyclePojo cp = new CyclePojo();
		CyclePojo cp2 = new CyclePojo();
		cp.setPojo(cp2);
		cp2.setPojo(cp);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, new CyclePojo[] { cp, cp });

		CyclePojo[] cp3 = (CyclePojo[]) serializer.deserializeReturn(bb);
		assertSame(cp3[0], cp3[1]);
		assertSame(cp3[0], cp3[0].getPojo().getPojo());
	}

	@Test
	public void testMultiDimensionalArray() throws IOException, ClassNotFoundException {
		CyclePojo cp = new CyclePojo();
		CyclePojo cp2 = new CyclePojo();
		cp.setPojo(cp2);
		cp2.setPojo(cp);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, new CyclePojo[][] { {cp}, {cp} });

		CyclePojo[][] cp3 = (CyclePojo[][]) serializer.deserializeReturn(bb);
		assertSame(cp3[0][0], cp3[1][0]);
		assertSame(cp3[0][0], cp3[1][0].getPojo().getPojo());
	}

	@Test
	public void testMultiDimensionalArray2() throws IOException, ClassNotFoundException {
		CyclePojo cp = new CyclePojo();
		CyclePojo cp2 = new CyclePojo();
		cp.setPojo(cp2);
		cp2.setPojo(cp);

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, new CyclePojo[][][] { {{cp}}, {{cp}} });

		CyclePojo[][][] cp3 = (CyclePojo[][][]) serializer.deserializeReturn(bb);
		assertSame(cp3[0][0][0], cp3[1][0][0]);
		assertSame(cp3[0][0][0], cp3[1][0][0].getPojo().getPojo());
	}

	@Test
	public void testNestedArray() throws IOException, ClassNotFoundException {
		CyclePojo cp = new CyclePojo();
		CyclePojo cp2 = new CyclePojo();
		cp.setPojo(cp2);
		cp2.setPojo(cp);

		ByteBuf bb = Unpooled.buffer(16384);
		Object[] o = new Object[2];
		o[0] = new CyclePojo[] { cp, cp };
		o[1] = o;
		serializer.serializeReturn(bb, o);

		Object[] deserializeReturn = (Object[]) serializer.deserializeReturn(bb);
		assertSame(deserializeReturn, deserializeReturn[1]);

		CyclePojo[] cp3 = (CyclePojo[]) deserializeReturn[0];
		assertSame(cp3[0], cp3[1]);
		assertSame(cp3[0], cp3[0].getPojo().getPojo());
	}

	@Test
	public void testClasses() throws IOException, ClassNotFoundException {
		Class<?> clazz = CyclePojo.class;

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, clazz);

		Class<?> clazz2 = (Class<?>) serializer.deserializeReturn(bb);
		assertSame(clazz2, clazz);
	}

	@Test
	public void testPrimitiveClasses() throws IOException, ClassNotFoundException {
		Class<?> clazz = int.class;

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, clazz);

		Class<?> clazz2 = (Class<?>) serializer.deserializeReturn(bb);
		assertSame(clazz2, clazz);
	}

	@Test
	public void testClassArrays() throws IOException, ClassNotFoundException {
		Class<?>[] clazz = new Class<?>[] { boolean.class, byte.class, char.class, short.class, int.class, float.class,
				double.class, long.class, CyclePojo.class };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, clazz);

		Class<?>[] clazz2 = (Class<?>[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.deepEquals(clazz, clazz2));
	}

	@Test
	public void testEnumArrays() throws IOException, ClassNotFoundException {
		MyEnum[] a = new MyEnum[] { BAR, FOO, BAR, BAR, BAR, FOO, FOO, FOO };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, a);

		System.out.println(bb.readableBytes());

		MyEnum[] a2 = (MyEnum[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(a, a2));
	}

	@Test
	public void testPrimitiveArraysZ() throws IOException, ClassNotFoundException {
		boolean[] b = new boolean[] { true, false, true, true, true, false, false, false };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, b);

		boolean[] b2 = (boolean[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(b, b2));
	}

	@Test
	public void testPrimitiveArraysS() throws IOException, ClassNotFoundException {
		short[] s = new short[] { 1, 2, 3, 4, 5, 6, 7, 8 };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, s);

		short[] s2 = (short[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(s, s2));
	}

	@Test
	public void testPrimitiveArraysC() throws IOException, ClassNotFoundException {
		char[] c = new char[] { 1, 2, 3, 4, 5, 6, 7, 8 };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, c);

		char[] c2 = (char[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(c, c2));
	}

	@Test
	public void testPrimitiveArraysI() throws IOException, ClassNotFoundException {
		int[] i = new int[] { 1, 2, 3, 4, 5, 6, 7, 8 };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, i);

		int[] i2 = (int[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(i, i2));
	}

	@Test
	public void testPrimitiveArraysJ() throws IOException, ClassNotFoundException {
		long[] l = new long[] { 1, 2, 3, 4, 5, 6, 7, 8 };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, l);

		long[] l2 = (long[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(l, l2));
	}

	@Test
	public void testPrimitiveArraysF() throws IOException, ClassNotFoundException {
		float[] f = new float[] { 1, 2, 3, 4, 5, 6, 7, 8 };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, f);

		float[] f2 = (float[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(f, f2));
	}

	@Test
	public void testPrimitiveArraysD() throws IOException, ClassNotFoundException {
		double[] d = new double[] { 1, 2, 3, 4, 5, 6, 7, 8 };

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, d);

		double[] d2 = (double[]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(d, d2));
	}

	@Test
	public void testPrimitiveMultiArraysZ() throws IOException, ClassNotFoundException {
		boolean[][] b = new boolean[][] {{ true, false, true, true, true, false, false, false },
			{ false, true, true, true, true, false, false, false }};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, b);

		boolean[][] b2 = (boolean[][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(b[0], b2[0]));
		assertTrue(Arrays.equals(b[1], b2[1]));
	}

	@Test
	public void testPrimitiveMultiArraysS() throws IOException, ClassNotFoundException {
		short[][] s = new short[][] {{ 1, 2, 3, 4, 5, 6, 7, 8 }, {9, 10, 11, 12, 13, 14, 15, 16}};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, s);

		short[][] s2 = (short[][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(s[0], s2[0]));
		assertTrue(Arrays.equals(s[1], s2[1]));
	}

	@Test
	public void testPrimitiveMultiArraysC() throws IOException, ClassNotFoundException {
		char[][] c = new char[][] {{ 1, 2, 3, 4, 5, 6, 7, 8 }, {9, 10, 11, 12, 13, 14, 15, 16}};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, c);

		char[][] c2 = (char[][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(c[0], c2[0]));
		assertTrue(Arrays.equals(c[1], c2[1]));
	}

	@Test
	public void testPrimitiveMultiArraysI() throws IOException, ClassNotFoundException {
		int[][] i = new int[][] {{ 1, 2, 3, 4, 5, 6, 7, 8 }, {9, 10, 11, 12, 13, 14, 15, 16}};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, i);

		int[][] i2 = (int[][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(i[0], i2[0]));
		assertTrue(Arrays.equals(i[1], i2[1]));
	}

	@Test
	public void testPrimitiveMultiArraysJ() throws IOException, ClassNotFoundException {
		long[][] l = new long[][] {{ 1, 2, 3, 4, 5, 6, 7, 8 }, {9, 10, 11, 12, 13, 14, 15, 16}};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, l);

		long[][] l2 = (long[][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(l[0], l2[0]));
		assertTrue(Arrays.equals(l[1], l2[1]));
	}

	@Test
	public void testPrimitiveMultiArraysJ2() throws IOException, ClassNotFoundException {
		long[][][] l = new long[][][] {{{ 1, 2, 3, 4}, {5, 6, 7, 8 }}, {{9, 10, 11, 12}, {13, 14, 15, 16}}};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, l);

		long[][][] l2 = (long[][][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(l[0][0], l2[0][0]));
		assertTrue(Arrays.equals(l[0][1], l2[0][1]));
		assertTrue(Arrays.equals(l[1][0], l2[1][0]));
		assertTrue(Arrays.equals(l[1][1], l2[1][1]));
	}

	@Test
	public void testPrimitiveMultiArraysF() throws IOException, ClassNotFoundException {
		float[][] f = new float[][] {{ 1, 2, 3, 4, 5, 6, 7, 8 }, {9, 10, 11, 12, 13, 14, 15, 16}};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, f);

		float[][] f2 = (float[][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(f[0], f2[0]));
		assertTrue(Arrays.equals(f[1], f2[1]));
	}

	@Test
	public void testPrimitiveMultiArraysD() throws IOException, ClassNotFoundException {
		double[][] d = new double[][] {{ 1, 2, 3, 4, 5, 6, 7, 8 }, {9, 10, 11, 12, 13, 14, 15, 16}};

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, d);

		double[][] d2 = (double[][]) serializer.deserializeReturn(bb);
		assertTrue(Arrays.equals(d[0], d2[0]));
		assertTrue(Arrays.equals(d[1], d2[1]));
	}

	@Test
	public void testVersionAware() throws IOException, ClassNotFoundException {
		Version v = new Version(1,2,3,"foo");

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, v);

		bb.markReaderIndex();

		assertEquals(v, serializer.deserializeReturn(bb));
		assertFalse(bb.isReadable());

		bb.resetReaderIndex();

		MetaClasses mc = new MetaClasses(new MetaClassesClassLoader(bundle));

		BinaryWireFormat newWF = new BinaryWireFormat(mc, new VersionAwareVanillaPojoSerializer(mc));

		assertEquals(v, newWF.readObject(bb));
		assertFalse(bb.isReadable());

		newWF.reset();

		// Now without a qualifier
		v = new Version(1,2,3);

		bb.clear();
		serializer.serializeReturn(bb, v);

		bb.markReaderIndex();

		assertEquals(v, serializer.deserializeReturn(bb));
		assertFalse(bb.isReadable());

		bb.resetReaderIndex();

		assertEquals(v, newWF.readObject(bb));
		assertFalse(bb.isReadable());

		newWF.reset();

		// Now with an array
		Version[] vArray = new Version[] {new Version(2,3,4), new Version(3,4,5)};

		bb.clear();
		serializer.serializeReturn(bb, vArray);

		bb.markReaderIndex();

		v = new Version(1,2,3);
		assertArrayEquals(vArray, (Version[]) serializer.deserializeReturn(bb));
		assertFalse(bb.isReadable());

		bb.resetReaderIndex();

		assertArrayEquals(vArray, (Version[]) newWF.readObject(bb));
		assertFalse(bb.isReadable());

		newWF.reset();
	}

	@Test
	public void testStringWithNonASCIIChars() throws Exception {
		//Use an interrobang to upset the serializer
		String toTest = "Hello World\u203D";

		ByteBuf bb = Unpooled.buffer(16384);
		serializer.serializeReturn(bb, toTest);

		assertEquals(toTest, serializer.deserializeReturn(bb));
	}
}
