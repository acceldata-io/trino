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
package io.trino.sql.query;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.connector.MockConnectorFactory;
import io.trino.connector.MockConnectorPlugin;
import io.trino.connector.MockConnectorTableHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorCapabilities;
import io.trino.testing.QueryRunner;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.util.List;
import java.util.Set;

import static io.trino.spi.connector.ConnectorCapabilities.CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestCaseSensitiveNames
{
    private final QueryAssertions assertions;

    public TestCaseSensitiveNames()
    {
        QueryRunner queryRunner = new StandaloneQueryRunner(testSessionBuilder()
                .setCatalog("case_sensitive")
                .setSchema("dup_db")
                .build());
        queryRunner.installPlugin(new MockConnectorPlugin(mockConnector(
                "case_sensitive",
                ImmutableSet.of(CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES),
                ImmutableList.of("dup_db", "DUP_DB"),
                ImmutableList.of("dup_case", "DUP_CASE"))));
        queryRunner.installPlugin(new MockConnectorPlugin(mockConnector(
                "lowercase",
                ImmutableSet.of(),
                ImmutableList.of("dup_db"),
                ImmutableList.of("dup_case"))));
        queryRunner.createCatalog("case_sensitive", "case_sensitive", ImmutableMap.of());
        queryRunner.createCatalog("lowercase", "lowercase", ImmutableMap.of());
        assertions = new QueryAssertions(queryRunner);
    }

    private static MockConnectorFactory mockConnector(String name, Set<ConnectorCapabilities> capabilities, List<String> schemas, List<String> tables)
    {
        return MockConnectorFactory.builder()
                .withName(name)
                .withCapabilities(() -> capabilities)
                .withListSchemaNames(_ -> schemas)
                .withListTables((_, schema) -> schemas.contains(schema) ? tables : ImmutableList.of())
                .withGetTableHandle((_, tableName) -> {
                    if (schemas.contains(tableName.getSchemaName()) && tables.contains(tableName.getTableName())) {
                        return new MockConnectorTableHandle(tableName);
                    }
                    return null;
                })
                .withGetColumns(_ -> ImmutableList.of(new ColumnMetadata("source", VARCHAR)))
                .withData(tableName -> ImmutableList.of(ImmutableList.of(tableName.toString())))
                .build();
    }

    @AfterAll
    public void teardown()
    {
        assertions.close();
    }

    @Test
    public void testSelect()
    {
        assertThat(assertions.query("SELECT source FROM case_sensitive.dup_db.dup_case"))
                .matches("VALUES VARCHAR 'dup_db.dup_case'");
        assertThat(assertions.query("SELECT source FROM case_sensitive.dup_db.\"DUP_CASE\""))
                .matches("VALUES VARCHAR 'dup_db.DUP_CASE'");
        assertThat(assertions.query("SELECT source FROM case_sensitive.dup_db.DUP_CASE"))
                .matches("VALUES VARCHAR 'dup_db.dup_case'");
        assertThat(assertions.query("SELECT source FROM case_sensitive.\"DUP_DB\".\"DUP_CASE\""))
                .matches("VALUES VARCHAR 'DUP_DB.DUP_CASE'");
        assertThat(assertions.query("SELECT source FROM \"DUP_CASE\""))
                .matches("VALUES VARCHAR 'dup_db.DUP_CASE'");
        assertThat(assertions.query("SELECT a.source, b.source FROM case_sensitive.dup_db.dup_case a CROSS JOIN case_sensitive.dup_db.\"DUP_CASE\" b"))
                .matches("VALUES (VARCHAR 'dup_db.dup_case', VARCHAR 'dup_db.DUP_CASE')");

        // a catalog without case-sensitive names lowercases quoted names as before
        assertThat(assertions.query("SELECT source FROM lowercase.dup_db.\"DUP_CASE\""))
                .matches("VALUES VARCHAR 'dup_db.dup_case'");
        assertThat(assertions.query("SELECT source FROM lowercase.\"DUP_DB\".dup_case"))
                .matches("VALUES VARCHAR 'dup_db.dup_case'");
    }

    @Test
    public void testListing()
    {
        assertThat(assertions.query("SHOW SCHEMAS FROM case_sensitive"))
                .matches("VALUES VARCHAR 'DUP_DB', VARCHAR 'dup_db', VARCHAR 'information_schema'");
        assertThat(assertions.query("SHOW TABLES FROM case_sensitive.dup_db"))
                .matches("VALUES VARCHAR 'DUP_CASE', VARCHAR 'dup_case'");
        assertThat(assertions.query("SHOW TABLES FROM case_sensitive.\"DUP_DB\""))
                .matches("VALUES VARCHAR 'DUP_CASE', VARCHAR 'dup_case'");
        assertThat(assertions.query("SHOW SCHEMAS FROM lowercase"))
                .matches("VALUES VARCHAR 'dup_db', VARCHAR 'information_schema'");
    }

    @Test
    public void testInformationSchema()
    {
        assertThat(assertions.query("SELECT table_schema, table_name FROM case_sensitive.information_schema.tables WHERE table_schema = 'DUP_DB' AND table_name = 'DUP_CASE'"))
                .matches("VALUES (VARCHAR 'DUP_DB', VARCHAR 'DUP_CASE')");
        assertThat(assertions.query("SELECT table_name FROM case_sensitive.information_schema.tables WHERE table_schema = 'dup_db' AND table_name = 'dup_case'"))
                .matches("VALUES VARCHAR 'dup_case'");
        assertThat(assertions.query("SELECT table_name, column_name FROM case_sensitive.information_schema.columns WHERE table_schema = 'dup_db' AND table_name = 'DUP_CASE'"))
                .matches("VALUES (VARCHAR 'DUP_CASE', VARCHAR 'source')");

        // names that are not lowercase cannot exist in a catalog without case-sensitive names
        assertThat(assertions.query("SELECT table_name FROM lowercase.information_schema.tables WHERE table_schema = 'dup_db' AND table_name = 'DUP_CASE'"))
                .returnsEmptyResult();
    }

    @Test
    public void testSystemJdbc()
    {
        assertThat(assertions.query("SELECT table_schem, table_name FROM system.jdbc.tables WHERE table_cat = 'case_sensitive' AND table_schem = 'DUP_DB' AND table_name = 'DUP_CASE'"))
                .matches("VALUES (VARCHAR 'DUP_DB', VARCHAR 'DUP_CASE')");
        assertThat(assertions.query("SELECT table_name, column_name FROM system.jdbc.columns WHERE table_cat = 'case_sensitive' AND table_schem = 'dup_db' AND table_name = 'DUP_CASE'"))
                .matches("VALUES (VARCHAR 'DUP_CASE', VARCHAR 'source')");
        assertThat(assertions.query("SELECT table_schem FROM system.jdbc.schemas WHERE table_catalog = 'case_sensitive' AND table_schem = 'DUP_DB'"))
                .matches("VALUES VARCHAR 'DUP_DB'");

        assertThat(assertions.query("SELECT table_name FROM system.jdbc.tables WHERE table_cat = 'lowercase' AND table_schem = 'dup_db' AND table_name = 'DUP_CASE'"))
                .returnsEmptyResult();
        assertThat(assertions.query("SELECT table_name FROM system.jdbc.columns WHERE table_cat = 'lowercase' AND table_schem = 'dup_db' AND table_name = 'DUP_CASE'"))
                .returnsEmptyResult();
    }

    @Test
    public void testDescribe()
    {
        assertThat(assertions.query("DESCRIBE case_sensitive.\"DUP_DB\".\"DUP_CASE\""))
                .matches("VALUES (VARCHAR 'source', VARCHAR 'varchar', VARCHAR '', VARCHAR '')");
        assertThat(assertions.query("DESCRIBE case_sensitive.dup_db.\"MISSING\""))
                .failure().hasMessage("line 1:1: Table 'case_sensitive.dup_db.\"MISSING\"' does not exist");
    }

    @Test
    public void testShowCreate()
    {
        assertThat((String) assertions.execute("SHOW CREATE TABLE case_sensitive.\"DUP_DB\".\"DUP_CASE\"").getOnlyValue())
                .startsWith("CREATE TABLE case_sensitive.\"DUP_DB\".\"DUP_CASE\" (");
        assertThat((String) assertions.execute("SHOW CREATE TABLE case_sensitive.dup_db.dup_case").getOnlyValue())
                .startsWith("CREATE TABLE case_sensitive.dup_db.dup_case (");
        assertThat((String) assertions.execute("SHOW CREATE SCHEMA case_sensitive.\"DUP_DB\"").getOnlyValue())
                .startsWith("CREATE SCHEMA case_sensitive.\"DUP_DB\"");
    }
}
