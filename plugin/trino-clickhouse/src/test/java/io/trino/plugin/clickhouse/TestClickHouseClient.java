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
package io.trino.plugin.clickhouse;

import org.junit.jupiter.api.Test;

import static io.trino.plugin.clickhouse.ClickHouseClient.toTransferType;
import static org.assertj.core.api.Assertions.assertThat;

public class TestClickHouseClient
{
    @Test
    public void testTransferTypeKeepsTypesWithoutZone()
    {
        assertThat(toTransferType("DateTime")).isEqualTo("DateTime");
        assertThat(toTransferType("Nullable(DateTime64(3))")).isEqualTo("Nullable(DateTime64(3))");
        assertThat(toTransferType("Array(Nullable(DateTime64(9)))")).isEqualTo("Array(Nullable(DateTime64(9)))");
        assertThat(toTransferType("Map(String, Array(Nullable(Int32)))")).isEqualTo("Map(String, Array(Nullable(Int32)))");
        assertThat(toTransferType("DateTime('UTC')")).isEqualTo("DateTime('UTC')");
        assertThat(toTransferType("Array(DateTime64(6, 'UTC'))")).isEqualTo("Array(DateTime64(6, 'UTC'))");
    }

    @Test
    public void testTransferTypeUsesUtcForZonedValues()
    {
        assertThat(toTransferType("DateTime('Asia/Kathmandu')")).isEqualTo("DateTime('UTC')");
        assertThat(toTransferType("DateTime64(3,'Asia/Kathmandu')")).isEqualTo("DateTime64(3, 'UTC')");
        assertThat(toTransferType("Nullable(DateTime64(9, 'America/New_York'))")).isEqualTo("Nullable(DateTime64(9, 'UTC'))");
        assertThat(toTransferType("LowCardinality(Nullable(DateTime('Europe/Vilnius')))")).isEqualTo("LowCardinality(Nullable(DateTime('UTC')))");
        assertThat(toTransferType("DateTime64(3, 'Etc/UTC')")).isEqualTo("DateTime64(3, 'UTC')");
    }

    @Test
    public void testTransferTypeUsesUtcForNestedZonedValues()
    {
        assertThat(toTransferType("Array(Nullable(DateTime64(3, 'Asia/Kathmandu')))")).isEqualTo("Array(Nullable(DateTime64(3, 'UTC')))");
        assertThat(toTransferType("Array(Array(DateTime('Europe/Vilnius')))")).isEqualTo("Array(Array(DateTime('UTC')))");
        assertThat(toTransferType("Map(DateTime('Europe/Vilnius'), DateTime64(3, 'Asia/Kathmandu'))")).isEqualTo("Map(DateTime('UTC'), DateTime64(3, 'UTC'))");
        assertThat(toTransferType("Tuple(a DateTime('Asia/Kathmandu'), b Nullable(DateTime64(9, 'America/New_York')), c String)"))
                .isEqualTo("Tuple(a DateTime('UTC'), b Nullable(DateTime64(9, 'UTC')), c String)");
        assertThat(toTransferType("Tuple(DateTime DateTime('Asia/Kathmandu'), DateTime64 DateTime64(6, 'UTC'))"))
                .isEqualTo("Tuple(DateTime DateTime('UTC'), DateTime64 DateTime64(6, 'UTC'))");
    }

    @Test
    public void testTransferTypeReplacesLineStrings()
    {
        assertThat(toTransferType("LineString")).isEqualTo("Array(Point)");
        assertThat(toTransferType("Map(String, MultiLineString)")).isEqualTo("Map(String, Array(Array(Point)))");
        assertThat(toTransferType("Tuple(LineString, DateTime('Asia/Kathmandu'))")).isEqualTo("Tuple(Array(Point), DateTime('UTC'))");
        assertThat(toTransferType("Enum8('LineString' = 1)")).isEqualTo("Enum8('LineString' = 1)");
    }
}
