/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.java.typeutils.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.CompatibilityResult;
import org.apache.flink.api.common.typeutils.SerializerTestBase;
import org.apache.flink.api.common.typeutils.TypeComparator;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.CompositeType.FlatFieldDescriptor;
import org.apache.flink.api.common.operators.Keys.ExpressionKeys;
import org.apache.flink.api.common.operators.Keys.IncompatibleKeysException;
import org.apache.flink.api.common.typeutils.TypeSerializerConfigSnapshot;
import org.apache.flink.api.common.typeutils.TypeSerializerSerializationUtil;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.PojoTypeInfo;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.api.java.typeutils.TypeExtractor;

import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * A test for the {@link PojoSerializer}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(TypeSerializerSerializationUtil.class)
public class PojoSerializerTest extends SerializerTestBase<PojoSerializerTest.TestUserClass> {
	private TypeInformation<TestUserClass> type = TypeExtractor.getForClass(TestUserClass.class);

	@Override
	protected TypeSerializer<TestUserClass> createSerializer() {
		TypeSerializer<TestUserClass> serializer = type.createSerializer(new ExecutionConfig());
		assert(serializer instanceof PojoSerializer);
		return serializer;
	}

	@Override
	protected int getLength() {
		return -1;
	}

	@Override
	protected Class<TestUserClass> getTypeClass() {
		return TestUserClass.class;
	}

	@Override
	protected TestUserClass[] getTestData() {
		Random rnd = new Random(874597969123412341L);

		return new TestUserClass[]{
				new TestUserClass(rnd.nextInt(), "foo", rnd.nextDouble(), new int[]{1, 2, 3}, new Date(),
						new NestedTestUserClass(rnd.nextInt(), "foo@boo", rnd.nextDouble(), new int[]{10, 11, 12})),
				new TestUserClass(rnd.nextInt(), "bar", rnd.nextDouble(), new int[]{4, 5, 6}, null,
						new NestedTestUserClass(rnd.nextInt(), "bar@bas", rnd.nextDouble(), new int[]{20, 21, 22})),
				new TestUserClass(rnd.nextInt(), null, rnd.nextDouble(), null, null, null),
				new TestUserClass(rnd.nextInt(), "bar", rnd.nextDouble(), new int[]{4, 5, 6}, new Date(),
						new NestedTestUserClass(rnd.nextInt(), "bar@bas", rnd.nextDouble(), new int[]{20, 21, 22}))
		};

	}

	// User code class for testing the serializer
	public static class TestUserClass {
		public int dumm1;
		public String dumm2;
		public double dumm3;
		public int[] dumm4;
		public Date dumm5;

		public NestedTestUserClass nestedClass;

		public TestUserClass() {
		}

		public TestUserClass(int dumm1, String dumm2, double dumm3, int[] dumm4, Date dumm5, NestedTestUserClass nestedClass) {
			this.dumm1 = dumm1;
			this.dumm2 = dumm2;
			this.dumm3 = dumm3;
			this.dumm4 = dumm4;
			this.dumm5 = dumm5;
			this.nestedClass = nestedClass;
		}

		@Override
		public int hashCode() {
			return Objects.hash(dumm1, dumm2, dumm3, dumm4, nestedClass);
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof TestUserClass)) {
				return false;
			}
			TestUserClass otherTUC = (TestUserClass) other;
			if (dumm1 != otherTUC.dumm1) {
				return false;
			}
			if ((dumm2 == null && otherTUC.dumm2 != null)
					|| (dumm2 != null && !dumm2.equals(otherTUC.dumm2))) {
				return false;
			}
			if (dumm3 != otherTUC.dumm3) {
				return false;
			}
			if ((dumm4 != null && otherTUC.dumm4 == null)
					|| (dumm4 == null && otherTUC.dumm4 != null)
					|| (dumm4 != null && otherTUC.dumm4 != null && dumm4.length != otherTUC.dumm4.length)) {
				return false;
			}
			if (dumm4 != null && otherTUC.dumm4 != null) {
				for (int i = 0; i < dumm4.length; i++) {
					if (dumm4[i] != otherTUC.dumm4[i]) {
						return false;
					}
				}
			}
			
