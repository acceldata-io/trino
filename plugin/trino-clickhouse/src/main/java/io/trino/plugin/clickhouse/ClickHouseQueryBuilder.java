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

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.trino.plugin.jdbc.DefaultQueryBuilder;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.type.VarcharType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.trino.plugin.clickhouse.ClickHouseClient.toTransferType;

public class ClickHouseQueryBuilder
        extends DefaultQueryBuilder
{
    @Inject
    public ClickHouseQueryBuilder(RemoteQueryModifier queryModifier)
    {
        super(queryModifier);
    }

    @Override
    protected String getProjection(JdbcClient client, List<JdbcColumnHandle> columns, Map<String, ParameterizedExpression> columnExpressions, Consumer<QueryParameter> accumulator)
    {
        Map<String, ParameterizedExpression> projections = new HashMap<>(columnExpressions);
        for (JdbcColumnHandle column : columns) {
            // Columns mapped to varchar keep the remote text representation
            if (projections.containsKey(column.getColumnName()) || column.getColumnType() instanceof VarcharType) {
                continue;
            }
            column.getJdbcTypeHandle().jdbcTypeName().ifPresent(typeName -> {
                String transferType = toTransferType(typeName);
                if (!transferType.equals(typeName)) {
                    String cast = "CAST(%s AS %s)".formatted(client.quoted(column.getColumnName()), transferType);
                    projections.put(column.getColumnName(), new ParameterizedExpression(cast, ImmutableList.of()));
                }
            });
        }
        return super.getProjection(client, columns, projections, accumulator);
    }
}
