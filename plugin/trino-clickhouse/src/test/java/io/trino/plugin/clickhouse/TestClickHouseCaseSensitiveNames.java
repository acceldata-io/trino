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

import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
public class TestClickHouseCaseSensitiveNames
        extends AbstractTestQueryFramework
{
    private TestingClickHouseServer clickHouseServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        clickHouseServer = closeAfterClass(new TestingClickHouseServer());
        return ClickHouseQueryRunner.builder(clickHouseServer)
                .addConnectorProperty("case-sensitive-name-matching", "true")
                .build();
    }

    @BeforeAll
    public void createTables()
    {
        clickHouseServer.execute("CREATE DATABASE dup_db");
        clickHouseServer.execute("CREATE DATABASE \"DUP_DB\"");
        clickHouseServer.execute("CREATE TABLE dup_db.dup_case (id Int32) ENGINE = MergeTree ORDER BY id");
        clickHouseServer.execute("CREATE TABLE dup_db.\"DUP_CASE\" (id Int32) ENGINE = MergeTree ORDER BY id");
        clickHouseServer.execute("CREATE TABLE \"DUP_DB\".\"DUP_CASE\" (id Int32) ENGINE = MergeTree ORDER BY id");
        clickHouseServer.execute("INSERT INTO dup_db.dup_case VALUES (1)");
        clickHouseServer.execute("INSERT INTO dup_db.\"DUP_CASE\" VALUES (1), (2), (3)");
        clickHouseServer.execute("INSERT INTO \"DUP_DB\".\"DUP_CASE\" VALUES (1), (2), (3), (4), (5)");
    }

    @Test
    public void testSelect()
    {
        assertQuery("SELECT count(*) FROM clickhouse.dup_db.dup_case", "VALUES 1");
        assertQuery("SELECT count(*) FROM clickhouse.dup_db.DUP_CASE", "VALUES 1");
        assertQuery("SELECT count(*) FROM clickhouse.dup_db.\"DUP_CASE\"", "VALUES 3");
        assertQuery("SELECT count(*) FROM clickhouse.\"DUP_DB\".\"DUP_CASE\"", "VALUES 5");
    }

    @Test
    public void testListing()
    {
        assertQuery("SHOW TABLES FROM clickhouse.dup_db", "VALUES 'dup_case', 'DUP_CASE'");
        assertQuery("SHOW TABLES FROM clickhouse.\"DUP_DB\"", "VALUES 'DUP_CASE'");
        assertQuery(
                "SELECT schema_name FROM clickhouse.information_schema.schemata WHERE lower(schema_name) = 'dup_db'",
                "VALUES 'dup_db', 'DUP_DB'");
        assertQuery(
                "SELECT table_name FROM clickhouse.information_schema.tables WHERE table_schema = 'dup_db' AND table_name = 'DUP_CASE'",
                "VALUES 'DUP_CASE'");
        assertQuery(
                "SELECT table_name, column_name FROM system.jdbc.columns WHERE table_cat = 'clickhouse' AND table_schem = 'DUP_DB' AND table_name = 'DUP_CASE'",
                "VALUES ('DUP_CASE', 'id')");
        assertQuery("DESCRIBE clickhouse.dup_db.\"DUP_CASE\"", "VALUES ('id', 'integer', '', '')");
        assertThat((String) computeScalar("SHOW CREATE TABLE clickhouse.dup_db.\"DUP_CASE\""))
                .startsWith("CREATE TABLE clickhouse.dup_db.\"DUP_CASE\" (");
    }

    @Test
    public void testInsertAndCreateTableAsSelect()
    {
        assertUpdate("CREATE TABLE clickhouse.dup_db.\"INSERT_TARGET\" (id integer)");
        assertUpdate("INSERT INTO clickhouse.dup_db.\"INSERT_TARGET\" VALUES 1, 2", 2);
        assertQuery("SELECT count(*) FROM clickhouse.dup_db.\"INSERT_TARGET\"", "VALUES 2");
        assertQuery("SHOW TABLES FROM clickhouse.dup_db LIKE 'INSERT%'", "VALUES 'INSERT_TARGET'");
        assertUpdate("DROP TABLE clickhouse.dup_db.\"INSERT_TARGET\"");

        assertUpdate("CREATE TABLE clickhouse.dup_db.\"CTAS_TARGET\" AS SELECT * FROM clickhouse.dup_db.\"DUP_CASE\"", 3);
        assertQuery("SELECT count(*) FROM clickhouse.dup_db.\"CTAS_TARGET\"", "VALUES 3");
        assertQuery("SHOW TABLES FROM clickhouse.dup_db LIKE 'CTAS%'", "VALUES 'CTAS_TARGET'");
        assertUpdate("DROP TABLE clickhouse.dup_db.\"CTAS_TARGET\"");
        assertQueryReturnsEmptyResult("SHOW TABLES FROM clickhouse.dup_db LIKE 'CTAS%'");
    }

    @Test
    public void testRenameAndDrop()
    {
        assertUpdate("CREATE TABLE clickhouse.dup_db.\"RENAME_SOURCE\" (id integer)");
        assertUpdate("ALTER TABLE clickhouse.dup_db.\"RENAME_SOURCE\" RENAME TO \"Rename_Target\"");
        assertQuery("SHOW TABLES FROM clickhouse.dup_db LIKE 'Rename%'", "VALUES 'Rename_Target'");
        assertUpdate("DROP TABLE clickhouse.dup_db.\"Rename_Target\"");
        assertQueryReturnsEmptyResult("SHOW TABLES FROM clickhouse.dup_db LIKE 'Rename%'");
    }

    @Test
    public void testSchemas()
    {
        assertUpdate("CREATE SCHEMA clickhouse.\"Schema_Test\"");
        assertQuery("SELECT schema_name FROM clickhouse.information_schema.schemata WHERE schema_name = 'Schema_Test'", "VALUES 'Schema_Test'");
        assertUpdate("CREATE TABLE clickhouse.\"Schema_Test\".\"T\" (id integer)");
        assertQuery("SHOW TABLES FROM clickhouse.\"Schema_Test\"", "VALUES 'T'");
        assertUpdate("DROP TABLE clickhouse.\"Schema_Test\".\"T\"");
        assertUpdate("DROP SCHEMA clickhouse.\"Schema_Test\"");
        assertQueryReturnsEmptyResult("SELECT schema_name FROM clickhouse.information_schema.schemata WHERE schema_name = 'Schema_Test'");
    }
}
