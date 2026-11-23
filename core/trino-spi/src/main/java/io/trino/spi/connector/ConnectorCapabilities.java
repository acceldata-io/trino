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

public enum ConnectorCapabilities
{
    DEFAULT_COLUMN_VALUE,
    NOT_NULL_COLUMN_CONSTRAINT,
    MATERIALIZED_VIEW_GRACE_PERIOD,
    MATERIALIZED_VIEW_WHEN_STALE_BEHAVIOR,
    /**
     * Quoted schema and table names keep their case, and schema and table names are listed
     * with their exact case. Unquoted names and column names are still lowercased.
     */
    CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES,
}
