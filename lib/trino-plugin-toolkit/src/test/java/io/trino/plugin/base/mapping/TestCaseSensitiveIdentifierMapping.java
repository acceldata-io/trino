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
package io.trino.plugin.base.mapping;

import com.google.common.collect.ImmutableSet;
import io.trino.spi.security.ConnectorIdentity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class TestCaseSensitiveIdentifierMapping
{
    private static final ConnectorIdentity IDENTITY = ConnectorIdentity.ofUser("user");

    private final IdentifierMapping mapping = new CaseSensitiveIdentifierMapping();

    @Test
    public void testSchemaAndTableNamesKeepCase()
    {
        assertThat(mapping.fromRemoteSchemaName("DUP_DB")).isEqualTo("DUP_DB");
        assertThat(mapping.fromRemoteTableName("DUP_DB", "DUP_CASE")).isEqualTo("DUP_CASE");
        assertThat(mapping.toRemoteSchemaName(new TestingRemoteIdentifiers(true), IDENTITY, "DUP_DB")).isEqualTo("DUP_DB");
        assertThat(mapping.toRemoteTableName(new TestingRemoteIdentifiers(true), IDENTITY, "DUP_DB", "dup_case")).isEqualTo("dup_case");
    }

    @Test
    public void testColumnNamesAreLowercased()
    {
        assertThat(mapping.fromRemoteColumnName("Value")).isEqualTo("value");
        assertThat(mapping.toRemoteColumnName(new TestingRemoteIdentifiers(false), "value")).isEqualTo("value");
        assertThat(mapping.toRemoteColumnName(new TestingRemoteIdentifiers(true), "value")).isEqualTo("VALUE");
    }

    private record TestingRemoteIdentifiers(boolean storesUpperCaseIdentifiers)
            implements RemoteIdentifiers
    {
        @Override
        public Set<String> getRemoteSchemas()
        {
            return ImmutableSet.of();
        }

        @Override
        public Set<String> getRemoteTables(String remoteSchema)
        {
            return ImmutableSet.of();
        }
    }
}
