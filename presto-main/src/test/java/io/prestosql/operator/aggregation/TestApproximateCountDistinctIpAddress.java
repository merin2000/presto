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

import io.airlift.slice.Slices;
import io.prestosql.metadata.Signature;
import io.prestosql.spi.type.Type;

import java.util.concurrent.ThreadLocalRandom;

import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.type.IpAddressType.IPADDRESS;

public class TestApproximateCountDistinctIpAddress
        extends AbstractTestApproximateCountDistinct
{
    @Override
    protected InternalAggregationFunction getAggregationFunction()
    {
        return metadata.getAggregateFunctionImplementation(
                new Signature("approx_distinct", AGGREGATE, BIGINT.getTypeSignature(), IPADDRESS.getTypeSignature(), DOUBLE.getTypeSignature()));
    }

    @Override
    protected Type getValueType()
    {
        return IPADDRESS;
    }

    @Override
    protected Object randomValue()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Slices.wrappedBuffer(bytes);
    }
}