			if ((nestedClass == null && otherTUC.nestedClass != null)
					|| (nestedClass != null && !nestedClass.equals(otherTUC.nestedClass))) {
				return false;
			}
			return true;
		}
	}

	public static class NestedTestUserClass {
		public int dumm1;
		public String dumm2;
		public double dumm3;
		public int[] dumm4;

		public NestedTestUserClass() {
		}

		public NestedTestUserClass(int dumm1, String dumm2, double dumm3, int[] dumm4) {
			this.dumm1 = dumm1;
			this.dumm2 = dumm2;
			this.dumm3 = dumm3;
			this.dumm4 = dumm4;
		}

		@Override
		public int hashCode() {
			return Objects.hash(dumm1, dumm2, dumm3, dumm4);
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof NestedTestUserClass)) {
				return false;
			}
			NestedTestUserClass otherTUC = (NestedTestUserClass) other;
			if (dumm1 != otherTUC.dumm1) {
				return false;
			}
			if (!dumm2.equals(otherTUC.dumm2)) {
				return false;
			}
			if (dumm3 != otherTUC.dumm3) {
				return false;
			}
			if (dumm4.length != otherTUC.dumm4.length) {
				return false;
			}
			for (int i = 0; i < dumm4.length; i++) {
				if (dumm4[i] != otherTUC.dumm4[i]) {
					return false;
				}
			}
			return true;
		}
	}

	public static class SubTestUserClassA extends TestUserClass {
		public int subDumm1;
		public String subDumm2;

		public SubTestUserClassA() {}
	}

	public static class SubTestUserClassB extends TestUserClass {
		public Double subDumm1;
		public float subDumm2;

		public SubTestUserClassB() {}
	}
	
	/**
	 * This tests if the hashes returned by the pojo and tuple comparators are the same
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testTuplePojoTestEquality() {
		
		// test with a simple, string-key first.
		PojoTypeInfo<TestUserClass> pType = (PojoTypeInfo<TestUserClass>) type;
		List<FlatFieldDescriptor> result = new ArrayList<FlatFieldDescriptor>();
		pType.getFlatFields("nestedClass.dumm2", 0, result);
		int[] fields = new int[1]; // see below
		fields[0] = result.get(0).getPosition();
		TypeComparator<TestUserClass> pojoComp = pType.createComparator( fields, new boolean[]{true}, 0, new ExecutionConfig());
		
		TestUserClass pojoTestRecord = new TestUserClass(0, "abc", 3d, new int[] {1,2,3}, new Date(), new NestedTestUserClass(1, "haha", 4d, new int[] {5,4,3}));
		int pHash = pojoComp.hash(pojoTestRecord);
		
		Tuple1<String> tupleTest = new Tuple1<String>("haha");
		TupleTypeInfo<Tuple1<String>> tType = (TupleTypeInfo<Tuple1<String>>)TypeExtractor.getForObject(tupleTest);
		TypeComparator<Tuple1<String>> tupleComp = tType.createComparator(new int[] {0}, new boolean[] {true}, 0, new ExecutionConfig());
		
		int tHash = tupleComp.hash(tupleTest);
		
		Assert.assertTrue("The hashing for tuples and pojos must be the same, so that they are mixable", pHash == tHash);
		
		Tuple3<Integer, String, Double> multiTupleTest = new Tuple3<Integer, String, Double>(1, "haha", 4d); // its important here to use the same values.
		TupleTypeInfo<Tuple3<Integer, String, Double>> multiTupleType = (TupleTypeInfo<Tuple3<Integer, String, Double>>)TypeExtractor.getForObject(multiTupleTest);
		
		ExpressionKeys fieldKey = new ExpressionKeys(new int[]{1,0,2}, multiTupleType);
		ExpressionKeys expressKey = new ExpressionKeys(new String[] {"nestedClass.dumm2", "nestedClass.dumm1", "nestedClass.dumm3"}, pType);
		try {
			Assert.assertTrue("Expecting the keys to be compatible", fieldKey.areCompatible(expressKey));
		} catch (IncompatibleKeysException e) {
			e.printStackTrace();
			Assert.fail("Keys must be compatible: "+e.getMessage());
		}
		TypeComparator<TestUserClass> multiPojoComp = pType.createComparator( expressKey.computeLogicalKeyPositions(), new boolean[]{true, true, true}, 0, new ExecutionConfig());
		int multiPojoHash = multiPojoComp.hash(pojoTestRecord);
		
		
		// pojo order is: dumm2 (str), dumm1 (int), dumm3 (double).
		TypeComparator<Tuple3<Integer, String, Double>> multiTupleComp = multiTupleType.createComparator(fieldKey.computeLogicalKeyPositions(), new boolean[] {true, true,true}, 0, new ExecutionConfig());
		int multiTupleHash = multiTupleComp.hash(multiTupleTest);
		
		Assert.assertTrue("The hashing for tuples and pojos must be the same, so that they are mixable. Also for those with multiple key fields", multiPojoHash == multiTupleHash);
		
	}

	// --------------------------------------------------------------------------------------------
	// Configuration snapshotting & reconfiguring tests
	// --------------------------------------------------------------------------------------------

	/**
	 * Verifies that reconfiguring with a config snapshot of a preceding POJO serializer
	 * with different POJO type will result in INCOMPATIBLE.
	 */
	@Test
	public void testReconfigureWithDifferentPojoType() throws Exception {
		PojoSerializer<SubTestUserClassB> pojoSerializer1 = (PojoSerializer<SubTestUserClassB>)
			TypeExtractor.getForClass(SubTestUserClassB.class).createSerializer(new ExecutionConfig());

		// snapshot configuration and serialize to bytes
		TypeSerializerConfigSnapshot pojoSerializerConfigSnapshot = pojoSerializer1.snapshotConfiguration();
		byte[] serializedConfig;
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			TypeSerializerSerializationUtil.writeSerializerConfigSnapshot(new DataOutputViewStreamWrapper(out), pojoSerializerConfigSnapshot);
			serializedConfig = out.toByteArray();
		}

		PojoSerializer<SubTestUserClassA> pojoSerializer2 = (PojoSerializer<SubTestUserClassA>)
			TypeExtractor.getForClass(SubTestUserClassA.class).createSerializer(new ExecutionConfig());

		// read configuration again from bytes
		try(ByteArrayInputStream in = new ByteArrayInputStream(serializedConfig)) {
			pojoSerializerConfigSnapshot = TypeSerializerSerializationUtil.readSerializerConfigSnapshot(
				new DataInputViewStreamWrapper(in), Thread.currentThread().getContextClassLoader());
		}

		CompatibilityResult<SubTestUserClassA> compatResult = pojoSerializer2.ensureCompatibility(pojoSerializerConfigSnapshot);
		assertTrue(compatResult.isRequiresMigration());
	}

	/**
	 * Tests that reconfiguration correctly reorders subclass registrations to their previous order.
	 */
	@Test
	public void testReconfigureDifferentSubclassRegistrationOrder() throws Exception {
		ExecutionConfig executionConfig = new ExecutionConfig();
		executionConfig.registerPojoType(SubTestUserClassA.class);
		executionConfig.registerPojoType(SubTestUserClassB.class);

		PojoSerializer<TestUserClass> pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(executionConfig);

		// get original registration ids
		int subClassATag = pojoSerializer.getRegisteredClasses().get(SubTestUserClassA.class);
		int subClassBTag = pojoSerializer.getRegisteredClasses().get(SubTestUserClassB.class);

		// snapshot configuration and serialize to bytes
		TypeSerializerConfigSnapshot pojoSerializerConfigSnapshot = pojoSerializer.snapshotConfiguration();
		byte[] serializedConfig;
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			TypeSerializerSerializationUtil.writeSerializerConfigSnapshot(new DataOutputViewStreamWrapper(out), pojoSerializerConfigSnapshot);
			serializedConfig = out.toByteArray();
		}

		// use new config and instantiate new PojoSerializer
		executionConfig = new ExecutionConfig();
		executionConfig.registerPojoType(SubTestUserClassB.class); // test with B registered before A
		executionConfig.registerPojoType(SubTestUserClassA.class);

		pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(executionConfig);

		// read configuration from bytes
		try(ByteArrayInputStream in = new ByteArrayInputStream(serializedConfig)) {
			pojoSerializerConfigSnapshot = TypeSerializerSerializationUtil.readSerializerConfigSnapshot(
				new DataInputViewStreamWrapper(in), Thread.currentThread().getContextClassLoader());
		}

		CompatibilityResult<TestUserClass> compatResult = pojoSerializer.ensureCompatibility(pojoSerializerConfigSnapshot);
		assertTrue(!compatResult.isRequiresMigration());

		// reconfigure - check reconfiguration result and that registration ids remains the same
		//assertEquals(ReconfigureResult.COMPATIBLE, pojoSerializer.reconfigure(pojoSerializerConfigSnapshot));
		assertEquals(subClassATag, pojoSerializer.getRegisteredClasses().get(SubTestUserClassA.class).intValue());
		assertEquals(subClassBTag, pojoSerializer.getRegisteredClasses().get(SubTestUserClassB.class).intValue());
	}

	/**
	 * Tests that reconfiguration repopulates previously cached subclass serializers.
	 */
	@Test
	public void testReconfigureRepopulateNonregisteredSubclassSerializerCache() throws Exception {
		// don't register any subclasses
		PojoSerializer<TestUserClass> pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(new ExecutionConfig());

		// create cached serializers for SubTestUserClassA and SubTestUserClassB
		pojoSerializer.getSubclassSerializer(SubTestUserClassA.class);
		pojoSerializer.getSubclassSerializer(SubTestUserClassB.class);

		assertEquals(2, pojoSerializer.getSubclassSerializerCache().size());
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassA.class));
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassB.class));

		// snapshot configuration and serialize to bytes
		TypeSerializerConfigSnapshot pojoSerializerConfigSnapshot = pojoSerializer.snapshotConfiguration();
		byte[] serializedConfig;
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			TypeSerializerSerializationUtil.writeSerializerConfigSnapshot(new DataOutputViewStreamWrapper(out), pojoSerializerConfigSnapshot);
			serializedConfig = out.toByteArray();
		}

		// instantiate new PojoSerializer

		pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(new ExecutionConfig());

		// read configuration from bytes
		try(ByteArrayInputStream in = new ByteArrayInputStream(serializedConfig)) {
			pojoSerializerConfigSnapshot = TypeSerializerSerializationUtil.readSerializerConfigSnapshot(
				new DataInputViewStreamWrapper(in), Thread.currentThread().getContextClassLoader());
		}

		// reconfigure - check reconfiguration result and that subclass serializer cache is repopulated
		CompatibilityResult<TestUserClass> compatResult = pojoSerializer.ensureCompatibility(pojoSerializerConfigSnapshot);
		assertFalse(compatResult.isRequiresMigration());
		assertEquals(2, pojoSerializer.getSubclassSerializerCache().size());
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassA.class));
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassB.class));
	}

	/**
	 * Tests that:
	 *  - Previous Pojo serializer did not have registrations, and created cached serializers for subclasses
	 *  - On restore, it had those subclasses registered
	 *
	 * In this case, after reconfiguration, the cache should be repopulated, and registrations should
	 * also exist for the subclasses.
	 *
	 * Note: the cache still needs to be repopulated because previous data of those subclasses were
	 * written with the cached serializers. In this case, the repopulated cache has reconfigured serializers
	 * for the subclasses so that previous written data can be read, but the registered serializers
	 * for the subclasses do not necessarily need to be reconfigured since they will only be used to
	 * write new data.
	 */
	@Test
	public void testReconfigureWithPreviouslyNonregisteredSubclasses() throws Exception {
		// don't register any subclasses at first
		PojoSerializer<TestUserClass> pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(new ExecutionConfig());

		// create cached serializers for SubTestUserClassA and SubTestUserClassB
		pojoSerializer.getSubclassSerializer(SubTestUserClassA.class);
		pojoSerializer.getSubclassSerializer(SubTestUserClassB.class);

		// make sure serializers are in cache
		assertEquals(2, pojoSerializer.getSubclassSerializerCache().size());
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassA.class));
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassB.class));

		// make sure that registrations are empty
		assertTrue(pojoSerializer.getRegisteredClasses().isEmpty());
		assertEquals(0, pojoSerializer.getRegisteredSerializers().length);

		// snapshot configuration and serialize to bytes
		TypeSerializerConfigSnapshot pojoSerializerConfigSnapshot = pojoSerializer.snapshotConfiguration();
		byte[] serializedConfig;
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			TypeSerializerSerializationUtil.writeSerializerConfigSnapshot(new DataOutputViewStreamWrapper(out), pojoSerializerConfigSnapshot);
			serializedConfig = out.toByteArray();
		}

		// instantiate new PojoSerializer, with new execution config that has the subclass registrations
		ExecutionConfig newExecutionConfig = new ExecutionConfig();
		newExecutionConfig.registerPojoType(SubTestUserClassA.class);
		newExecutionConfig.registerPojoType(SubTestUserClassB.class);
		pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(newExecutionConfig);

		// read configuration from bytes
		try(ByteArrayInputStream in = new ByteArrayInputStream(serializedConfig)) {
			pojoSerializerConfigSnapshot = TypeSerializerSerializationUtil.readSerializerConfigSnapshot(
				new DataInputViewStreamWrapper(in), Thread.currentThread().getContextClassLoader());
		}

		// reconfigure - check reconfiguration result and that
		// 1) subclass serializer cache is repopulated
		// 2) registrations also contain the now registered subclasses
		CompatibilityResult<TestUserClass> compatResult = pojoSerializer.ensureCompatibility(pojoSerializerConfigSnapshot);
		assertFalse(compatResult.isRequiresMigration());
		assertEquals(2, pojoSerializer.getSubclassSerializerCache().size());
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassA.class));
		assertTrue(pojoSerializer.getSubclassSerializerCache().containsKey(SubTestUserClassB.class));
		assertEquals(2, pojoSerializer.getRegisteredClasses().size());
		assertTrue(pojoSerializer.getRegisteredClasses().containsKey(SubTestUserClassA.class));
		assertTrue(pojoSerializer.getRegisteredClasses().containsKey(SubTestUserClassB.class));
	}

	/**
	 * Verifies that reconfiguration reorders the fields of the new Pojo serializer to remain the same.
	 */
	@Test
	public void testReconfigureWithDifferentFieldOrder() throws Exception {
		Field[] mockOriginalFieldOrder = {
			TestUserClass.class.getField("dumm4"),
			TestUserClass.class.getField("dumm3"),
			TestUserClass.class.getField("nestedClass"),
			TestUserClass.class.getField("dumm1"),
			TestUserClass.class.getField("dumm2"),
			TestUserClass.class.getField("dumm5"),
		};

		// creating this serializer just for generating config snapshots of the field serializers
		PojoSerializer<TestUserClass> ser = (PojoSerializer<TestUserClass>) type.createSerializer(new ExecutionConfig());

		LinkedHashMap<Field, Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> mockOriginalFieldToSerializerConfigSnapshot =
			new LinkedHashMap<>(mockOriginalFieldOrder.length);
		mockOriginalFieldToSerializerConfigSnapshot.put(
			mockOriginalFieldOrder[0],
			new Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>(
				ser.getFieldSerializers()[3],
				ser.getFieldSerializers()[3].snapshotConfiguration()));
		mockOriginalFieldToSerializerConfigSnapshot.put(
			mockOriginalFieldOrder[1],
			new Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>(
				ser.getFieldSerializers()[2],
				ser.getFieldSerializers()[2].snapshotConfiguration()));
		mockOriginalFieldToSerializerConfigSnapshot.put(
			mockOriginalFieldOrder[2],
			new Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>(
				ser.getFieldSerializers()[5],
				ser.getFieldSerializers()[5].snapshotConfiguration()));
		mockOriginalFieldToSerializerConfigSnapshot.put(
			mockOriginalFieldOrder[3],
			new Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>(
				ser.getFieldSerializers()[0],
				ser.getFieldSerializers()[0].snapshotConfiguration()));
		mockOriginalFieldToSerializerConfigSnapshot.put(
			mockOriginalFieldOrder[4],
			new Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>(
				ser.getFieldSerializers()[1],
				ser.getFieldSerializers()[1].snapshotConfiguration()));
		mockOriginalFieldToSerializerConfigSnapshot.put(
			mockOriginalFieldOrder[5],
			new Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>(
				ser.getFieldSerializers()[4],
				ser.getFieldSerializers()[4].snapshotConfiguration()));

		PojoSerializer<TestUserClass> pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(new ExecutionConfig());

		assertEquals(TestUserClass.class.getField("dumm1"), pojoSerializer.getFields()[0]);
		assertEquals(TestUserClass.class.getField("dumm2"), pojoSerializer.getFields()[1]);
		assertEquals(TestUserClass.class.getField("dumm3"), pojoSerializer.getFields()[2]);
		assertEquals(TestUserClass.class.getField("dumm4"), pojoSerializer.getFields()[3]);
		assertEquals(TestUserClass.class.getField("dumm5"), pojoSerializer.getFields()[4]);
		assertEquals(TestUserClass.class.getField("nestedClass"), pojoSerializer.getFields()[5]);

		PojoSerializer.PojoSerializerConfigSnapshot<TestUserClass> mockPreviousConfigSnapshot =
			new PojoSerializer.PojoSerializerConfigSnapshot<>(
				TestUserClass.class,
				mockOriginalFieldToSerializerConfigSnapshot, // this mocks the previous field order
				new LinkedHashMap<Class<?>, Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>>(), // empty; irrelevant for this test
				new HashMap<Class<?>, Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>>()); // empty; irrelevant for this test

		// reconfigure - check reconfiguration result and that fields are reordered to the previous order
		CompatibilityResult<TestUserClass> compatResult = pojoSerializer.ensureCompatibility(mockPreviousConfigSnapshot);
		assertFalse(compatResult.isRequiresMigration());
		int i = 0;
		for (Field field : mockOriginalFieldOrder) {
			assertEquals(field, pojoSerializer.getFields()[i]);
			i++;
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testSerializerSerializationFailureResilience() throws Exception{
		PojoSerializer<TestUserClass> pojoSerializer = (PojoSerializer<TestUserClass>) type.createSerializer(new ExecutionConfig());

		// snapshot configuration and serialize to bytes
		PojoSerializer.PojoSerializerConfigSnapshot<TestUserClass> config = pojoSerializer.snapshotConfiguration();
		byte[] serializedConfig;
		try (
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			TypeSerializerSerializationUtil.writeSerializerConfigSnapshot(new DataOutputViewStreamWrapper(out), config);
			serializedConfig = out.toByteArray();
		}

		// mock failure when deserializing serializers
		TypeSerializerSerializationUtil.TypeSerializerSerializationProxy<?> mockProxy =
			mock(TypeSerializerSerializationUtil.TypeSerializerSerializationProxy.class);
		doThrow(new IOException()).when(mockProxy).read(any(DataInputViewStreamWrapper.class));
		PowerMockito.whenNew(TypeSerializerSerializationUtil.TypeSerializerSerializationProxy.class).withAnyArguments().thenReturn(mockProxy);

		// read configuration from bytes
		PojoSerializer.PojoSerializerConfigSnapshot<TestUserClass> deserializedConfig;
		try(ByteArrayInputStream in = new ByteArrayInputStream(serializedConfig)) {
			deserializedConfig = (PojoSerializer.PojoSerializerConfigSnapshot<TestUserClass>)
				TypeSerializerSerializationUtil.readSerializerConfigSnapshot(
					new DataInputViewStreamWrapper(in), Thread.currentThread().getContextClassLoader());
		}

		Assert.assertFalse(pojoSerializer.ensureCompatibility(deserializedConfig).isRequiresMigration());
		verifyPojoSerializerConfigSnapshotWithSerializerSerializationFailure(config, deserializedConfig);
	}

	private static void verifyPojoSerializerConfigSnapshotWithSerializerSerializationFailure(
			PojoSerializer.PojoSerializerConfigSnapshot<?> original,
			PojoSerializer.PojoSerializerConfigSnapshot<?> deserializedConfig) {

		LinkedHashMap<Field, Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> originalFieldSerializersAndConfs =
				original.getFieldToSerializerConfigSnapshot();
		for (Map.Entry<Field, Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> entry
				: deserializedConfig.getFieldToSerializerConfigSnapshot().entrySet()) {

			Assert.assertEquals(null, entry.getValue().f0);

			if (entry.getValue().f1 instanceof PojoSerializer.PojoSerializerConfigSnapshot) {
				verifyPojoSerializerConfigSnapshotWithSerializerSerializationFailure(
					(PojoSerializer.PojoSerializerConfigSnapshot<?>) originalFieldSerializersAndConfs.get(entry.getKey()).f1,
					(PojoSerializer.PojoSerializerConfigSnapshot<?>) entry.getValue().f1);
			} else {
				Assert.assertEquals(originalFieldSerializersAndConfs.get(entry.getKey()).f1, entry.getValue().f1);
			}
		}

		LinkedHashMap<Class<?>, Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> originalRegistrations =
				original.getRegisteredSubclassesToSerializerConfigSnapshots();

		for (Map.Entry<Class<?>, Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> entry
				: deserializedConfig.getRegisteredSubclassesToSerializerConfigSnapshots().entrySet()) {

			Assert.assertEquals(null, entry.getValue().f0);

			if (entry.getValue().f1 instanceof PojoSerializer.PojoSerializerConfigSnapshot) {
				verifyPojoSerializerConfigSnapshotWithSerializerSerializationFailure(
					(PojoSerializer.PojoSerializerConfigSnapshot<?>) originalRegistrations.get(entry.getKey()).f1,
					(PojoSerializer.PojoSerializerConfigSnapshot<?>) entry.getValue().f1);
			} else {
				Assert.assertEquals(originalRegistrations.get(entry.getKey()).f1, entry.getValue().f1);
			}
		}
	}
}
