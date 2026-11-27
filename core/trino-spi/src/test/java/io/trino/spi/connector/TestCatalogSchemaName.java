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
import static io.trino.spi.connector.CatalogSchemaName.caseSensitiveCatalogSchemaName;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCatalogSchemaName
{
    private static final JsonCodec<CatalogSchemaName> CODEC = jsonCodec(CatalogSchemaName.class);

    @Test
    public void testNames()
    {
        assertThat(new CatalogSchemaName("Catalog", "DUP_DB").getSchemaName()).isEqualTo("dup_db");

        CatalogSchemaName caseSensitive = caseSensitiveCatalogSchemaName("Catalog", "DUP_DB");
        assertThat(caseSensitive.getCatalogName()).isEqualTo("catalog");
        assertThat(caseSensitive.getSchemaName()).isEqualTo("DUP_DB");
        assertThat(caseSensitive.isCaseSensitive()).isTrue();
        assertThat(caseSensitive).isNotEqualTo(new CatalogSchemaName("catalog", "DUP_DB"));
    }

    @Test
    public void testJson()
    {
        assertThat(CODEC.toJson(new CatalogSchemaName("catalog", "dup_db"))).doesNotContain("caseSensitive");

        CatalogSchemaName caseSensitive = caseSensitiveCatalogSchemaName("catalog", "DUP_DB");
        assertThat(CODEC.fromJson(CODEC.toJson(caseSensitive))).isEqualTo(caseSensitive);

        // JSON without the flag is lowercased, as before
        assertThat(CODEC.fromJson("{\"catalogName\": \"catalog\", \"schemaName\": \"DUP_DB\"}"))
                .isEqualTo(new CatalogSchemaName("catalog", "dup_db"));
    }
}
