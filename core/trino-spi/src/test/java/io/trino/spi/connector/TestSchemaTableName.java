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
package io.trino.spi.connector;

import io.airlift.json.JsonCodec;
import org.junit.jupiter.api.Test;

import static io.airlift.json.JsonCodec.jsonCodec;
import static io.trino.spi.connector.SchemaTableName.caseSensitiveSchemaTableName;
import static org.assertj.core.api.Assertions.assertThat;

public class TestSchemaTableName
{
    private static final JsonCodec<SchemaTableName> CODEC = jsonCodec(SchemaTableName.class);

    @Test
    public void testLowercase()
    {
        SchemaTableName name = new SchemaTableName("DUP_DB", "DUP_CASE");
        assertThat(name.getSchemaName()).isEqualTo("dup_db");
        assertThat(name.getTableName()).isEqualTo("dup_case");
        assertThat(name.isCaseSensitive()).isFalse();
        assertThat(name).isEqualTo(caseSensitiveSchemaTableName("dup_db", "dup_case"));
    }

    @Test
    public void testCaseSensitive()
    {
        SchemaTableName name = caseSensitiveSchemaTableName("DUP_DB", "DUP_CASE");
        assertThat(name.getSchemaName()).isEqualTo("DUP_DB");
        assertThat(name.getTableName()).isEqualTo("DUP_CASE");
        assertThat(name.isCaseSensitive()).isTrue();
        assertThat(name).isNotEqualTo(new SchemaTableName("DUP_DB", "DUP_CASE"));
    }

    @Test
    public void testJson()
    {
        assertThat(CODEC.toJson(new SchemaTableName("dup_db", "dup_case")))
                .doesNotContain("caseSensitive");
        assertThat(CODEC.fromJson(CODEC.toJson(new SchemaTableName("dup_db", "dup_case"))))
                .isEqualTo(new SchemaTableName("dup_db", "dup_case"));

        SchemaTableName caseSensitive = caseSensitiveSchemaTableName("dup_db", "DUP_CASE");
        assertThat(CODEC.toJson(caseSensitive)).contains("\"caseSensitive\" : true");
        assertThat(CODEC.fromJson(CODEC.toJson(caseSensitive))).isEqualTo(caseSensitive);

        // JSON without the flag is lowercased, as before
        assertThat(CODEC.fromJson("{\"schema\": \"DUP_DB\", \"table\": \"DUP_CASE\"}"))
                .isEqualTo(new SchemaTableName("dup_db", "dup_case"));
    }

    @Test
    public void testPrefixKeepsCase()
    {
        assertThat(new SchemaTablePrefix("DUP_DB", "DUP_CASE").toSchemaTableName())
                .isEqualTo(caseSensitiveSchemaTableName("DUP_DB", "DUP_CASE"));
    }
}
