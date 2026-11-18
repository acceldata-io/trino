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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

/**
 * An INSERT that takes its rows through ClickHouse's {@code input()} table function.
 * <p>
 * ClickHouse JDBC 0.7 prepares a plain {@code INSERT INTO t (c, ...) VALUES (?, ...)} by first running
 * {@code SELECT c, ... FROM t WHERE 0} to learn the column types. ClickHouse checks SELECT rights for that
 * query, so a user who may insert into the table but not read it is refused before any row is written.
 * With {@code input('c type, ...')} the driver takes the types from the statement and runs no such query;
 * rows are still bound by position, through the same prepared statement class.
 * <p>
 * The plain INSERT is still tried first: some ClickHouse versions (24.8, not 26.8) also want
 * {@code CREATE TEMPORARY TABLE} for {@code input()}, so it is used only when ClickHouse refuses
 * the driver's type check.
 */
final class ClickHouseInputInsert
{
    private static final int ACCESS_DENIED = 497;

    private ClickHouseInputInsert() {}

    /**
     * Prepares the INSERT, or the same INSERT through {@code input()} when ClickHouse refuses the type check
     * the driver runs while preparing it.
     *
     * @param sql the INSERT ... VALUES Trino built, possibly with a leading comment
     * @param inputSql the same INSERT through {@code input()}, from {@link #insertSql}
     */
    static PreparedStatement prepareInsert(Connection connection, String sql, String inputSql)
            throws SQLException
    {
        try {
            return connection.prepareStatement(sql);
        }
        catch (SQLException e) {
            int insertStart = sql.indexOf("INSERT INTO ");
            if (e.getErrorCode() != ACCESS_DENIED || insertStart < 0) {
                throw e;
            }
            try {
                return connection.prepareStatement(sql.substring(0, insertStart) + inputSql);
            }
            catch (SQLException | RuntimeException inputError) {
                e.addSuppressed(inputError);
                throw e;
            }
        }
    }

    /**
     * @param quotedTable the target table, quoted
     * @param quotedColumns the target columns, quoted, in bind order
     * @param clickHouseTypes each column's ClickHouse type, e.g. {@code Nullable(String)}
     */
    static String insertSql(String quotedTable, List<String> quotedColumns, List<String> clickHouseTypes)
    {
        checkArgument(!quotedColumns.isEmpty(), "no columns to insert into %s", quotedTable);
        checkArgument(quotedColumns.size() == clickHouseTypes.size(), "columns and types mismatch: %s, %s", quotedColumns, clickHouseTypes);
        String columns = String.join(", ", quotedColumns);
        String structure = IntStream.range(0, quotedColumns.size())
                .mapToObj(i -> quotedColumns.get(i) + " " + clickHouseTypes.get(i))
                .collect(joining(", "));
        return format("INSERT INTO %s (%s) SELECT %s FROM input(%s)", quotedTable, columns, columns, stringLiteral(structure));
    }

    private static String stringLiteral(String value)
    {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
