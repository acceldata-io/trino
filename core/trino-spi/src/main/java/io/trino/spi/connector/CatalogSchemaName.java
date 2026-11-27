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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;
import static java.util.Locale.ENGLISH;

public final class CatalogSchemaName
{
    private final String catalogName;
    private final String schemaName;

    public CatalogSchemaName(String catalogName, String schemaName)
    {
        this(catalogName, schemaName, false);
    }

    private CatalogSchemaName(String catalogName, String schemaName, boolean caseSensitive)
    {
        this.catalogName = catalogName.toLowerCase(ENGLISH);
        this.schemaName = caseSensitive ? schemaName : schemaName.toLowerCase(ENGLISH);
    }

    /**
     * Creates a name that keeps the case of the schema name, for catalogs with
     * {@link ConnectorCapabilities#CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES}.
     */
    public static CatalogSchemaName caseSensitiveCatalogSchemaName(String catalogName, String schemaName)
    {
        return new CatalogSchemaName(catalogName, schemaName, true);
    }

    @JsonCreator
    public static CatalogSchemaName fromJson(
            @JsonProperty("catalogName") String catalogName,
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("caseSensitive") Boolean caseSensitive)
    {
        return new CatalogSchemaName(catalogName, schemaName, Boolean.TRUE.equals(caseSensitive));
    }

    @JsonProperty
    public String getCatalogName()
    {
        return catalogName;
    }

    @JsonProperty
    public String getSchemaName()
    {
        return schemaName;
    }

    /**
     * Whether the schema name keeps uppercase letters, which only names in catalogs with
     * case-sensitive schema and table names can have.
     */
    @JsonProperty("caseSensitive")
    @JsonInclude(NON_DEFAULT)
    public boolean isCaseSensitive()
    {
        return !schemaName.equals(schemaName.toLowerCase(ENGLISH));
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        CatalogSchemaName that = (CatalogSchemaName) obj;
        return Objects.equals(catalogName, that.catalogName) &&
                Objects.equals(schemaName, that.schemaName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(catalogName, schemaName);
    }

    @Override
    public String toString()
    {
        return catalogName + '.' + schemaName;
    }
}
