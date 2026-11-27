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
import static io.airlift.slice.SizeOf.estimatedSizeOf;
import static io.airlift.slice.SizeOf.instanceSize;
import static io.trino.spi.connector.SchemaUtil.checkNotEmpty;
import static java.util.Locale.ENGLISH;

public final class SchemaTableName
{
    private static final int INSTANCE_SIZE = instanceSize(SchemaTableName.class);

    private final String schemaName;
    private final String tableName;

    public SchemaTableName(String schemaName, String tableName)
    {
        this(schemaName, tableName, false);
    }

    private SchemaTableName(String schemaName, String tableName, boolean caseSensitive)
    {
        checkNotEmpty(schemaName, "schemaName");
        checkNotEmpty(tableName, "tableName");
        this.schemaName = caseSensitive ? schemaName : schemaName.toLowerCase(ENGLISH);
        this.tableName = caseSensitive ? tableName : tableName.toLowerCase(ENGLISH);
    }

    public static SchemaTableName schemaTableName(String schemaName, String tableName)
    {
        return new SchemaTableName(schemaName, tableName);
    }

    /**
     * Creates a name that keeps the case of the schema and table names, for catalogs with
     * {@link ConnectorCapabilities#CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES}.
     */
    public static SchemaTableName caseSensitiveSchemaTableName(String schemaName, String tableName)
    {
        return new SchemaTableName(schemaName, tableName, true);
    }

    @JsonCreator
    public static SchemaTableName fromJson(
            @JsonProperty("schema") String schemaName,
            @JsonProperty("table") String tableName,
            @JsonProperty("caseSensitive") Boolean caseSensitive)
    {
        return new SchemaTableName(schemaName, tableName, Boolean.TRUE.equals(caseSensitive));
    }

    @JsonProperty("schema")
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty("table")
    public String getTableName()
    {
        return tableName;
    }

    /**
     * Whether the name keeps uppercase letters, which only names in catalogs with case-sensitive
     * schema and table names can have.
     */
    @JsonProperty("caseSensitive")
    @JsonInclude(NON_DEFAULT)
    public boolean isCaseSensitive()
    {
        return !schemaName.equals(schemaName.toLowerCase(ENGLISH)) || !tableName.equals(tableName.toLowerCase(ENGLISH));
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schemaName, tableName);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        SchemaTableName other = (SchemaTableName) obj;
        return Objects.equals(this.schemaName, other.schemaName) &&
                Objects.equals(this.tableName, other.tableName);
    }

    @Override
    public String toString()
    {
        return schemaName + '.' + tableName;
    }

    public SchemaTablePrefix toSchemaTablePrefix()
    {
        return new SchemaTablePrefix(schemaName, tableName);
    }

    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE
                + estimatedSizeOf(schemaName)
                + estimatedSizeOf(tableName);
    }
}
