/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionKind;
import io.prestosql.metadata.LongVariableConstraint;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.aggregation.AggregationImplementation;
import io.prestosql.operator.aggregation.AggregationMetadata;
import io.prestosql.operator.aggregation.InternalAggregationFunction;
import io.prestosql.operator.aggregation.LazyAccumulatorFactoryBinder;
import io.prestosql.operator.aggregation.ParametricAggregation;
import io.prestosql.operator.aggregation.state.NullableDoubleState;
import io.prestosql.operator.aggregation.state.NullableDoubleStateSerializer;
import io.prestosql.operator.aggregation.state.NullableLongState;
import io.prestosql.operator.aggregation.state.SliceState;
import io.prestosql.operator.annotations.LiteralImplementationDependency;
import io.prestosql.operator.annotations.OperatorImplementationDependency;
import io.prestosql.operator.annotations.TypeImplementationDependency;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.AccumulatorStateSerializer;
import io.prestosql.spi.function.AggregationFunction;
import io.prestosql.spi.function.AggregationState;
import io.prestosql.spi.function.AggregationStateSerializerFactory;
import io.prestosql.spi.function.BlockIndex;
import io.prestosql.spi.function.BlockPosition;
import io.prestosql.spi.function.CombineFunction;
import io.prestosql.spi.function.Description;
import io.prestosql.spi.function.InputFunction;
import io.prestosql.spi.function.LiteralParameter;
import io.prestosql.spi.function.LiteralParameters;
import io.prestosql.spi.function.OperatorDependency;
import io.prestosql.spi.function.OutputFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.function.TypeParameter;
import io.prestosql.spi.function.TypeParameterSpecialization;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.spi.type.TypeSignatureParameter;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.type.Constraint;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandle;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.metadata.Signature.typeVariable;
import static io.prestosql.operator.aggregation.AggregationFromAnnotationsParser.parseFunctionDefinition;
import static io.prestosql.operator.aggregation.AggregationFromAnnotationsParser.parseFunctionDefinitions;
import static io.prestosql.spi.function.OperatorType.LESS_THAN;
import static io.prestosql.spi.type.StandardTypes.ARRAY;
import static io.prestosql.spi.type.StandardTypes.DOUBLE;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestAnnotationEngineForAggregates
        extends TestAnnotationEngine
{
    private static final Metadata METADATA = createTestMetadataManager();

    @AggregationFunction("simple_exact_aggregate")
    @Description("Simple exact aggregate description")
    public static final class ExactAggregationFunction
    {
        @InputFunction
        public static void input(@AggregationState NullableDoubleState state, @SqlType(DOUBLE) double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(@AggregationState NullableDoubleState combine1, @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction(DOUBLE)
        public static void output(@AggregationState NullableDoubleState state, BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testSimpleExactAggregationParse()
    {
        Signature expectedSignature = new Signature(
                "simple_exact_aggregate",
                FunctionKind.AGGREGATE,
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()));

        ParametricAggregation aggregation = parseFunctionDefinition(ExactAggregationFunction.class);
        assertEquals(aggregation.getDescription(), "Simple exact aggregate description");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();
        assertImplementationCount(implementations, 1, 0, 0);
        AggregationImplementation implementation = getOnlyElement(implementations.getExactImplementations().values());
        assertFalse(implementation.getStateSerializerFactory().isPresent());
        assertEquals(implementation.getDefinitionClass(), ExactAggregationFunction.class);
        assertDependencyCount(implementation, 0, 0, 0);
        assertFalse(implementation.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "simple_exact_aggregate");
    }

    @AggregationFunction("simple_exact_aggregate_aggregation_state_moved")
    @Description("Simple exact function which has @AggregationState on different than first positions")
    public static final class StateOnDifferentThanFirstPositionAggregationFunction
    {
        @InputFunction
        public static void input(@SqlType(DOUBLE) double value, @AggregationState NullableDoubleState state)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(@AggregationState NullableDoubleState combine1, @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction(DOUBLE)
        public static void output(BlockBuilder out, @AggregationState NullableDoubleState state)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testStateOnDifferentThanFirstPositionAggregationParse()
    {
        Signature expectedSignature = new Signature(
                "simple_exact_aggregate_aggregation_state_moved",
                FunctionKind.AGGREGATE,
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()));

        ParametricAggregation aggregation = parseFunctionDefinition(StateOnDifferentThanFirstPositionAggregationFunction.class);
        assertEquals(aggregation.getSignature(), expectedSignature);

        AggregationImplementation implementation = getOnlyElement(aggregation.getImplementations().getExactImplementations().values());
        assertEquals(implementation.getDefinitionClass(), StateOnDifferentThanFirstPositionAggregationFunction.class);
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL, AggregationMetadata.ParameterMetadata.ParameterType.STATE);
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));
    }

    @AggregationFunction("no_aggregation_state_aggregate")
    @Description("Aggregate with no @AggregationState annotations")
    public static final class NotAnnotatedAggregateStateAggregationFunction
    {
        @InputFunction
        public static void input(NullableDoubleState state, @SqlType(DOUBLE) double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(NullableDoubleState combine1, NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction(DOUBLE)
        public static void output(NullableDoubleState state, BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testNotAnnotatedAggregateStateAggregationParse()
    {
        ParametricAggregation aggregation = parseFunctionDefinition(NotAnnotatedAggregateStateAggregationFunction.class);

        AggregationImplementation implementation = getOnlyElement(aggregation.getImplementations().getExactImplementations().values());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "no_aggregation_state_aggregate");
    }

    @AggregationFunction("custom_serializer_aggregate")
    @Description("Aggregate with no @AggregationState annotations")
    public static final class CustomStateSerializerAggregationFunction
    {
        public static class CustomSerializer
                extends NullableDoubleStateSerializer
        {
        }

        @InputFunction
        public static void input(
                @AggregationState NullableDoubleState state,
                @SqlType(DOUBLE) double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableDoubleState combine1,
                @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction(DOUBLE)
        public static void output(@AggregationState NullableDoubleState state, BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @AggregationStateSerializerFactory(NullableDoubleState.class)
        public static CustomSerializer createSerializer()
        {
            return new CustomSerializer();
        }
    }

    @Test
    public void testCustomStateSerializerAggregationParse()
    {
        ParametricAggregation aggregation = parseFunctionDefinition(CustomStateSerializerAggregationFunction.class);
        AggregationImplementation implementation = getOnlyElement(aggregation.getImplementations().getExactImplementations().values());
        assertTrue(implementation.getStateSerializerFactory().isPresent());

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().build(), 1, METADATA);
        AccumulatorStateSerializer<?> createdSerializer = getOnlyElement(((LazyAccumulatorFactoryBinder) specialized.getAccumulatorFactoryBinder())
                .getGenericAccumulatorFactoryBinder().getStateDescriptors()).getSerializer();
        Class<?> serializerFactory = implementation.getStateSerializerFactory().get().type().returnType();
        assertTrue(serializerFactory.isInstance(createdSerializer));
    }

    @AggregationFunction(value = "custom_decomposable_aggregate", decomposable = false)
    @Description("Aggregate with Decomposable=false")
    public static final class NotDecomposableAggregationFunction
    {
        @InputFunction
        public static void input(
                @AggregationState NullableDoubleState state,
                @SqlType(DOUBLE) double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableDoubleState combine1,
                @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction(DOUBLE)
        public static void output(
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @AggregationStateSerializerFactory(NullableDoubleState.class)
        public static AccumulatorStateSerializer<?> createSerializer()
        {
            return new CustomStateSerializerAggregationFunction.CustomSerializer();
        }
    }

    @Test
    public void testNotDecomposableAggregationParse()
    {
        Signature expectedSignature = new Signature(
                "custom_decomposable_aggregate",
                FunctionKind.AGGREGATE,
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()));

        ParametricAggregation aggregation = parseFunctionDefinition(NotDecomposableAggregationFunction.class);
        assertEquals(aggregation.getDescription(), "Aggregate with Decomposable=false");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertFalse(specialized.isDecomposable());
        assertEquals(specialized.name(), "custom_decomposable_aggregate");
    }

    @AggregationFunction("simple_generic_implementations")
    @Description("Simple aggregate with two generic implementations")
    public static final class GenericAggregationFunction
    {
        @InputFunction
        @TypeParameter("T")
        public static void input(
                @AggregationState NullableDoubleState state,
                @SqlType("T") double value)
        {
            // noop this is only for annotation testing puproses
        }

        @InputFunction
        @TypeParameter("T")
        public static void input(
                @AggregationState NullableLongState state,
                @SqlType("T") long value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableLongState state,
                @AggregationState NullableLongState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableDoubleState state,
                @AggregationState NullableDoubleState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("T")
        public static void output(
                @AggregationState NullableLongState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("T")
        public static void output(
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testSimpleGenericAggregationFunctionParse()
    {
        Signature expectedSignature = new Signature(
                "simple_generic_implementations",
                FunctionKind.AGGREGATE,
                ImmutableList.of(typeVariable("T")),
                ImmutableList.of(),
                new TypeSignature("T"),
                ImmutableList.of(new TypeSignature("T")),
                false);

        ParametricAggregation aggregation = parseFunctionDefinition(GenericAggregationFunction.class);
        assertEquals(aggregation.getDescription(), "Simple aggregate with two generic implementations");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();
        assertImplementationCount(implementations, 0, 0, 2);
        AggregationImplementation implementationDouble = implementations.getGenericImplementations().stream().filter(impl -> impl.getStateClass() == NullableDoubleState.class).collect(toImmutableList()).get(0);
        assertFalse(implementationDouble.getStateSerializerFactory().isPresent());
        assertEquals(implementationDouble.getDefinitionClass(), GenericAggregationFunction.class);
        assertDependencyCount(implementationDouble, 0, 0, 0);
        assertFalse(implementationDouble.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementationDouble.getInputParameterMetadataTypes().equals(expectedMetadataTypes));
        assertEquals(implementationDouble.getStateClass(), NullableDoubleState.class);

        AggregationImplementation implementationLong = implementations.getGenericImplementations().stream().filter(impl -> impl.getStateClass() == NullableLongState.class).collect(toImmutableList()).get(0);
        assertFalse(implementationLong.getStateSerializerFactory().isPresent());
        assertEquals(implementationLong.getDefinitionClass(), GenericAggregationFunction.class);
        assertDependencyCount(implementationLong, 0, 0, 0);
        assertFalse(implementationLong.hasSpecializedTypeParameters());
        assertTrue(implementationLong.getInputParameterMetadataTypes().equals(expectedMetadataTypes));
        assertEquals(implementationLong.getStateClass(), NullableLongState.class);

        InternalAggregationFunction specialized = aggregation.specialize(
                BoundVariables.builder().setTypeVariable("T", DoubleType.DOUBLE).build(),
                1,
                METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.getParameterTypes().equals(ImmutableList.of(DoubleType.DOUBLE)));
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "simple_generic_implementations");
    }

    @AggregationFunction("block_input_aggregate")
    @Description("Simple aggregate with @BlockPosition usage")
    public static final class BlockInputAggregationFunction
    {
        @InputFunction
        public static void input(
                @AggregationState NullableDoubleState state,
                @BlockPosition @SqlType(DOUBLE) Block value,
                @BlockIndex int id)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableDoubleState combine1,
                @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction(DOUBLE)
        public static void output(
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testSimpleBlockInputAggregationParse()
    {
        Signature expectedSignature = new Signature(
                "block_input_aggregate",
                FunctionKind.AGGREGATE,
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()));

        ParametricAggregation aggregation = parseFunctionDefinition(BlockInputAggregationFunction.class);
        assertEquals(aggregation.getDescription(), "Simple aggregate with @BlockPosition usage");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();
        assertImplementationCount(implementations, 1, 0, 0);
        AggregationImplementation implementation = getOnlyElement(implementations.getExactImplementations().values());
        assertFalse(implementation.getStateSerializerFactory().isPresent());
        assertEquals(implementation.getDefinitionClass(), BlockInputAggregationFunction.class);
        assertDependencyCount(implementation, 0, 0, 0);
        assertFalse(implementation.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INPUT_CHANNEL, AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX);
        assertEquals(implementation.getInputParameterMetadataTypes(), expectedMetadataTypes);

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "block_input_aggregate");
    }

    @AggregationFunction("implicit_specialized_aggregate")
    @Description("Simple implicit specialized aggregate")
    public static final class ImplicitSpecializedAggregationFunction
    {
        @InputFunction
        @TypeParameter("T")
        public static void input(
                @AggregationState NullableDoubleState state,
                @SqlType("array(T)") Block arrayBlock, @SqlType("T") double additionalValue)
        {
            // noop this is only for annotation testing puproses
        }

        @InputFunction
        @TypeParameter("T")
        public static void input(
                @AggregationState NullableLongState state,
                @SqlType("array(T)") Block arrayBlock, @SqlType("T") long additionalValue)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableLongState state,
                @AggregationState NullableLongState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableDoubleState state,
                @AggregationState NullableDoubleState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("T")
        public static void output(
                @AggregationState NullableLongState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("T")
        public static void output(
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test(enabled = false) // TODO this is not yet supported
    public void testSimpleImplicitSpecializedAggregationParse()
    {
        Signature expectedSignature = new Signature(
                "implicit_specialized_aggregate",
                FunctionKind.AGGREGATE,
                ImmutableList.of(typeVariable("T")),
                ImmutableList.of(),
                new TypeSignature("T"),
                ImmutableList.of(new TypeSignature(ARRAY, TypeSignatureParameter.of(new TypeSignature("T"))), new TypeSignature("T")),
                false);

        ParametricAggregation aggregation = parseFunctionDefinition(ImplicitSpecializedAggregationFunction.class);
        assertEquals(aggregation.getDescription(), "Simple implicit specialized aggregate");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();
        assertImplementationCount(implementations, 0, 0, 2);

        AggregationImplementation implementation1 = implementations.getSpecializedImplementations().get(0);
        assertTrue(implementation1.hasSpecializedTypeParameters());
        assertFalse(implementation1.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation1.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        AggregationImplementation implementation2 = implementations.getSpecializedImplementations().get(1);
        assertTrue(implementation2.hasSpecializedTypeParameters());
        assertFalse(implementation2.hasSpecializedTypeParameters());
        assertTrue(implementation2.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().setTypeVariable("T", DoubleType.DOUBLE).build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "implicit_specialized_aggregate");
    }

    @AggregationFunction("explicit_specialized_aggregate")
    @Description("Simple explicit specialized aggregate")
    public static final class ExplicitSpecializedAggregationFunction
    {
        @InputFunction
        @TypeParameterSpecialization(name = "T", nativeContainerType = double.class)
        @TypeParameter("T")
        public static void input(
                @AggregationState NullableDoubleState state,
                @SqlType("array(T)") Block arrayBlock)
        {
            // noop this is only for annotation testing puproses
        }

        @InputFunction
        @TypeParameter("T")
        public static void input(
                @AggregationState NullableLongState state,
                @SqlType("array(T)") Block arrayBlock)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableLongState state,
                @AggregationState NullableLongState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableDoubleState state,
                @AggregationState NullableDoubleState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("T")
        public static void output(
                @AggregationState NullableLongState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("T")
        public static void output(
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test(enabled = false) // TODO this is not yet supported
    public void testSimpleExplicitSpecializedAggregationParse()
    {
        Signature expectedSignature = new Signature(
                "explicit_specialized_aggregate",
                FunctionKind.AGGREGATE,
                ImmutableList.of(typeVariable("T")),
                ImmutableList.of(),
                new TypeSignature("T"),
                ImmutableList.of(new TypeSignature(ARRAY, TypeSignatureParameter.of(new TypeSignature("T")))),
                false);

        ParametricAggregation aggregation = parseFunctionDefinition(ExplicitSpecializedAggregationFunction.class);
        assertEquals(aggregation.getDescription(), "Simple explicit specialized aggregate");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();
        assertImplementationCount(implementations, 0, 1, 1);
        AggregationImplementation implementation1 = implementations.getSpecializedImplementations().get(0);
        assertTrue(implementation1.hasSpecializedTypeParameters());
        assertFalse(implementation1.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation1.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        AggregationImplementation implementation2 = implementations.getSpecializedImplementations().get(1);
        assertTrue(implementation2.hasSpecializedTypeParameters());
        assertFalse(implementation2.hasSpecializedTypeParameters());
        assertTrue(implementation2.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().setTypeVariable("T", DoubleType.DOUBLE).build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "implicit_specialized_aggregate");
    }

    @AggregationFunction("multi_output_aggregate")
    @Description("Simple multi output function aggregate generic description")
    public static final class MultiOutputAggregationFunction
    {
        @InputFunction
        public static void input(
                @AggregationState NullableDoubleState state,
                @SqlType(DOUBLE) double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState NullableDoubleState combine1,
                @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @AggregationFunction("multi_output_aggregate_1")
        @Description("Simple multi output function aggregate specialized description")
        @OutputFunction(DOUBLE)
        public static void output1(
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @AggregationFunction("multi_output_aggregate_2")
        @OutputFunction(DOUBLE)
        public static void output2(
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testMultiOutputAggregationParse()
    {
        Signature expectedSignature1 = new Signature(
                "multi_output_aggregate_1",
                FunctionKind.AGGREGATE,
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()));

        Signature expectedSignature2 = new Signature(
                "multi_output_aggregate_2",
                FunctionKind.AGGREGATE,
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()));

        List<ParametricAggregation> aggregations = parseFunctionDefinitions(MultiOutputAggregationFunction.class);
        assertEquals(aggregations.size(), 2);

        ParametricAggregation aggregation1 = aggregations.stream().filter(aggregate -> aggregate.getSignature().getName().equals("multi_output_aggregate_1")).collect(toImmutableList()).get(0);
        assertEquals(aggregation1.getSignature(), expectedSignature1);
        assertEquals(aggregation1.getDescription(), "Simple multi output function aggregate specialized description");

        ParametricAggregation aggregation2 = aggregations.stream().filter(aggregate -> aggregate.getSignature().getName().equals("multi_output_aggregate_2")).collect(toImmutableList()).get(0);
        assertEquals(aggregation2.getSignature(), expectedSignature2);
        assertEquals(aggregation2.getDescription(), "Simple multi output function aggregate generic description");

        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);

        ParametricImplementationsGroup<AggregationImplementation> implementations1 = aggregation1.getImplementations();
        assertImplementationCount(implementations1, 1, 0, 0);

        ParametricImplementationsGroup<AggregationImplementation> implementations2 = aggregation2.getImplementations();
        assertImplementationCount(implementations2, 1, 0, 0);

        AggregationImplementation implementation = getOnlyElement(implementations1.getExactImplementations().values());
        assertFalse(implementation.getStateSerializerFactory().isPresent());
        assertEquals(implementation.getDefinitionClass(), MultiOutputAggregationFunction.class);
        assertDependencyCount(implementation, 0, 0, 0);
        assertFalse(implementation.hasSpecializedTypeParameters());
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation1.specialize(BoundVariables.builder().build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "multi_output_aggregate_1");
    }

    @AggregationFunction("inject_operator_aggregate")
    @Description("Simple aggregate with operator injected")
    public static final class InjectOperatorAggregateFunction
    {
        @InputFunction
        public static void input(
                @OperatorDependency(operator = LESS_THAN, returnType = StandardTypes.BOOLEAN, argumentTypes = {DOUBLE, DOUBLE}) MethodHandle methodHandle,
                @AggregationState NullableDoubleState state,
                @SqlType(DOUBLE) double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @OperatorDependency(operator = LESS_THAN, returnType = StandardTypes.BOOLEAN, argumentTypes = {DOUBLE, DOUBLE}) MethodHandle methodHandle,
                @AggregationState NullableDoubleState combine1,
                @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction(DOUBLE)
        public static void output(
                @OperatorDependency(operator = LESS_THAN, returnType = StandardTypes.BOOLEAN, argumentTypes = {DOUBLE, DOUBLE}) MethodHandle methodHandle,
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @AggregationStateSerializerFactory(NullableDoubleState.class)
        public static CustomStateSerializerAggregationFunction.CustomSerializer createSerializer(
                @OperatorDependency(operator = LESS_THAN, returnType = StandardTypes.BOOLEAN, argumentTypes = {DOUBLE, DOUBLE}) MethodHandle methodHandle)
        {
            return new CustomStateSerializerAggregationFunction.CustomSerializer();
        }
    }

    @Test
    public void testInjectOperatorAggregateParse()
    {
        Signature expectedSignature = new Signature(
                "inject_operator_aggregate",
                FunctionKind.AGGREGATE,
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()));

        ParametricAggregation aggregation = parseFunctionDefinition(InjectOperatorAggregateFunction.class);
        assertEquals(aggregation.getDescription(), "Simple aggregate with operator injected");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();

        AggregationImplementation implementation = getOnlyElement(implementations.getExactImplementations().values());

        assertTrue(implementation.getStateSerializerFactory().isPresent());
        assertEquals(implementation.getDefinitionClass(), InjectOperatorAggregateFunction.class);

        assertDependencyCount(implementation, 1, 1, 1);
        assertEquals(implementation.getStateSerializerFactoryDependencies().size(), 1);

        assertTrue(implementation.getInputDependencies().get(0) instanceof OperatorImplementationDependency);
        assertTrue(implementation.getCombineDependencies().get(0) instanceof OperatorImplementationDependency);
        assertTrue(implementation.getOutputDependencies().get(0) instanceof OperatorImplementationDependency);
        assertTrue(implementation.getStateSerializerFactoryDependencies().get(0) instanceof OperatorImplementationDependency);

        assertFalse(implementation.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "inject_operator_aggregate");
    }

    @AggregationFunction("inject_type_aggregate")
    @Description("Simple aggregate with type injected")
    public static final class InjectTypeAggregateFunction
    {
        @InputFunction
        @TypeParameter("T")
        public static void input(
                @TypeParameter("T") Type type,
                @AggregationState NullableDoubleState state,
                @SqlType("T") double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @TypeParameter("T") Type type,
                @AggregationState NullableDoubleState combine1,
                @AggregationState NullableDoubleState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("T")
        public static void output(
                @TypeParameter("T") Type type,
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @AggregationStateSerializerFactory(NullableDoubleState.class)
        public static CustomStateSerializerAggregationFunction.CustomSerializer createSerializer(
                @TypeParameter("T") Type type)
        {
            return new CustomStateSerializerAggregationFunction.CustomSerializer();
        }
    }

    @Test
    public void testInjectTypeAggregateParse()
    {
        Signature expectedSignature = new Signature(
                "inject_type_aggregate",
                FunctionKind.AGGREGATE,
                ImmutableList.of(typeVariable("T")),
                ImmutableList.of(),
                new TypeSignature("T"),
                ImmutableList.of(new TypeSignature("T")),
                false);

        ParametricAggregation aggregation = parseFunctionDefinition(InjectTypeAggregateFunction.class);
        assertEquals(aggregation.getDescription(), "Simple aggregate with type injected");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();

        assertEquals(implementations.getGenericImplementations().size(), 1);
        AggregationImplementation implementation = implementations.getGenericImplementations().get(0);

        assertTrue(implementation.getStateSerializerFactory().isPresent());
        assertEquals(implementation.getDefinitionClass(), InjectTypeAggregateFunction.class);

        assertDependencyCount(implementation, 1, 1, 1);
        assertEquals(implementation.getStateSerializerFactoryDependencies().size(), 1);

        assertTrue(implementation.getInputDependencies().get(0) instanceof TypeImplementationDependency);
        assertTrue(implementation.getCombineDependencies().get(0) instanceof TypeImplementationDependency);
        assertTrue(implementation.getOutputDependencies().get(0) instanceof TypeImplementationDependency);
        assertTrue(implementation.getStateSerializerFactoryDependencies().get(0) instanceof TypeImplementationDependency);

        assertFalse(implementation.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().setTypeVariable("T", DoubleType.DOUBLE).build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "inject_type_aggregate");
    }

    @AggregationFunction("inject_literal_aggregate")
    @Description("Simple aggregate with type literal")
    public static final class InjectLiteralAggregateFunction
    {
        @InputFunction
        @LiteralParameters("x")
        public static void input(
                @LiteralParameter("x") Long varcharSize,
                @AggregationState SliceState state,
                @SqlType("varchar(x)") Slice slice)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @LiteralParameter("x") Long varcharSize,
                @AggregationState SliceState combine1,
                @AggregationState SliceState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("varchar(x)")
        public static void output(
                @LiteralParameter("x") Long varcharSize,
                @AggregationState SliceState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }

        @AggregationStateSerializerFactory(SliceState.class)
        public static CustomStateSerializerAggregationFunction.CustomSerializer createSerializer(
                @LiteralParameter("x") Long varcharSize)
        {
            return new CustomStateSerializerAggregationFunction.CustomSerializer();
        }
    }

    @Test
    public void testInjectLiteralAggregateParse()
    {
        Signature expectedSignature = new Signature(
                "inject_literal_aggregate",
                FunctionKind.AGGREGATE,
                parseTypeSignature("varchar(x)", ImmutableSet.of("x")),
                ImmutableList.of(parseTypeSignature("varchar(x)", ImmutableSet.of("x"))));

        ParametricAggregation aggregation = parseFunctionDefinition(InjectLiteralAggregateFunction.class);
        assertEquals(aggregation.getDescription(), "Simple aggregate with type literal");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();

        assertEquals(implementations.getGenericImplementations().size(), 1);
        AggregationImplementation implementation = implementations.getGenericImplementations().get(0);

        assertTrue(implementation.getStateSerializerFactory().isPresent());
        assertEquals(implementation.getDefinitionClass(), InjectLiteralAggregateFunction.class);

        assertDependencyCount(implementation, 1, 1, 1);
        assertEquals(implementation.getStateSerializerFactoryDependencies().size(), 1);

        assertTrue(implementation.getInputDependencies().get(0) instanceof LiteralImplementationDependency);
        assertTrue(implementation.getCombineDependencies().get(0) instanceof LiteralImplementationDependency);
        assertTrue(implementation.getOutputDependencies().get(0) instanceof LiteralImplementationDependency);
        assertTrue(implementation.getStateSerializerFactoryDependencies().get(0) instanceof LiteralImplementationDependency);

        assertFalse(implementation.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(BoundVariables.builder().setLongVariable("x", 17L).build(), 1, METADATA);
        assertEquals(specialized.getFinalType(), VarcharType.createVarcharType(17));
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "inject_literal_aggregate");
    }

    @AggregationFunction("parametric_aggregate_long_constraint")
    @Description("Parametric aggregate with parametric type returned")
    public static final class LongConstraintAggregateFunction
    {
        @InputFunction
        @LiteralParameters({"x", "y", "z"})
        @Constraint(variable = "z", expression = "x + y")
        public static void input(
                @AggregationState SliceState state,
                @SqlType("varchar(x)") Slice slice1,
                @SqlType("varchar(y)") Slice slice2)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @AggregationState SliceState combine1,
                @AggregationState SliceState combine2)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("varchar(z)")
        public static void output(
                @AggregationState SliceState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testLongConstraintAggregateFunctionParse()
    {
        Signature expectedSignature = new Signature(
                "parametric_aggregate_long_constraint",
                FunctionKind.AGGREGATE,
                ImmutableList.of(),
                ImmutableList.of(new LongVariableConstraint("z", "x + y")),
                parseTypeSignature("varchar(z)", ImmutableSet.of("z")),
                ImmutableList.of(parseTypeSignature("varchar(x)", ImmutableSet.of("x")),
                        parseTypeSignature("varchar(y)", ImmutableSet.of("y"))),
                false);

        ParametricAggregation aggregation = parseFunctionDefinition(LongConstraintAggregateFunction.class);
        assertEquals(aggregation.getDescription(), "Parametric aggregate with parametric type returned");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();

        assertEquals(implementations.getGenericImplementations().size(), 1);
        AggregationImplementation implementation = implementations.getGenericImplementations().get(0);

        assertTrue(!implementation.getStateSerializerFactory().isPresent());
        assertEquals(implementation.getDefinitionClass(), LongConstraintAggregateFunction.class);

        assertDependencyCount(implementation, 0, 0, 0);
        assertEquals(implementation.getStateSerializerFactoryDependencies().size(), 0);

        assertFalse(implementation.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementation.getInputParameterMetadataTypes().equals(expectedMetadataTypes));

        InternalAggregationFunction specialized = aggregation.specialize(
                BoundVariables.builder()
                        .setLongVariable("x", 17L)
                        .setLongVariable("y", 13L)
                        .setLongVariable("z", 30L)
                        .build(),
                2,
                METADATA);
        assertEquals(specialized.getFinalType(), VarcharType.createVarcharType(30));
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "parametric_aggregate_long_constraint");
    }

    @AggregationFunction("fixed_type_parameter_injection")
    @Description("Simple aggregate with fixed parameter type injected")
    public static final class FixedTypeParameterInjectionAggregateFunction
    {
        @InputFunction
        public static void input(
                @TypeParameter("ROW(ARRAY(BIGINT),ROW(ROW(CHAR)),BIGINT,MAP(BIGINT,CHAR))") Type type,
                @AggregationState NullableDoubleState state,
                @SqlType("double") double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        public static void combine(
                @TypeParameter("ROW(ARRAY(BIGINT),ROW(ROW(CHAR)),BIGINT,MAP(BIGINT,CHAR))") Type type,
                @AggregationState NullableDoubleState state,
                @AggregationState NullableDoubleState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("double")
        public static void output(
                @TypeParameter("ROW(ARRAY(BIGINT),ROW(ROW(CHAR)),BIGINT,MAP(BIGINT,CHAR))") Type type,
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testFixedTypeParameterInjectionAggregateFunctionParse()
    {
        Signature expectedSignature = new Signature(
                "fixed_type_parameter_injection",
                FunctionKind.AGGREGATE,
                ImmutableList.of(),
                ImmutableList.of(),
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()),
                false);

        ParametricAggregation aggregation = parseFunctionDefinition(FixedTypeParameterInjectionAggregateFunction.class);
        assertEquals(aggregation.getDescription(), "Simple aggregate with fixed parameter type injected");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();
        assertImplementationCount(implementations, 1, 0, 0);
        AggregationImplementation implementationDouble = implementations.getExactImplementations().get(expectedSignature);
        assertFalse(implementationDouble.getStateSerializerFactory().isPresent());
        assertEquals(implementationDouble.getDefinitionClass(), FixedTypeParameterInjectionAggregateFunction.class);
        assertDependencyCount(implementationDouble, 1, 1, 1);
        assertFalse(implementationDouble.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementationDouble.getInputParameterMetadataTypes().equals(expectedMetadataTypes));
        assertEquals(implementationDouble.getStateClass(), NullableDoubleState.class);
    }

    @AggregationFunction("partially_fixed_type_parameter_injection")
    @Description("Simple aggregate with fixed parameter type injected")
    public static final class PartiallyFixedTypeParameterInjectionAggregateFunction
    {
        @InputFunction
        @TypeParameter("T1")
        @TypeParameter("T2")
        public static void input(
                @TypeParameter("ROW(ARRAY(T1),ROW(ROW(T2)),CHAR)") Type type,
                @AggregationState NullableDoubleState state,
                @SqlType("double") double value)
        {
            // noop this is only for annotation testing puproses
        }

        @CombineFunction
        @TypeParameter("T1")
        @TypeParameter("T2")
        public static void combine(
                @TypeParameter("ROW(ARRAY(T1),ROW(ROW(T2)),CHAR)") Type type,
                @AggregationState NullableDoubleState state,
                @AggregationState NullableDoubleState otherState)
        {
            // noop this is only for annotation testing puproses
        }

        @OutputFunction("double")
        @TypeParameter("T1")
        @TypeParameter("T2")
        public static void output(
                @TypeParameter("ROW(ARRAY(T1),ROW(ROW(T2)),CHAR)") Type type,
                @AggregationState NullableDoubleState state,
                BlockBuilder out)
        {
            // noop this is only for annotation testing puproses
        }
    }

    @Test
    public void testPartiallyFixedTypeParameterInjectionAggregateFunctionParse()
    {
        Signature expectedSignature = new Signature(
                "partially_fixed_type_parameter_injection",
                FunctionKind.AGGREGATE,
                ImmutableList.of(typeVariable("T1"), typeVariable("T2")),
                ImmutableList.of(),
                DoubleType.DOUBLE.getTypeSignature(),
                ImmutableList.of(DoubleType.DOUBLE.getTypeSignature()),
                false);

        ParametricAggregation aggregation = parseFunctionDefinition(PartiallyFixedTypeParameterInjectionAggregateFunction.class);
        assertEquals(aggregation.getDescription(), "Simple aggregate with fixed parameter type injected");
        assertTrue(aggregation.isDeterministic());
        assertEquals(aggregation.getSignature(), expectedSignature);
        ParametricImplementationsGroup<AggregationImplementation> implementations = aggregation.getImplementations();
        assertImplementationCount(implementations, 0, 0, 1);
        AggregationImplementation implementationDouble = implementations.getGenericImplementations().stream().filter(impl -> impl.getStateClass() == NullableDoubleState.class).collect(toImmutableList()).get(0);
        assertFalse(implementationDouble.getStateSerializerFactory().isPresent());
        assertEquals(implementationDouble.getDefinitionClass(), PartiallyFixedTypeParameterInjectionAggregateFunction.class);
        assertDependencyCount(implementationDouble, 1, 1, 1);
        assertFalse(implementationDouble.hasSpecializedTypeParameters());
        List<AggregationMetadata.ParameterMetadata.ParameterType> expectedMetadataTypes = ImmutableList.of(AggregationMetadata.ParameterMetadata.ParameterType.STATE, AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL);
        assertTrue(implementationDouble.getInputParameterMetadataTypes().equals(expectedMetadataTypes));
        assertEquals(implementationDouble.getStateClass(), NullableDoubleState.class);

        InternalAggregationFunction specialized = aggregation.specialize(
                BoundVariables.builder().setTypeVariable("T1", DoubleType.DOUBLE).setTypeVariable("T2", DoubleType.DOUBLE).build(),
                1,
                METADATA);
        assertEquals(specialized.getFinalType(), DoubleType.DOUBLE);
        assertTrue(specialized.getParameterTypes().equals(ImmutableList.of(DoubleType.DOUBLE)));
        assertTrue(specialized.isDecomposable());
        assertEquals(specialized.name(), "partially_fixed_type_parameter_injection");
    }
}
