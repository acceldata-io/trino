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
package io.trino.metadata;

import io.airlift.json.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestQualifiedObjectName
{
    private final JsonCodec<QualifiedObjectName> codec = JsonCodec.jsonCodec(QualifiedObjectName.class);

    @Test
    public void testJsonSerializationRoundTrip()
    {
        // simple
        testRoundTrip(new QualifiedObjectName("catalog", "schema", "table_name"));

        // names with dots
        testRoundTrip(new QualifiedObjectName("catalog.twój", "schema.ściema", "tabel.tabelkówna"));

        // names with apostrophes
        testRoundTrip(new QualifiedObjectName("cata\"l.o.g\"", "s\"ch.e.ma\"", "\"t.a.b.e.l\""));

        // catalog names must be lowercase
        assertThatThrownBy(() -> new QualifiedObjectName("CataLOG", "SchemA", "TabEl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("catalogName is not lowercase: CataLOG");

        // schema and table names keep their case in catalogs with case-sensitive names
        testRoundTrip(new QualifiedObjectName("catalog", "SchemA", "TabEl"));

        // empty
        testRoundTrip(new QualifiedObjectName("", "", ""));
    }

    @Test
    public void testCaseSensitiveNames()
    {
        QualifiedObjectName name = new QualifiedObjectName("catalog", "DUP_DB", "DUP_CASE");
        assertThat(name.toString()).isEqualTo("catalog.\"DUP_DB\".\"DUP_CASE\"");
        assertThat(name.asSchemaTableName().getSchemaName()).isEqualTo("DUP_DB");
        assertThat(name.asSchemaTableName().getTableName()).isEqualTo("DUP_CASE");
        assertThat(name.asCatalogSchemaTableName().getSchemaTableName().getTableName()).isEqualTo("DUP_CASE");

        assertThat(QualifiedObjectName.valueOf("catalog.\"DUP_DB\".\"DUP_CASE\"")).isEqualTo(name);
        // unquoted parts are lowercased, as in SQL
        assertThat(QualifiedObjectName.valueOf("Catalog.DUP_DB.DUP_CASE")).isEqualTo(new QualifiedObjectName("catalog", "dup_db", "dup_case"));
    }

    private void testRoundTrip(QualifiedObjectName value)
    {
        String json = codec.toJson(value);
        QualifiedObjectName parsed = codec.fromJson(json);
        assertThat(parsed).isEqualTo(value);
    }
}
