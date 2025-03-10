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
package io.prestosql.operator.aggregation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.StandardTypes;

import java.util.List;

import static io.prestosql.spi.type.VarbinaryType.VARBINARY;

public class TestVarBinaryMinAggregation
        extends AbstractTestAggregationFunction
{
    @Override
    protected Block[] getSequenceBlocks(int start, int length)
    {
        BlockBuilder blockBuilder = VARBINARY.createBlockBuilder(null, length);
        for (int i = start; i < start + length; i++) {
            VARBINARY.writeSlice(blockBuilder, Slices.wrappedBuffer(Ints.toByteArray(i)));
        }
        return new Block[] {blockBuilder.build()};
    }

    @Override
    protected Object getExpectedValue(int start, int length)
    {
        if (length == 0) {
            return null;
        }
        Slice min = null;
        for (int i = start; i < start + length; i++) {
            Slice slice = Slices.wrappedBuffer(Ints.toByteArray(i));
            min = (min == null) ? slice : Ordering.natural().min(min, slice);
        }
        return min.toStringUtf8();
    }

    @Override
    protected String getFunctionName()
    {
        return "min";
    }

    @Override
    protected List<String> getFunctionParameterTypes()
    {
        return ImmutableList.of(StandardTypes.VARCHAR);
    }
}
