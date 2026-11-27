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

import com.google.common.collect.ImmutableMap;
import io.trino.spi.Plugin;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.testing.TestingConnectorContext;
import org.junit.jupiter.api.Test;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.spi.connector.ConnectorCapabilities.CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestClickHousePlugin
{
    @Test
    public void testCreateConnector()
    {
        Plugin plugin = new ClickHousePlugin();
        ConnectorFactory factory = getOnlyElement(plugin.getConnectorFactories());
        Connector connector = factory.create(
                "test",
                ImmutableMap.of(
                        "connection-url", "jdbc:clickhouse://test",
                        "bootstrap.quiet", "true"),
                new TestingConnectorContext());
        assertThat(connector.getCapabilities()).doesNotContain(CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES);
        connector.shutdown();
    }

    @Test
    public void testCreateConnectorWithCaseSensitiveNames()
    {
        Plugin plugin = new ClickHousePlugin();
        ConnectorFactory factory = getOnlyElement(plugin.getConnectorFactories());
        Connector connector = factory.create(
                "test",
                ImmutableMap.of(
                        "connection-url", "jdbc:clickhouse://test",
                        "case-sensitive-name-matching", "true",
                        "bootstrap.quiet", "true"),
                new TestingConnectorContext());
        assertThat(connector.getCapabilities()).contains(CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES);
        connector.shutdown();

        assertThatThrownBy(() -> factory.create(
                "test",
                ImmutableMap.of(
                        "connection-url", "jdbc:clickhouse://test",
                        "case-sensitive-name-matching", "true",
                        "case-insensitive-name-matching", "true",
                        "bootstrap.quiet", "true"),
                new TestingConnectorContext()))
                .hasMessageContaining("case-sensitive-name-matching cannot be combined with case-insensitive-name-matching");
    }
}
