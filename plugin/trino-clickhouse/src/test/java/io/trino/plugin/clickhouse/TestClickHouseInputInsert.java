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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestClickHouseInputInsert
{
    @Test
    void testInsertTakesTypesFromInputFunction()
    {
        assertThat(ClickHouseInputInsert.insertSql(
                "\"db\".\"t\"",
                List.of("\"id\"", "\"s\"", "\"n\""),
                List.of("Int32", "LowCardinality(String)", "Nullable(Decimal(12, 2))")))
                .isEqualTo("INSERT INTO \"db\".\"t\" (\"id\", \"s\", \"n\")" +
                        " SELECT \"id\", \"s\", \"n\"" +
                        " FROM input('\"id\" Int32, \"s\" LowCardinality(String), \"n\" Nullable(Decimal(12, 2))')");
    }

    @Test
    void testStructureIsEscapedAsStringLiteral()
    {
        assertThat(ClickHouseInputInsert.insertSql(
                "\"t\"",
                List.of("\"it's\"", "\"a\\b\""),
                List.of("Enum8('x' = 1)", "String")))
                .isEqualTo("INSERT INTO \"t\" (\"it's\", \"a\\b\") SELECT \"it's\", \"a\\b\"" +
                        " FROM input('\"it\\'s\" Enum8(\\'x\\' = 1), \"a\\\\b\" String')");
    }

    @Test
    void testColumnsAndTypesMustMatch()
    {
        assertThatThrownBy(() -> ClickHouseInputInsert.insertSql("\"t\"", List.of("\"a\""), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClickHouseInputInsert.insertSql("\"t\"", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void testRefusedTypeCheckSwitchesToInputFunction()
            throws SQLException
    {
        List<String> prepared = new ArrayList<>();
        PreparedStatement statement = stubStatement();
        Connection connection = connection(prepared, sql -> {
            if (sql.contains(" VALUES ")) {
                throw new SQLException("Not enough privileges", "HY000", ACCESS_DENIED);
            }
            return statement;
        });

        assertThat(ClickHouseInputInsert.prepareInsert(connection, "/* q1 */ INSERT INTO \"t\" (\"a\") VALUES (?)", "INSERT INTO \"t\" (\"a\") SELECT \"a\" FROM input('\"a\" Int32')"))
                .isSameAs(statement);
        assertThat(prepared).containsExactly(
                "/* q1 */ INSERT INTO \"t\" (\"a\") VALUES (?)",
                "/* q1 */ INSERT INTO \"t\" (\"a\") SELECT \"a\" FROM input('\"a\" Int32')");
    }

    @Test
    void testWorkingInsertIsUnchanged()
            throws SQLException
    {
        List<String> prepared = new ArrayList<>();
        PreparedStatement statement = stubStatement();
        Connection connection = connection(prepared, sql -> statement);

        assertThat(ClickHouseInputInsert.prepareInsert(connection, "INSERT INTO \"t\" (\"a\") VALUES (?)", "unused"))
                .isSameAs(statement);
        assertThat(prepared).containsExactly("INSERT INTO \"t\" (\"a\") VALUES (?)");
    }

    @Test
    void testOtherErrorsAreNotRetried()
    {
        List<String> prepared = new ArrayList<>();
        Connection connection = connection(prepared, sql -> {
            throw new SQLException("Unknown table", "HY000", 60);
        });

        assertThatThrownBy(() -> ClickHouseInputInsert.prepareInsert(connection, "INSERT INTO \"t\" (\"a\") VALUES (?)", "input sql"))
                .isInstanceOf(SQLException.class)
                .hasMessage("Unknown table");
        assertThat(prepared).hasSize(1);
    }

    @Test
    void testFailedInputInsertKeepsTheFirstError()
    {
        Connection connection = connection(new ArrayList<>(), sql -> {
            throw new SQLException(sql.contains(" VALUES ") ? "needs SELECT" : "needs CREATE TEMPORARY TABLE", "HY000", ACCESS_DENIED);
        });

        assertThatThrownBy(() -> ClickHouseInputInsert.prepareInsert(connection, "INSERT INTO \"t\" (\"a\") VALUES (?)", "INSERT INTO \"t\" (\"a\") SELECT \"a\" FROM input('\"a\" Int32')"))
                .hasMessage("needs SELECT")
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement().hasFieldOrPropertyWithValue("message", "needs CREATE TEMPORARY TABLE"));
    }

    private static final int ACCESS_DENIED = 497;

    private interface Prepare
    {
        PreparedStatement prepare(String sql)
                throws SQLException;
    }

    private static Connection connection(List<String> prepared, Prepare prepare)
    {
        return (Connection) Proxy.newProxyInstance(
                TestClickHouseInputInsert.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    prepared.add((String) args[0]);
                    return prepare.prepare((String) args[0]);
                });
    }

    private static PreparedStatement stubStatement()
    {
        return (PreparedStatement) Proxy.newProxyInstance(
                TestClickHouseInputInsert.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> null);
    }
}
