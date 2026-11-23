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

import com.clickhouse.data.ClickHouseColumn;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.ClickHouseVersion;
import com.clickhouse.jdbc.JdbcTypeMapping;
import com.google.common.base.Enums;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Shorts;
import com.google.inject.Inject;
import io.airlift.slice.Slice;
import io.trino.plugin.base.aggregation.AggregateFunctionRewriter;
import io.trino.plugin.base.aggregation.AggregateFunctionRule;
import io.trino.plugin.base.expression.ConnectorExpressionRewriter;
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.clickhouse.expression.RewriteLike;
import io.trino.plugin.clickhouse.expression.RewriteStringComparison;
import io.trino.plugin.clickhouse.expression.RewriteStringIn;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcExpression;
import io.trino.plugin.jdbc.JdbcOutputTableHandle;
import io.trino.plugin.jdbc.JdbcSortItem;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.LongReadFunction;
import io.trino.plugin.jdbc.LongWriteFunction;
import io.trino.plugin.jdbc.ObjectReadFunction;
import io.trino.plugin.jdbc.ObjectWriteFunction;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.SliceWriteFunction;
import io.trino.plugin.jdbc.WriteFunction;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.aggregation.ImplementAvgFloatingPoint;
import io.trino.plugin.jdbc.aggregation.ImplementCorr;
import io.trino.plugin.jdbc.aggregation.ImplementCount;
import io.trino.plugin.jdbc.aggregation.ImplementCountAll;
import io.trino.plugin.jdbc.aggregation.ImplementCountDistinct;
import io.trino.plugin.jdbc.aggregation.ImplementCovariancePop;
import io.trino.plugin.jdbc.aggregation.ImplementCovarianceSamp;
import io.trino.plugin.jdbc.aggregation.ImplementMinMax;
import io.trino.plugin.jdbc.aggregation.ImplementSum;
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.MapBlockBuilder;
import io.trino.spi.block.RowBlockBuilder;
import io.trino.spi.block.SqlMap;
import io.trino.spi.block.SqlRow;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ColumnPosition;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Variable;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.Int128;
import io.trino.spi.type.LongTimestamp;
import io.trino.spi.type.LongTimestampWithTimeZone;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.TimeZoneKey;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeOperators;
import io.trino.spi.type.TypeSignature;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import jakarta.annotation.Nullable;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.clickhouse.data.ClickHouseValues.convertToQuotedString;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.trino.plugin.clickhouse.ClickHouseSessionProperties.isMapStringAsVarchar;
import static io.trino.plugin.clickhouse.ClickHouseTableProperties.ENGINE_PROPERTY;
import static io.trino.plugin.clickhouse.ClickHouseTableProperties.ORDER_BY_PROPERTY;
import static io.trino.plugin.clickhouse.ClickHouseTableProperties.PARTITION_BY_PROPERTY;
import static io.trino.plugin.clickhouse.ClickHouseTableProperties.PRIMARY_KEY_PROPERTY;
import static io.trino.plugin.clickhouse.ClickHouseTableProperties.SAMPLE_BY_PROPERTY;
import static io.trino.plugin.clickhouse.TrinoToClickHouseWriteChecker.DATETIME;
import static io.trino.plugin.clickhouse.TrinoToClickHouseWriteChecker.DATETIME64;
import static io.trino.plugin.clickhouse.TrinoToClickHouseWriteChecker.DATETIME64_NANOS;
import static io.trino.plugin.clickhouse.TrinoToClickHouseWriteChecker.UINT16;
import static io.trino.plugin.clickhouse.TrinoToClickHouseWriteChecker.UINT32;
import static io.trino.plugin.clickhouse.TrinoToClickHouseWriteChecker.UINT64;
import static io.trino.plugin.clickhouse.TrinoToClickHouseWriteChecker.UINT8;
import static io.trino.plugin.jdbc.DecimalConfig.DecimalMapping.ALLOW_OVERFLOW;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.getDecimalDefaultScale;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.getDecimalRounding;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.getDecimalRoundingMode;
import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN;
import static io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.dateReadFunctionUsingLocalDate;
import static io.trino.plugin.jdbc.StandardColumnMappings.decimalColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.fromLongTrinoTimestamp;
import static io.trino.plugin.jdbc.StandardColumnMappings.fromTrinoTimestamp;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.longDecimalReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.longDecimalWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.longTimestampReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.realWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.shortDecimalWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.timestampColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.timestampReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.toLongTrinoTimestamp;
import static io.trino.plugin.jdbc.StandardColumnMappings.toTrinoTimestamp;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction;
import static io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.getUnsupportedTypeHandling;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.StandardErrorCode.SCHEMA_NOT_EMPTY;
import static io.trino.spi.connector.ConnectorMetadata.MODIFYING_ROWS_MESSAGE;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TimestampType.TIMESTAMP_SECONDS;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_SECONDS;
import static io.trino.spi.type.TimestampWithTimeZoneType.createTimestampWithTimeZoneType;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_SECOND;
import static io.trino.spi.type.Timestamps.NANOSECONDS_PER_MICROSECOND;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_NANOSECOND;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.UuidType.javaUuidToTrinoUuid;
import static io.trino.spi.type.UuidType.trinoUuidToJavaUuid;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;
import static java.lang.Math.max;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.lang.System.arraycopy;
import static java.math.RoundingMode.UNNECESSARY;
import static java.time.ZoneOffset.UTC;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class ClickHouseClient
        extends BaseJdbcClient
{
    private static final Splitter TABLE_PROPERTY_SPLITTER = Splitter.on(',').omitEmptyStrings().trimResults();

    private static final DecimalType UINT64_TYPE = createDecimalType(20, 0);

    // An empty character means that the table doesn't have a comment in ClickHouse
    private static final String NO_COMMENT = "";

    public static final int DEFAULT_DOMAIN_COMPACTION_THRESHOLD = 1_000;

    // ClickHouse has no documented nesting depth limit; this is a Trino-side safety guard to prevent unbounded recursion
    private static final int MAX_ARRAY_NESTING_DEPTH = 10;

    private static final Pattern ZONED_DATETIME64 = Pattern.compile("DateTime64\\(\\s*(\\d+)\\s*,\\s*'([^']*)'\\s*\\)");
    private static final DateTimeFormatter UTC_DATETIME64_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS").withZone(UTC);

    private static final Pattern QUOTED_LITERAL = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'");
    private static final Pattern MULTI_LINE_STRING = Pattern.compile("\\bMultiLineString\\b");
    private static final Pattern LINE_STRING = Pattern.compile("\\bLineString\\b");

    private static final RowType GEO_POINT = RowType.from(ImmutableList.of(RowType.field("x", DOUBLE), RowType.field("y", DOUBLE)));

    private final ConnectorExpressionRewriter<ParameterizedExpression> connectorExpressionRewriter;
    private final AggregateFunctionRewriter<JdbcExpression, ?> aggregateFunctionRewriter;
    private final TypeOperators typeOperators;
    private final Type uuidType;
    private final Type ipAddressType;
    private final AtomicReference<ClickHouseVersion> clickHouseVersion = new AtomicReference<>();

    @Inject
    public ClickHouseClient(
            BaseJdbcConfig config,
            ConnectionFactory connectionFactory,
            QueryBuilder queryBuilder,
            TypeManager typeManager,
            IdentifierMapping identifierMapping,
            RemoteQueryModifier queryModifier)
    {
        super("\"", connectionFactory, queryBuilder, config.getJdbcTypesMappedToVarchar(), identifierMapping, queryModifier, false);
        this.typeOperators = typeManager.getTypeOperators();
        this.uuidType = typeManager.getType(new TypeSignature(StandardTypes.UUID));
        this.ipAddressType = typeManager.getType(new TypeSignature(StandardTypes.IPADDRESS));
        JdbcTypeHandle bigintTypeHandle = new JdbcTypeHandle(Types.BIGINT, Optional.of("bigint"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        this.connectorExpressionRewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
                .addStandardRules(this::quoted)
                .add(new RewriteStringComparison())
                .add(new RewriteStringIn())
                .add(new RewriteLike())
                .map("$not($is_null(value))").to("value IS NOT NULL")
                .map("$not(value: boolean)").to("NOT value")
                .map("$is_null(value)").to("value IS NULL")
                .build();
        this.aggregateFunctionRewriter = new AggregateFunctionRewriter<>(
                this.connectorExpressionRewriter,
                ImmutableSet.<AggregateFunctionRule<JdbcExpression, ParameterizedExpression>>builder()
                        .add(new ImplementCountAll(bigintTypeHandle))
                        .add(new ImplementCount(bigintTypeHandle))
                        .add(new ImplementCountDistinct(bigintTypeHandle, true))
                        .add(new ImplementMinMax(true))
                        .add(new ImplementSum(ClickHouseClient::toTypeHandle))
                        .add(new ImplementAvgFloatingPoint())
                        .add(new ImplementAvgBigint())
                        .add(new ImplementCorr())
                        .add(new ImplementCovarianceSamp())
                        .add(new ImplementCovariancePop())
                        .build());
    }

    @Override
    public Optional<JdbcExpression> implementAggregation(ConnectorSession session, AggregateFunction aggregate, Map<String, ColumnHandle> assignments)
    {
        if (aggregate.getArguments().stream().anyMatch(argument -> isNestedType(argument.getType()))) {
            return Optional.empty();
        }
        // TODO support complex ConnectorExpressions
        return aggregateFunctionRewriter.rewrite(session, aggregate, assignments);
    }

    @Override
    public boolean supportsAggregationPushdown(ConnectorSession session, JdbcTableHandle table, List<AggregateFunction> aggregates, Map<String, ColumnHandle> assignments, List<List<ColumnHandle>> groupingSets)
    {
        // ClickHouse compares and orders Array, Map and Tuple values differently from Trino, and keeps duplicate map keys
        return groupingSets.stream()
                .flatMap(List::stream)
                .noneMatch(column -> isNestedType(((JdbcColumnHandle) column).getColumnType()));
    }

    private static boolean isNestedType(Type type)
    {
        return type instanceof ArrayType || type instanceof MapType || type instanceof RowType;
    }

    @Override
    public Optional<ParameterizedExpression> convertPredicate(ConnectorSession session, ConnectorExpression expression, Map<String, ColumnHandle> assignments)
    {
        return connectorExpressionRewriter.rewrite(session, expression, assignments);
    }

    @Override
    public boolean supportsTopN(ConnectorSession session, JdbcTableHandle handle, List<JdbcSortItem> sortOrder)
    {
        return true;
    }

    @Override
    protected Optional<TopNFunction> topNFunction()
    {
        return Optional.of((query, sortItems, limit) -> {
            String orderBy = sortItems.stream()
                    .map(sortItem -> {
                        String ordering = sortItem.sortOrder().isAscending() ? "ASC" : "DESC";
                        String nullsHandling = sortItem.sortOrder().isNullsFirst() ? "NULLS FIRST" : "NULLS LAST";
                        return format("%s %s %s", quoted(sortItem.column().getColumnName()), ordering, nullsHandling);
                    })
                    .collect(joining(", "));
            return format("%s ORDER BY %s LIMIT %d", query, orderBy, limit);
        });
    }

    @Override
    public boolean isTopNGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    public ResultSet getTables(Connection connection, Optional<String> schemaName, Optional<String> tableName)
            throws SQLException
    {
        // Clickhouse maps their "database" to SQL catalogs and does not have schemas
        DatabaseMetaData metadata = connection.getMetaData();
        return metadata.getTables(
                schemaName.orElse(null),
                null,
                escapeObjectNameForMetadataQuery(tableName, metadata.getSearchStringEscape()).orElse(null),
                getTableTypes().map(types -> types.toArray(String[]::new)).orElse(null));
    }

    @Override
    protected String getTableSchemaName(ResultSet resultSet)
            throws SQLException
    {
        return resultSet.getString("TABLE_CAT");
    }

    private static Optional<JdbcTypeHandle> toTypeHandle(DecimalType decimalType)
    {
        return Optional.of(new JdbcTypeHandle(Types.DECIMAL, Optional.of("Decimal"), Optional.of(decimalType.getPrecision()), Optional.of(decimalType.getScale()), Optional.empty(), Optional.empty()));
    }

    @Override
    protected String quoted(@Nullable String catalog, @Nullable String schema, String table)
    {
        StringBuilder sb = new StringBuilder();
        if (!isNullOrEmpty(schema)) {
            sb.append(quoted(schema)).append(".");
        }
        else if (!isNullOrEmpty(catalog)) {
            sb.append(quoted(catalog)).append(".");
        }
        sb.append(quoted(table));
        return sb.toString();
    }

    @Override
    protected void copyTableSchema(ConnectorSession session, Connection connection, String catalogName, String schemaName, String tableName, String newTableName, List<String> columnNames)
    {
        // ClickHouse does not support `create table tbl as select * from tbl2 where 0=1`
        // ClickHouse supports the following two methods to copy schema
        // 1. create table tbl as tbl2
        // 2. create table tbl1 ENGINE=<engine> as select * from tbl2
        String sql = format(
                "CREATE TABLE %s AS %s ",
                quoted(null, schemaName, newTableName),
                quoted(null, schemaName, tableName));
        try {
            execute(session, connection, sql);
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    public String buildInsertSql(JdbcOutputTableHandle handle, List<WriteFunction> columnWriters)
    {
        // The JDBC driver prepares INSERT ... VALUES (?, ...) by first running SELECT <columns> FROM <table> WHERE 0
        // to learn the column types, which needs the SELECT privilege. An INSERT from input() declares the types
        // in the statement instead. The driver rejects ? placeholders in such a statement.
        List<String> columnNames = handle.getColumnNames();
        checkArgument(columnNames.size() == columnWriters.size(), "handle and columnWriters mismatch: %s, %s", handle, columnWriters);
        Optional<List<JdbcTypeHandle>> columnTypes = handle.getJdbcColumnTypes();
        if (handle.getPageSinkIdColumnName().isPresent() ||
                columnTypes.isEmpty() ||
                columnTypes.get().stream().anyMatch(type -> type.jdbcTypeName().isEmpty() || type.jdbcTypeName().get().contains("?"))) {
            return super.buildInsertSql(handle, columnWriters);
        }

        ImmutableList.Builder<String> inputColumns = ImmutableList.builder();
        ImmutableList.Builder<String> selectColumns = ImmutableList.builder();
        for (int i = 0; i < columnNames.size(); i++) {
            String inputColumn = "_c" + (i + 1);
            inputColumns.add(inputColumn + " " + toTransferType(columnTypes.get().get(i).jdbcTypeName().orElseThrow()));
            selectColumns.add(inputColumn);
        }
        return format(
                "INSERT INTO %s (%s) SELECT %s FROM input(%s)",
                quoted(
                        handle.getRemoteTableName().getCatalogName().orElse(null),
                        handle.getRemoteTableName().getSchemaName().orElse(null),
                        handle.getTemporaryTableName().orElseGet(() -> handle.getRemoteTableName().getTableName())),
                columnNames.stream()
                        .map(this::quoted)
                        .collect(joining(", ")),
                join(", ", selectColumns.build()),
                clickhouseVarcharLiteral(join(", ", inputColumns.build())));
    }

    @Override
    public Collection<String> listSchemas(Connection connection)
    {
        // for Clickhouse, we need to list catalogs instead of schemas
        try (ResultSet resultSet = connection.getMetaData().getCatalogs()) {
            ImmutableSet.Builder<String> schemaNames = ImmutableSet.builder();
            while (resultSet.next()) {
                String schemaName = resultSet.getString("TABLE_CAT");
                // skip internal schemas
                if (filterSchema(schemaName)) {
                    schemaNames.add(schemaName);
                }
            }
            return schemaNames.build();
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    public Optional<String> getTableComment(ResultSet resultSet)
            throws SQLException
    {
        // Empty remarks means that the table doesn't have a comment in ClickHouse
        return Optional.ofNullable(emptyToNull(resultSet.getString("REMARKS")));
    }

    @Override
    protected List<String> createTableSqls(RemoteTableName remoteTableName, List<String> columns, ConnectorTableMetadata tableMetadata)
    {
        ImmutableList.Builder<String> tableOptions = ImmutableList.builder();
        Map<String, Object> tableProperties = tableMetadata.getProperties();
        ClickHouseEngineType engine = ClickHouseTableProperties.getEngine(tableProperties);
        tableOptions.add("ENGINE = " + engine.getEngineType());
        if (engine == ClickHouseEngineType.MERGETREE && formatProperty(ClickHouseTableProperties.getOrderBy(tableProperties)).isEmpty()) {
            // https://clickhouse.com/docs/en/engines/table-engines/mergetree-family/mergetree#order_by
            tableOptions.add("ORDER BY tuple()");
        }
        formatProperty(ClickHouseTableProperties.getOrderBy(tableProperties)).ifPresent(value -> tableOptions.add("ORDER BY " + value));
        formatProperty(ClickHouseTableProperties.getPrimaryKey(tableProperties)).ifPresent(value -> tableOptions.add("PRIMARY KEY " + value));
        formatProperty(ClickHouseTableProperties.getPartitionBy(tableProperties)).ifPresent(value -> tableOptions.add("PARTITION BY " + value));
        ClickHouseTableProperties.getSampleBy(tableProperties).ifPresent(value -> tableOptions.add("SAMPLE BY " + quoted(value)));
        tableMetadata.getComment().ifPresent(comment -> tableOptions.add(format("COMMENT %s", clickhouseVarcharLiteral(comment))));

        return ImmutableList.of(format("CREATE TABLE %s (%s) %s", quoted(remoteTableName), join(", ", columns), join(" ", tableOptions.build())));
    }

    @Override
    public Map<String, Object> getTableProperties(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        try (Connection connection = connectionFactory.openConnection(session);
                PreparedStatement statement = connection.prepareStatement("" +
                        "SELECT engine, sorting_key, partition_key, primary_key, sampling_key " +
                        "FROM system.tables " +
                        "WHERE database = ? AND name = ?")) {
            statement.setString(1, tableHandle.asPlainTable().getRemoteTableName().getCatalogName().orElse(null));
            statement.setString(2, tableHandle.asPlainTable().getRemoteTableName().getTableName());

            try (ResultSet resultSet = statement.executeQuery()) {
                ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();
                while (resultSet.next()) {
                    String engine = resultSet.getString("engine");
                    if (!isNullOrEmpty(engine)) {
                        // Don't throw an exception because many table engines aren't supported in ClickHouseEngineType
                        Optional<ClickHouseEngineType> engineType = Enums.getIfPresent(ClickHouseEngineType.class, engine.toUpperCase(ENGLISH)).toJavaUtil();
                        engineType.ifPresent(type -> properties.put(ENGINE_PROPERTY, type));
                    }
                    String sortingKey = resultSet.getString("sorting_key");
                    if (!isNullOrEmpty(sortingKey)) {
                        properties.put(ORDER_BY_PROPERTY, TABLE_PROPERTY_SPLITTER.splitToList(sortingKey));
                    }
                    String partitionKey = resultSet.getString("partition_key");
                    if (!isNullOrEmpty(partitionKey)) {
                        properties.put(PARTITION_BY_PROPERTY, TABLE_PROPERTY_SPLITTER.splitToList(partitionKey));
                    }
                    String primaryKey = resultSet.getString("primary_key");
                    if (!isNullOrEmpty(primaryKey)) {
                        properties.put(PRIMARY_KEY_PROPERTY, TABLE_PROPERTY_SPLITTER.splitToList(primaryKey));
                    }
                    String samplingKey = resultSet.getString("sampling_key");
                    if (!isNullOrEmpty(samplingKey)) {
                        properties.put(SAMPLE_BY_PROPERTY, samplingKey);
                    }
                }
                return properties.buildOrThrow();
            }
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    public void setTableProperties(ConnectorSession session, JdbcTableHandle handle, Map<String, Optional<Object>> nullableProperties)
    {
        // TODO: Support other table properties
        checkArgument(nullableProperties.size() == 1 && nullableProperties.containsKey(SAMPLE_BY_PROPERTY), "Only support setting 'sample_by' property");
        // TODO: Support sampling key removal when we support a newer version of ClickHouse. See https://github.com/ClickHouse/ClickHouse/pull/30180.
        checkArgument(nullableProperties.values().stream().noneMatch(Optional::isEmpty), "Setting a property to null is not supported");

        Map<String, Object> properties = nullableProperties.entrySet().stream()
                .filter(entry -> entry.getValue().isPresent())
                .collect(toImmutableMap(Entry::getKey, entry -> entry.getValue().orElseThrow()));

        ImmutableList.Builder<String> tableOptions = ImmutableList.builder();
        ClickHouseTableProperties.getSampleBy(properties).ifPresent(value -> tableOptions.add("SAMPLE BY " + quoted(value)));

        try (Connection connection = connectionFactory.openConnection(session)) {
            String sql = format(
                    "ALTER TABLE %s MODIFY %s",
                    quoted(handle.asPlainTable().getRemoteTableName()),
                    join(" ", tableOptions.build()));
            execute(session, connection, sql);
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    protected String getColumnDefinitionSql(ConnectorSession session, ColumnMetadata column, String columnName)
    {
        StringBuilder sb = new StringBuilder()
                .append(quoted(columnName))
                .append(" ");
        if (column.isNullable() && !(column.getType() instanceof ArrayType)) {
            // ClickHouse does not support Nullable(Array(T)); use Array(Nullable(T)) instead
            sb.append("Nullable(").append(toWriteMapping(session, column.getType()).getDataType()).append(")");
        }
        else {
            // By default, the clickhouse column is not allowed to be null
            sb.append(toWriteMapping(session, column.getType()).getDataType());
        }
        if (column.getComment() != null) {
            sb.append(format(" COMMENT %s", clickhouseVarcharLiteral(column.getComment())));
        }
        return sb.toString();
    }

    @Override
    protected void createSchema(ConnectorSession session, Connection connection, String remoteSchemaName)
            throws SQLException
    {
        execute(session, connection, "CREATE DATABASE " + quoted(remoteSchemaName));
    }

    @Override
    protected void dropSchema(ConnectorSession session, Connection connection, String remoteSchemaName, boolean cascade)
            throws SQLException
    {
        // ClickHouse always deletes all tables inside the database https://clickhouse.com/docs/en/sql-reference/statements/drop
        if (!cascade) {
            try (ResultSet tables = getTables(connection, Optional.of(remoteSchemaName), Optional.empty())) {
                if (tables.next()) {
                    throw new TrinoException(SCHEMA_NOT_EMPTY, "Cannot drop non-empty schema '%s'".formatted(remoteSchemaName));
                }
            }
        }
        execute(session, connection, "DROP DATABASE " + quoted(remoteSchemaName));
    }

    @Override
    protected void renameSchema(ConnectorSession session, Connection connection, String remoteSchemaName, String newRemoteSchemaName)
            throws SQLException
    {
        execute(session, connection, "RENAME DATABASE " + quoted(remoteSchemaName) + " TO " + quoted(newRemoteSchemaName));
    }

    @Override
    public void addColumn(ConnectorSession session, JdbcTableHandle handle, ColumnMetadata column, ColumnPosition position)
    {
        switch (position) {
            case ColumnPosition.First _ -> throw new TrinoException(NOT_SUPPORTED, "This connector does not support adding columns with FIRST clause");
            case ColumnPosition.After _ -> throw new TrinoException(NOT_SUPPORTED, "This connector does not support adding columns with AFTER clause");
            case ColumnPosition.Last _ -> addColumn(session, handle.asPlainTable().getRemoteTableName(), column);
        }
    }

    private void addColumn(ConnectorSession session, RemoteTableName table, ColumnMetadata column)
    {
        try (Connection connection = connectionFactory.openConnection(session)) {
            String remoteColumnName = getIdentifierMapping().toRemoteColumnName(getRemoteIdentifiers(connection), column.getName());
            String sql = format(
                    "ALTER TABLE %s ADD COLUMN %s",
                    quoted(table),
                    getColumnDefinitionSql(session, column, remoteColumnName));
            execute(session, connection, sql);
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    public void setTableComment(ConnectorSession session, JdbcTableHandle handle, Optional<String> comment)
    {
        String sql = format(
                "ALTER TABLE %s MODIFY COMMENT %s",
                quoted(handle.asPlainTable().getRemoteTableName()),
                clickhouseVarcharLiteral(comment.orElse(NO_COMMENT)));
        execute(session, sql);
    }

    @Override
    public void setColumnComment(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Optional<String> comment)
    {
        String sql = format(
                "ALTER TABLE %s COMMENT COLUMN %s %s",
                quoted(handle.asPlainTable().getRemoteTableName()),
                quoted(column.getColumnName()),
                clickhouseVarcharLiteral(comment.orElse("")));
        execute(session, sql);
    }

    @Override
    public void setColumnType(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Type type)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting column types");
    }

    @Override
    public void dropNotNullConstraint(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping a not null constraint");
    }

    private static String clickhouseVarcharLiteral(String value)
    {
        requireNonNull(value, "value is null");
        return convertToQuotedString(value);
    }

    @Override
    protected Optional<List<String>> getTableTypes()
    {
        return Optional.empty();
    }

    @Override
    protected void renameTable(ConnectorSession session, Connection connection, String catalogName, String remoteSchemaName, String remoteTableName, String newRemoteSchemaName, String newRemoteTableName)
            throws SQLException
    {
        execute(session, connection, format("RENAME TABLE %s TO %s",
                quoted(catalogName, remoteSchemaName, remoteTableName),
                quoted(catalogName, newRemoteSchemaName, newRemoteTableName)));
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> sql + " LIMIT " + limit);
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    public OptionalLong delete(ConnectorSession session, JdbcTableHandle handle)
    {
        // ClickHouse does not support DELETE syntax, but is using custom: ALTER TABLE [db.]table [ON CLUSTER cluster] DELETE WHERE filter_expr
        throw new TrinoException(NOT_SUPPORTED, MODIFYING_ROWS_MESSAGE);
    }

    @Override
    public OptionalLong update(ConnectorSession session, JdbcTableHandle handle)
    {
        throw new TrinoException(NOT_SUPPORTED, MODIFYING_ROWS_MESSAGE);
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        String jdbcTypeName = typeHandle.jdbcTypeName()
                .orElseThrow(() -> new TrinoException(JDBC_ERROR, "Type name is missing: " + typeHandle));

        Optional<ColumnMapping> mapping = getForcedMappingToVarchar(typeHandle);
        if (mapping.isPresent()) {
            return mapping;
        }

        ClickHouseColumn column = ClickHouseColumn.of("", jdbcTypeName);
        ClickHouseDataType columnDataType = column.getDataType();
        switch (columnDataType) {
            case UInt8:
                return Optional.of(ColumnMapping.longMapping(SMALLINT, ResultSet::getShort, uInt8WriteFunction(getClickHouseServerVersion(session))));
            case UInt16:
                return Optional.of(ColumnMapping.longMapping(INTEGER, ResultSet::getInt, uInt16WriteFunction(getClickHouseServerVersion(session))));
            case UInt32:
                return Optional.of(ColumnMapping.longMapping(BIGINT, ResultSet::getLong, uInt32WriteFunction(getClickHouseServerVersion(session))));
            case UInt64:
                return Optional.of(ColumnMapping.objectMapping(
                        UINT64_TYPE,
                        longDecimalReadFunction(UINT64_TYPE, UNNECESSARY),
                        uInt64WriteFunction(getClickHouseServerVersion(session))));
            case IPv4:
            case IPv6:
                return Optional.of(ipAddressColumnMapping(column.getOriginalTypeName()));
            case Enum8:
            case Enum16:
                return Optional.of(ColumnMapping.sliceMapping(
                        createUnboundedVarcharType(),
                        varcharReadFunction(createUnboundedVarcharType()),
                        varcharWriteFunction(),
                        // TODO (https://github.com/trinodb/trino/issues/7100) Currently pushdown would not work and may require a custom bind expression
                        DISABLE_PUSHDOWN));

            case FixedString: // FixedString(n)
            case String:
                if (isMapStringAsVarchar(session)) {
                    return Optional.of(ColumnMapping.sliceMapping(
                            createUnboundedVarcharType(),
                            // ClickHouse strings are arbitrary bytes, and getString would replace invalid UTF-8
                            (resultSet, columnIndex) -> wrappedBuffer(resultSet.getBytes(columnIndex)),
                            varcharWriteFunction(),
                            FULL_PUSHDOWN));
                }
                return Optional.of(varbinaryColumnMapping());
            case UUID:
                return Optional.of(uuidColumnMapping());
            case Map: {
                Optional<ColumnMapping> columnMapping = mapColumnMapping(session, connection, column);
                if (columnMapping.isPresent()) {
                    return columnMapping;
                }
                break;
            }
            case Tuple: {
                Optional<ColumnMapping> columnMapping = rowColumnMapping(session, connection, column);
                if (columnMapping.isPresent()) {
                    return columnMapping;
                }
                break;
            }
            case Point:
            case Ring:
            case LineString:
            case Polygon:
            case MultiLineString:
            case MultiPolygon:
                return Optional.of(geoColumnMapping(geoType(columnDataType)));
            default:
                // no-op
        }

        switch (typeHandle.jdbcType()) {
            case Types.TINYINT:
                return Optional.of(tinyintColumnMapping());

            case Types.SMALLINT:
                return Optional.of(smallintColumnMapping());

            case Types.INTEGER:
                return Optional.of(integerColumnMapping());

            case Types.BIGINT:
                return Optional.of(bigintColumnMapping());

            case Types.FLOAT:
            case Types.REAL:
                return Optional.of(ColumnMapping.longMapping(
                        REAL,
                        (resultSet, columnIndex) -> floatToRawIntBits(resultSet.getFloat(columnIndex)),
                        realWriteFunction(),
                        DISABLE_PUSHDOWN));

            case Types.DOUBLE:
                return Optional.of(doubleColumnMapping());

            case Types.DECIMAL:
                int decimalDigits = typeHandle.requiredDecimalDigits();
                int precision = typeHandle.requiredColumnSize();

                ColumnMapping decimalColumnMapping;
                if (getDecimalRounding(session) == ALLOW_OVERFLOW && precision > Decimals.MAX_PRECISION) {
                    int scale = Math.min(decimalDigits, getDecimalDefaultScale(session));
                    decimalColumnMapping = decimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, scale), getDecimalRoundingMode(session));
                }
                else {
                    decimalColumnMapping = decimalColumnMapping(createDecimalType(precision, max(decimalDigits, 0)));
                }
                return Optional.of(ColumnMapping.mapping(
                        decimalColumnMapping.getType(),
                        decimalColumnMapping.getReadFunction(),
                        decimalColumnMapping.getWriteFunction(),
                        // TODO (https://github.com/trinodb/trino/issues/7100) fix, enable and test decimal pushdown
                        DISABLE_PUSHDOWN));

            case Types.DATE:
                return Optional.of(dateColumnMappingUsingLocalDate(getClickHouseServerVersion(session)));

            case Types.TIMESTAMP:
                if (columnDataType == ClickHouseDataType.DateTime) {
                    // ClickHouse DateTime does not have sub-second precision
                    verify(typeHandle.requiredDecimalDigits() == 0, "Expected 0 as timestamp precision, but got %s", typeHandle.requiredDecimalDigits());
                    return Optional.of(ColumnMapping.longMapping(
                            TIMESTAMP_SECONDS,
                            timestampReadFunction(TIMESTAMP_SECONDS),
                            timestampSecondsWriteFunction(getClickHouseServerVersion(session))));
                }
                if (columnDataType == ClickHouseDataType.DateTime64) {
                    return Optional.of(dateTime64ColumnMapping(column.getScale(), getClickHouseServerVersion(session)));
                }
                return Optional.of(timestampColumnMapping(TIMESTAMP_MILLIS));

            case Types.TIMESTAMP_WITH_TIMEZONE:
                if (columnDataType == ClickHouseDataType.DateTime) {
                    // ClickHouse DateTime does not have sub-second precision
                    verify(typeHandle.requiredDecimalDigits() == 0, "Expected 0 as timestamp with time zone precision, but got %s", typeHandle.requiredDecimalDigits());
                    return Optional.of(ColumnMapping.longMapping(
                            TIMESTAMP_TZ_SECONDS,
                            shortTimestampWithTimeZoneReadFunction(),
                            shortTimestampWithTimeZoneWriteFunction(column.getTimeZone())));
                }
                if (columnDataType == ClickHouseDataType.DateTime64) {
                    return Optional.of(dateTime64WithTimeZoneColumnMapping(column.getScale(), column.getTimeZone().toZoneId(), getClickHouseServerVersion(session)));
                }
                break;

            case Types.ARRAY: {
                Optional<ColumnMapping> columnMapping = arrayToTrinoType(session, connection, typeHandle);
                if (columnMapping.isPresent()) {
                    return columnMapping;
                }
                break;
            }
        }

        if (getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
            return mapToUnboundedVarchar(typeHandle);
        }

        return Optional.empty();
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        if (type == BOOLEAN) {
            // ClickHouse is no separate type for boolean values. Use UInt8 type, restricted to the values 0 or 1.
            return WriteMapping.booleanMapping("UInt8", booleanWriteFunction());
        }
        if (type == TINYINT) {
            return WriteMapping.longMapping("Int8", tinyintWriteFunction());
        }
        if (type == SMALLINT) {
            return WriteMapping.longMapping("Int16", smallintWriteFunction());
        }
        if (type == INTEGER) {
            return WriteMapping.longMapping("Int32", integerWriteFunction());
        }
        if (type == BIGINT) {
            return WriteMapping.longMapping("Int64", bigintWriteFunction());
        }
        if (type == REAL) {
            return WriteMapping.longMapping("Float32", realWriteFunction());
        }
        if (type == DOUBLE) {
            return WriteMapping.doubleMapping("Float64", doubleWriteFunction());
        }
        if (type instanceof DecimalType decimalType) {
            String dataType = format("Decimal(%s, %s)", decimalType.getPrecision(), decimalType.getScale());
            if (decimalType.isShort()) {
                return WriteMapping.longMapping(dataType, shortDecimalWriteFunction(decimalType));
            }
            return WriteMapping.objectMapping(dataType, longDecimalWriteFunction(decimalType));
        }
        if (type instanceof CharType || type instanceof VarcharType) {
            // The String type replaces the types VARCHAR, BLOB, CLOB, and others from other DBMSs.
            return WriteMapping.sliceMapping("String", varcharWriteFunction());
        }
        if (type instanceof VarbinaryType) {
            // Strings of an arbitrary length. The length is not limited
            return WriteMapping.sliceMapping("String", varbinaryWriteFunction());
        }
        if (type == DATE) {
            return WriteMapping.longMapping("Date", dateWriteFunctionUsingLocalDate(getClickHouseServerVersion(session)));
        }
        if (type == TIMESTAMP_SECONDS) {
            return WriteMapping.longMapping("DateTime", timestampSecondsWriteFunction(getClickHouseServerVersion(session)));
        }
        if (type.equals(uuidType)) {
            return WriteMapping.sliceMapping("UUID", uuidWriteFunction());
        }
        if (type.equals(ipAddressType)) {
            // Required for array(ipaddress) write: toWriteMapping recurses on element types,
            // and without this mapping, writing Array(IPv6) columns would throw NOT_SUPPORTED
            return WriteMapping.sliceMapping("IPv6", ipAddressWriteFunction("IPv6"));
        }
        if (type instanceof ArrayType arrayType) {
            WriteMapping elementMapping = toWriteMapping(session, arrayType.getElementType());
            String elementDataType = elementMapping.getDataType();
            // ClickHouse does not support Nullable(Array(T)) at column level; nested array elements
            // cannot be Nullable, but scalar elements support Array(Nullable(T))
            boolean isNestedArray = arrayType.getElementType() instanceof ArrayType;
            String columnType;
            String writeElementType;
            if (isNestedArray) {
                columnType = "Array(%s)".formatted(elementDataType);
                writeElementType = elementDataType;
            }
            else {
                // Wrap scalar elements in Nullable so null values are stored correctly;
                // createArrayOf("Float64", [null]) would silently convert null to 0.0
                columnType = "Array(Nullable(%s))".formatted(elementDataType);
                writeElementType = "Nullable(%s)".formatted(elementDataType);
            }
            return WriteMapping.objectMapping(columnType, arrayWriteFunction(arrayType.getElementType(), writeElementType));
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported column type: " + type);
    }

    private ClickHouseVersion getClickHouseServerVersion(ConnectorSession session)
    {
        return clickHouseVersion.updateAndGet(current -> {
            if (current != null) {
                return current;
            }

            try (Connection connection = connectionFactory.openConnection(session);
                    PreparedStatement statement = connection.prepareStatement("SELECT version()");
                    ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    current = ClickHouseVersion.of(resultSet.getString(1));
                }
                return current;
            }
            catch (SQLException e) {
                throw new TrinoException(JDBC_ERROR, e);
            }
        });
    }

    /**
     * format property to match ClickHouse create table statement
     *
     * @param prop property will be formatted
     * @return formatted property
     */
    private Optional<String> formatProperty(List<String> prop)
    {
        if (prop == null || prop.isEmpty()) {
            return Optional.empty();
        }
        if (prop.size() == 1) {
            // only one column
            return Optional.of(quoted(prop.get(0)));
        }
        // include more than one column
        return Optional.of(prop.stream().map(this::quoted).collect(Collectors.joining(",", "(", ")")));
    }

    private static LongWriteFunction uInt8WriteFunction(ClickHouseVersion version)
    {
        return (statement, index, value) -> {
            // ClickHouse stores incorrect results when the values are out of supported range.
            UINT8.validate(version, value);
            statement.setShort(index, Shorts.checkedCast(value));
        };
    }

    private static LongWriteFunction uInt16WriteFunction(ClickHouseVersion version)
    {
        return (statement, index, value) -> {
            // ClickHouse stores incorrect results when the values are out of supported range.
            UINT16.validate(version, value);
            statement.setInt(index, toIntExact(value));
        };
    }

    private static LongWriteFunction uInt32WriteFunction(ClickHouseVersion version)
    {
        return (preparedStatement, parameterIndex, value) -> {
            // ClickHouse stores incorrect results when the values are out of supported range.
            UINT32.validate(version, value);
            preparedStatement.setLong(parameterIndex, value);
        };
    }

    private static ObjectWriteFunction uInt64WriteFunction(ClickHouseVersion version)
    {
        return ObjectWriteFunction.of(
                Int128.class,
                (statement, index, value) -> {
                    BigInteger unscaledValue = value.toBigInteger();
                    BigDecimal bigDecimal = new BigDecimal(unscaledValue, UINT64_TYPE.getScale(), new MathContext(UINT64_TYPE.getPrecision()));
                    // ClickHouse stores incorrect results when the values are out of supported range.
                    UINT64.validate(version, bigDecimal);
                    statement.setBigDecimal(index, bigDecimal);
                });
    }

    private static ColumnMapping dateColumnMappingUsingLocalDate(ClickHouseVersion version)
    {
        return ColumnMapping.longMapping(
                DATE,
                dateReadFunctionUsingLocalDate(),
                dateWriteFunctionUsingLocalDate(version));
    }

    private static LongWriteFunction dateWriteFunctionUsingLocalDate(ClickHouseVersion version)
    {
        return (statement, index, value) -> {
            LocalDate date = LocalDate.ofEpochDay(value);
            // Deny unsupported dates eagerly to prevent unexpected results. ClickHouse stores '1970-01-01' when the date is out of supported range.
            TrinoToClickHouseWriteChecker.DATE.validate(version, date);
            statement.setObject(index, date);
        };
    }

    private static LongWriteFunction timestampSecondsWriteFunction(ClickHouseVersion version)
    {
        return (statement, index, value) -> {
            long epochSecond = floorDiv(value, MICROSECONDS_PER_SECOND);
            int nanoFraction = floorMod(value, MICROSECONDS_PER_SECOND) * NANOSECONDS_PER_MICROSECOND;
            verify(nanoFraction == 0, "Nanos of second must be zero: '%s'", value);
            LocalDateTime timestamp = LocalDateTime.ofEpochSecond(epochSecond, 0, UTC);
            // ClickHouse stores incorrect results when the values are out of supported range.
            DATETIME.validate(version, timestamp);
            statement.setObject(index, timestamp);
        };
    }

    private static LongReadFunction shortTimestampWithTimeZoneReadFunction()
    {
        return (resultSet, columnIndex) -> {
            ZonedDateTime zonedDateTime = resultSet.getObject(columnIndex, ZonedDateTime.class);
            return packDateTimeWithZone(zonedDateTime.toInstant().toEpochMilli(), zonedDateTime.getZone().getId());
        };
    }

    private static LongWriteFunction shortTimestampWithTimeZoneWriteFunction(TimeZone columnTimeZone)
    {
        return (statement, index, value) -> {
            long millisUtc = unpackMillisUtc(value);
            // Clickhouse JDBC driver inserts datetime as string value as yyyy-MM-dd HH:mm:ss and zone from the Column metadata would be used.
            statement.setObject(index, Instant.ofEpochMilli(millisUtc).atZone(columnTimeZone.toZoneId()));
        };
    }

    private static ColumnMapping dateTime64ColumnMapping(int precision, ClickHouseVersion version)
    {
        TimestampType timestampType = createTimestampType(precision);
        TrinoToClickHouseWriteChecker<LocalDateTime> checker = dateTime64WriteChecker(precision);
        if (timestampType.isShort()) {
            return ColumnMapping.longMapping(
                    timestampType,
                    timestampReadFunction(timestampType),
                    (statement, index, epochMicros) -> {
                        LocalDateTime timestamp = fromTrinoTimestamp(epochMicros);
                        // ClickHouse stores incorrect results when the values are out of supported range.
                        checker.validate(version, timestamp);
                        statement.setObject(index, timestamp);
                    });
        }
        return ColumnMapping.objectMapping(
                timestampType,
                longTimestampReadFunction(timestampType),
                ObjectWriteFunction.of(LongTimestamp.class, (statement, index, value) -> {
                    LocalDateTime timestamp = fromLongTrinoTimestamp(value, precision);
                    checker.validate(version, timestamp);
                    statement.setObject(index, timestamp);
                }));
    }

    private static ColumnMapping dateTime64WithTimeZoneColumnMapping(int precision, ZoneId columnZone, ClickHouseVersion version)
    {
        TimestampWithTimeZoneType timestampType = createTimestampWithTimeZoneType(precision);
        if (timestampType.isShort()) {
            return ColumnMapping.longMapping(
                    timestampType,
                    (resultSet, columnIndex) -> {
                        Instant instant = resultSet.getObject(columnIndex, ZonedDateTime.class).toInstant();
                        return packDateTimeWithZone(instant.toEpochMilli(), timeZoneKey(instant, columnZone));
                    },
                    shortDateTime64WithTimeZoneWriteFunction(precision, version));
        }
        return ColumnMapping.objectMapping(
                timestampType,
                ObjectReadFunction.of(LongTimestampWithTimeZone.class, (resultSet, columnIndex) -> {
                    Instant instant = resultSet.getObject(columnIndex, ZonedDateTime.class).toInstant();
                    return LongTimestampWithTimeZone.fromEpochSecondsAndFraction(
                            instant.getEpochSecond(),
                            (long) instant.getNano() * PICOSECONDS_PER_NANOSECOND,
                            timeZoneKey(instant, columnZone));
                }),
                longDateTime64WithTimeZoneWriteFunction(precision, version));
    }

    private static TimeZoneKey timeZoneKey(Instant instant, ZoneId zone)
    {
        int offsetSeconds = zone.getRules().getOffset(instant).getTotalSeconds();
        if (offsetSeconds % 60 != 0) {
            // Trino offset zones are whole minutes, unlike historical local mean time offsets such as +05:53:28
            return TimeZoneKey.getTimeZoneKey(zone.getId());
        }
        return TimeZoneKey.getTimeZoneKeyForOffset(offsetSeconds / 60);
    }

    private static LongWriteFunction shortDateTime64WithTimeZoneWriteFunction(int precision, ClickHouseVersion version)
    {
        TrinoToClickHouseWriteChecker<LocalDateTime> checker = dateTime64WriteChecker(precision);
        return new LongWriteFunction()
        {
            @Override
            public String getBindExpression()
            {
                return utcDateTime64BindExpression(precision);
            }

            @Override
            public void set(PreparedStatement statement, int index, long value)
                    throws SQLException
            {
                Instant instant = Instant.ofEpochMilli(unpackMillisUtc(value));
                checker.validate(version, LocalDateTime.ofInstant(instant, UTC));
                statement.setString(index, UTC_DATETIME64_FORMATTER.format(instant));
            }
        };
    }

    private static ObjectWriteFunction longDateTime64WithTimeZoneWriteFunction(int precision, ClickHouseVersion version)
    {
        TrinoToClickHouseWriteChecker<LocalDateTime> checker = dateTime64WriteChecker(precision);
        return new ObjectWriteFunction()
        {
            @Override
            public Class<?> getJavaType()
            {
                return LongTimestampWithTimeZone.class;
            }

            @Override
            public String getBindExpression()
            {
                return utcDateTime64BindExpression(precision);
            }

            @Override
            public void set(PreparedStatement statement, int index, Object value)
                    throws SQLException
            {
                LongTimestampWithTimeZone timestamp = (LongTimestampWithTimeZone) value;
                Instant instant = Instant.ofEpochMilli(timestamp.getEpochMillis())
                        .plusNanos(timestamp.getPicosOfMilli() / PICOSECONDS_PER_NANOSECOND);
                checker.validate(version, LocalDateTime.ofInstant(instant, UTC));
                statement.setString(index, UTC_DATETIME64_FORMATTER.format(instant));
            }
        };
    }

    private static String utcDateTime64BindExpression(int precision)
    {
        return "toDateTime64(?, %s, 'UTC')".formatted(precision);
    }

    private static TrinoToClickHouseWriteChecker<LocalDateTime> dateTime64WriteChecker(int precision)
    {
        return precision == 9 ? DATETIME64_NANOS : DATETIME64;
    }

    // The JDBC driver converts DateTime64 values through local date-time in the column time zone, which is ambiguous
    // when clocks are set back, so zoned DateTime64 columns are read and written as UTC values. The driver also cannot
    // read or write LineString and MultiLineString, which ClickHouse stores as Point arrays.
    static String toTransferType(String clickHouseType)
    {
        String type = replaceLineStringTypes(clickHouseType);
        String nullablePrefix = "Nullable(";
        boolean nullable = type.startsWith(nullablePrefix) && type.endsWith(")");
        String baseType = nullable ? type.substring(nullablePrefix.length(), type.length() - 1) : type;
        Matcher matcher = ZONED_DATETIME64.matcher(baseType);
        if (!matcher.matches() || matcher.group(2).equals("UTC")) {
            return type;
        }
        String utcType = "DateTime64(%s, 'UTC')".formatted(matcher.group(1));
        return nullable ? nullablePrefix + utcType + ")" : utcType;
    }

    private static String replaceLineStringTypes(String type)
    {
        StringBuilder result = new StringBuilder();
        Matcher quoted = QUOTED_LITERAL.matcher(type);
        int position = 0;
        while (quoted.find()) {
            result.append(replaceLineStrings(type.substring(position, quoted.start()))).append(quoted.group());
            position = quoted.end();
        }
        return result.append(replaceLineStrings(type.substring(position))).toString();
    }

    private static String replaceLineStrings(String text)
    {
        String replaced = MULTI_LINE_STRING.matcher(text).replaceAll("Array(Array(Point))");
        return LINE_STRING.matcher(replaced).replaceAll("Array(Point)");
    }

    private ColumnMapping ipAddressColumnMapping(String clickhouseType)
    {
        return ColumnMapping.sliceMapping(
                ipAddressType,
                (resultSet, columnIndex) -> {
                    // copied from IpAddressOperators.castFromVarcharToIpAddress
                    byte[] address = InetAddresses.forString(resultSet.getString(columnIndex)).getAddress();

                    byte[] bytes;
                    if (address.length == 4) {
                        bytes = new byte[16];
                        bytes[10] = (byte) 0xff;
                        bytes[11] = (byte) 0xff;
                        arraycopy(address, 0, bytes, 12, 4);
                    }
                    else if (address.length == 16) {
                        bytes = address;
                    }
                    else {
                        throw new TrinoException(GENERIC_INTERNAL_ERROR, "Invalid InetAddress length: " + address.length);
                    }

                    return wrappedBuffer(bytes);
                },
                ipAddressWriteFunction(clickhouseType));
    }

    private static SliceWriteFunction ipAddressWriteFunction(String clickhouseType)
    {
        return new SliceWriteFunction()
        {
            @Override
            public String getBindExpression()
            {
                return format("CAST(? AS %s)", clickhouseType);
            }

            @Override
            public void set(PreparedStatement statement, int index, Slice value)
                    throws SQLException
            {
                try {
                    statement.setObject(index, InetAddresses.toAddrString(InetAddress.getByAddress(value.getBytes())), Types.OTHER);
                }
                catch (UnknownHostException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    private ColumnMapping uuidColumnMapping()
    {
        return ColumnMapping.sliceMapping(
                uuidType,
                (resultSet, columnIndex) -> javaUuidToTrinoUuid((UUID) resultSet.getObject(columnIndex)),
                uuidWriteFunction());
    }

    private static SliceWriteFunction uuidWriteFunction()
    {
        return (statement, index, value) -> statement.setObject(index, trinoUuidToJavaUuid(value), Types.OTHER);
    }

    private Optional<ColumnMapping> arrayToTrinoType(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        String typeName = typeHandle.jdbcTypeName()
                .orElseThrow(() -> new TrinoException(JDBC_ERROR, "Type name is missing: " + typeHandle));
        ClickHouseColumn column = ClickHouseColumn.of("", typeName);
        if (!column.isArray()) {
            return Optional.empty();
        }
        ClickHouseColumn baseColumn = column.getArrayBaseColumn();
        if (baseColumn == null) {
            return Optional.empty();
        }
        int arrayLevel = column.getArrayNestedLevel();

        Optional<ColumnMapping> baseElementMapping = toColumnMapping(session, connection, toJdbcTypeHandle(baseColumn));
        if (baseElementMapping.isEmpty()) {
            return Optional.empty();
        }

        Type elementType = baseElementMapping.get().getType();
        // Use Nullable element type for createArrayOf so null values are stored correctly;
        // createArrayOf("Float64", null) would silently convert null to 0.0.
        // ClickHouse does not allow Nullable around Tuple, Map and geo types.
        String elementWriteTypeName = elementType instanceof RowType || elementType instanceof MapType || elementType instanceof ArrayType
                ? baseColumn.getOriginalTypeName()
                : "Nullable(" + baseColumn.getOriginalTypeName() + ")";
        ArrayType arrayType = new ArrayType(elementType);
        ColumnMapping arrayColumnMapping = arrayColumnMapping(arrayType, elementWriteTypeName);
        for (int i = 1; i < arrayLevel; i++) {
            elementWriteTypeName = "Array(" + elementWriteTypeName + ")";
            arrayType = new ArrayType(arrayType);
            arrayColumnMapping = arrayColumnMapping(arrayType, elementWriteTypeName);
        }
        return Optional.of(arrayColumnMapping);
    }

    private ColumnMapping arrayColumnMapping(ArrayType arrayType, String baseElementJdbcTypeName)
    {
        return ColumnMapping.objectMapping(
                arrayType,
                arrayReadFunction(arrayType.getElementType()),
                arrayWriteFunction(arrayType.getElementType(), baseElementJdbcTypeName),
                DISABLE_PUSHDOWN);
    }

    private Optional<ColumnMapping> mapColumnMapping(ConnectorSession session, Connection connection, ClickHouseColumn column)
    {
        Optional<Type> keyType = nestedType(session, connection, column.getKeyInfo());
        Optional<Type> valueType = nestedType(session, connection, column.getValueInfo());
        if (keyType.isEmpty() || valueType.isEmpty() || !keyType.get().isComparable()) {
            return Optional.empty();
        }
        return Optional.of(nestedColumnMapping(new MapType(keyType.get(), valueType.get(), typeOperators)));
    }

    private Optional<ColumnMapping> rowColumnMapping(ConnectorSession session, Connection connection, ClickHouseColumn column)
    {
        ImmutableList.Builder<RowType.Field> fields = ImmutableList.builder();
        for (ClickHouseColumn field : column.getNestedColumns()) {
            Optional<Type> fieldType = nestedType(session, connection, field);
            if (fieldType.isEmpty()) {
                return Optional.empty();
            }
            fields.add(field.getColumnName().isEmpty() ? RowType.field(fieldType.get()) : RowType.field(field.getColumnName(), fieldType.get()));
        }
        return Optional.of(nestedColumnMapping(RowType.from(fields.build())));
    }

    private Optional<Type> nestedType(ConnectorSession session, Connection connection, ClickHouseColumn column)
    {
        return toColumnMapping(session, connection, toJdbcTypeHandle(column)).map(ColumnMapping::getType);
    }

    private static JdbcTypeHandle toJdbcTypeHandle(ClickHouseColumn column)
    {
        return new JdbcTypeHandle(
                JdbcTypeMapping.getDefaultMapping().toSqlType(column, null),
                Optional.of(column.getOriginalTypeName()),
                column.getPrecision() > 0 ? Optional.of(column.getPrecision()) : Optional.empty(),
                column.getPrecision() > 0 ? Optional.of(column.getScale()) : Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Type geoType(ClickHouseDataType dataType)
    {
        return switch (dataType) {
            case Point -> GEO_POINT;
            case Ring, LineString -> new ArrayType(GEO_POINT);
            case Polygon, MultiLineString -> new ArrayType(new ArrayType(GEO_POINT));
            case MultiPolygon -> new ArrayType(new ArrayType(new ArrayType(GEO_POINT)));
            default -> throw new IllegalArgumentException("Unexpected geo type: " + dataType);
        };
    }

    private ColumnMapping nestedColumnMapping(Type type)
    {
        return ColumnMapping.objectMapping(
                type,
                nestedReadFunction(type),
                objectWriteFunction(type, (block, position) -> toJdbcObjectValue(type, block, position)),
                DISABLE_PUSHDOWN);
    }

    private ColumnMapping geoColumnMapping(Type type)
    {
        return ColumnMapping.objectMapping(
                type,
                nestedReadFunction(type),
                objectWriteFunction(type, (block, position) -> toGeoJdbcValue(type, block, position)),
                DISABLE_PUSHDOWN);
    }

    @SuppressWarnings("unchecked")
    private ObjectReadFunction nestedReadFunction(Type type)
    {
        return ObjectReadFunction.of((Class<Object>) type.getJavaType(), (resultSet, columnIndex) -> {
            BlockBuilder builder = type.createBlockBuilder(null, 1);
            appendValue(type, builder, resultSet.getObject(columnIndex), 0);
            return type.getObject(builder.build(), 0);
        });
    }

    @SuppressWarnings("unchecked")
    private static ObjectWriteFunction objectWriteFunction(Type type, BiFunction<Block, Integer, Object> toJdbcValue)
    {
        return ObjectWriteFunction.of((Class<Object>) type.getJavaType(), (statement, index, value) -> {
            BlockBuilder builder = type.createBlockBuilder(null, 1);
            type.writeObject(builder, value);
            statement.setObject(index, toJdbcValue.apply(builder.build(), 0));
        });
    }

    // The JDBC driver accepts geo values as nested double arrays, for example double[][] for a Ring
    private static Object toGeoJdbcValue(Type type, Block block, int position)
    {
        if (block.isNull(position)) {
            throw new TrinoException(INVALID_ARGUMENTS, "ClickHouse geo values cannot contain null");
        }
        if (type instanceof RowType rowType) {
            SqlRow point = rowType.getObject(block, position);
            double[] coordinates = new double[2];
            for (int field = 0; field < 2; field++) {
                Block fieldBlock = point.getRawFieldBlock(field);
                if (fieldBlock.isNull(point.getRawIndex())) {
                    throw new TrinoException(INVALID_ARGUMENTS, "ClickHouse geo values cannot contain null");
                }
                coordinates[field] = DOUBLE.getDouble(fieldBlock, point.getRawIndex());
            }
            return coordinates;
        }
        ArrayType arrayType = (ArrayType) type;
        Block elements = arrayType.getObject(block, position);
        Object values = java.lang.reflect.Array.newInstance(geoJavaClass(arrayType.getElementType()), elements.getPositionCount());
        for (int i = 0; i < elements.getPositionCount(); i++) {
            java.lang.reflect.Array.set(values, i, toGeoJdbcValue(arrayType.getElementType(), elements, i));
        }
        return values;
    }

    private static Class<?> geoJavaClass(Type type)
    {
        if (type instanceof ArrayType arrayType) {
            return geoJavaClass(arrayType.getElementType()).arrayType();
        }
        return double[].class;
    }

    private ObjectReadFunction arrayReadFunction(Type elementType)
    {
        return ObjectReadFunction.of(Block.class, (resultSet, columnIndex) -> {
            Array array = resultSet.getArray(columnIndex);
            if (array == null) {
                return elementType.createBlockBuilder(null, 0).build();
            }
            return buildArrayBlock(elementType, array.getArray(), 0);
        });
    }

    private Block buildArrayBlock(Type elementType, Object javaArray, int depth)
            throws SQLException
    {
        if (depth >= MAX_ARRAY_NESTING_DEPTH) {
            throw new TrinoException(NOT_SUPPORTED, "Array nesting depth exceeds maximum of %d".formatted(MAX_ARRAY_NESTING_DEPTH));
        }
        if (javaArray == null) {
            return elementType.createBlockBuilder(null, 0).build();
        }
        if (javaArray instanceof List<?> list) {
            BlockBuilder builder = elementType.createBlockBuilder(null, list.size());
            for (Object element : list) {
                appendValue(elementType, builder, element, depth);
            }
            return builder.build();
        }
        int length;
        try {
            length = java.lang.reflect.Array.getLength(javaArray);
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(JDBC_ERROR, "Unexpected array value from ClickHouse JDBC: %s".formatted(javaArray.getClass()), e);
        }
        BlockBuilder builder = elementType.createBlockBuilder(null, length);
        for (int i = 0; i < length; i++) {
            appendValue(elementType, builder, java.lang.reflect.Array.get(javaArray, i), depth);
        }
        return builder.build();
    }

    private void appendValue(Type type, BlockBuilder builder, Object value, int depth)
            throws SQLException
    {
        if (value == null) {
            builder.appendNull();
            return;
        }
        if (type instanceof MapType mapType) {
            checkNestingDepth(depth);
            if (!(value instanceof Map<?, ?> map)) {
                throw new TrinoException(JDBC_ERROR, "Unexpected map value from ClickHouse JDBC: " + value.getClass());
            }
            ((MapBlockBuilder) builder).buildEntry((keyBuilder, valueBuilder) -> {
                for (Entry<?, ?> entry : map.entrySet()) {
                    appendValue(mapType.getKeyType(), keyBuilder, entry.getKey(), depth + 1);
                    appendValue(mapType.getValueType(), valueBuilder, entry.getValue(), depth + 1);
                }
            });
            return;
        }
        if (type instanceof RowType rowType) {
            checkNestingDepth(depth);
            List<?> fieldValues = toFieldValues(value);
            List<RowType.Field> fields = rowType.getFields();
            if (fieldValues.size() != fields.size()) {
                throw new TrinoException(JDBC_ERROR, "Expected %s tuple elements from ClickHouse JDBC, but got %s".formatted(fields.size(), fieldValues.size()));
            }
            ((RowBlockBuilder) builder).buildEntry(fieldBuilders -> {
                for (int i = 0; i < fields.size(); i++) {
                    appendValue(fields.get(i).getType(), fieldBuilders.get(i), fieldValues.get(i), depth + 1);
                }
            });
            return;
        }
        if (type instanceof ArrayType arrayType) {
            // ClickHouse JDBC may return nested arrays as java.sql.Array or as Java arrays
            Object innerArray;
            if (value instanceof Array sqlArray) {
                innerArray = sqlArray.getArray();
            }
            else {
                innerArray = value;
            }
            type.writeObject(builder, buildArrayBlock(arrayType.getElementType(), innerArray, depth + 1));
            return;
        }
        if (type instanceof TimestampType timestampType) {
            LocalDateTime dateTime = (LocalDateTime) value;
            if (timestampType.isShort()) {
                type.writeLong(builder, toTrinoTimestamp(timestampType, dateTime));
            }
            else {
                type.writeObject(builder, toLongTrinoTimestamp(timestampType, dateTime));
            }
            return;
        }
        if (type instanceof TimestampWithTimeZoneType timestampWithTimeZoneType) {
            writeTimestampWithTimeZone(timestampWithTimeZoneType, builder, value);
            return;
        }
        if (type.getJavaType() == boolean.class) {
            type.writeBoolean(builder, (Boolean) value);
        }
        else if (type.getJavaType() == long.class) {
            if (value instanceof BigDecimal bigDecimal && type instanceof DecimalType decimalType) {
                // ClickHouse JDBC returns BigDecimal for short Decimal(p,s) array elements
                type.writeLong(builder, bigDecimal.setScale(decimalType.getScale(), UNNECESSARY).unscaledValue().longValueExact());
            }
            else if (value instanceof LocalDate date) {
                type.writeLong(builder, date.toEpochDay());
            }
            else if (value instanceof Date sqlDate) {
                type.writeLong(builder, sqlDate.toLocalDate().toEpochDay());
            }
            else if (value instanceof Float floatValue) {
                type.writeLong(builder, floatToRawIntBits(floatValue));
            }
            else if (value instanceof Byte byteValue) {
                // UInt8 (maps to SMALLINT) returns raw signed bytes; Int8 (maps to TINYINT) is already signed
                type.writeLong(builder, type.equals(SMALLINT) ? Byte.toUnsignedLong(byteValue) : byteValue.longValue());
            }
            else if (value instanceof Short shortValue) {
                // UInt16 (maps to INTEGER) returns raw signed shorts; Int16 (maps to SMALLINT) is already signed
                type.writeLong(builder, type.equals(INTEGER) ? Short.toUnsignedLong(shortValue) : shortValue.longValue());
            }
            else if (value instanceof Integer intValue) {
                // UInt32 (maps to BIGINT) returns raw signed ints; Int32 (maps to INTEGER) is already signed
                type.writeLong(builder, type.equals(BIGINT) ? Integer.toUnsignedLong(intValue) : intValue.longValue());
            }
            else {
                type.writeLong(builder, ((Number) value).longValue());
            }
        }
        else if (type.getJavaType() == double.class) {
            type.writeDouble(builder, ((Number) value).doubleValue());
        }
        else if (type.getJavaType() == Slice.class) {
            if (value instanceof byte[] bytes) {
                type.writeSlice(builder, wrappedBuffer(bytes));
            }
            else if (type.equals(uuidType)) {
                // UUID uses binary encoding in Trino; java.util.UUID from JDBC must be converted
                type.writeSlice(builder, javaUuidToTrinoUuid((UUID) value));
            }
            else if (type.equals(ipAddressType)) {
                // InetAddress.toString() returns "/ip" prefix; use InetAddresses.toAddrString() to avoid it
                String ipStr;
                if (value instanceof InetAddress inetAddress) {
                    ipStr = InetAddresses.toAddrString(inetAddress);
                }
                else {
                    ipStr = value.toString();
                }
                type.writeSlice(builder, wrappedBuffer(ipAddressStringToBytes(ipStr)));
            }
            else {
                type.writeSlice(builder, utf8Slice(value.toString()));
            }
        }
        else if (type instanceof DecimalType decimalType) {
            // Only LongDecimalType (precision > 18) reaches here; ShortDecimalType has getJavaType() == long.class
            // and is handled by the long.class branch above.
            // UInt64 arrays: ClickHouseObjectValue.asObject() returns raw long[] so elements arrive as boxed Long.
            // Long.toUnsignedString() reinterprets the signed bits as unsigned for UInt64 elements.
            BigDecimal bigDecimal;
            if (value instanceof BigDecimal bigDecimalValue) {
                bigDecimal = bigDecimalValue;
            }
            else if (value instanceof Long longValue) {
                bigDecimal = new BigDecimal(Long.toUnsignedString(longValue));
            }
            else {
                bigDecimal = new BigDecimal(value.toString());
            }
            type.writeObject(builder, Decimals.encodeScaledValue(bigDecimal, decimalType.getScale()));
        }
        else {
            type.writeObject(builder, value);
        }
    }

    private static void checkNestingDepth(int depth)
    {
        if (depth >= MAX_ARRAY_NESTING_DEPTH) {
            throw new TrinoException(NOT_SUPPORTED, "Nesting depth exceeds maximum of %d".formatted(MAX_ARRAY_NESTING_DEPTH));
        }
    }

    // The JDBC driver returns tuples as lists and geo points as double[]
    private static List<?> toFieldValues(Object value)
    {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(java.lang.reflect.Array.get(value, i));
            }
            return values;
        }
        throw new TrinoException(JDBC_ERROR, "Unexpected tuple value from ClickHouse JDBC: " + value.getClass());
    }

    private static void writeTimestampWithTimeZone(TimestampWithTimeZoneType type, BlockBuilder builder, Object value)
    {
        ZonedDateTime dateTime = switch (value) {
            case ZonedDateTime zonedDateTime -> zonedDateTime;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toZonedDateTime();
            // DateTime('UTC') elements are returned without a zone
            case LocalDateTime localDateTime -> localDateTime.atZone(UTC);
            default -> throw new TrinoException(JDBC_ERROR, "Unexpected timestamp with time zone value from ClickHouse JDBC: " + value.getClass());
        };
        Instant instant = dateTime.toInstant();
        int offsetSeconds = dateTime.getOffset().getTotalSeconds();
        TimeZoneKey zoneKey = offsetSeconds % 60 == 0 ? TimeZoneKey.getTimeZoneKeyForOffset(offsetSeconds / 60) : UTC_KEY;
        if (type.isShort()) {
            type.writeLong(builder, packDateTimeWithZone(instant.toEpochMilli(), zoneKey));
        }
        else {
            type.writeObject(builder, LongTimestampWithTimeZone.fromEpochSecondsAndFraction(
                    instant.getEpochSecond(),
                    (long) instant.getNano() * PICOSECONDS_PER_NANOSECOND,
                    zoneKey));
        }
    }

    private static byte[] ipAddressStringToBytes(String ipString)
    {
        byte[] raw = InetAddress.ofLiteral(ipString).getAddress();
        if (raw.length == 4) {
            byte[] bytes16 = new byte[16];
            bytes16[10] = (byte) 0xFF;
            bytes16[11] = (byte) 0xFF;
            arraycopy(raw, 0, bytes16, 12, 4);
            return bytes16;
        }
        return raw;
    }

    private ObjectWriteFunction arrayWriteFunction(Type elementType, String baseElementJdbcTypeName)
    {
        return ObjectWriteFunction.of(Block.class, (statement, index, block) -> {
            Object[] elements = toJdbcObjectArray(elementType, block);
            Array jdbcArray = statement.getConnection().createArrayOf(baseElementJdbcTypeName, elements);
            statement.setArray(index, jdbcArray);
        });
    }

    private Object[] toJdbcObjectArray(Type elementType, Block block)
    {
        int positionCount = block.getPositionCount();
        Object[] values = new Object[positionCount];
        for (int i = 0; i < positionCount; i++) {
            values[i] = toJdbcObjectValue(elementType, block, i);
        }
        return values;
    }

    private Object toJdbcObjectValue(Type type, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }
        if (BOOLEAN.equals(type)) {
            return type.getBoolean(block, position);
        }
        if (TINYINT.equals(type)) {
            return (byte) type.getLong(block, position);
        }
        if (SMALLINT.equals(type)) {
            return Shorts.checkedCast(type.getLong(block, position));
        }
        if (INTEGER.equals(type)) {
            return toIntExact(type.getLong(block, position));
        }
        if (BIGINT.equals(type)) {
            return type.getLong(block, position);
        }
        if (REAL.equals(type)) {
            return intBitsToFloat(toIntExact(type.getLong(block, position)));
        }
        if (DOUBLE.equals(type)) {
            return type.getDouble(block, position);
        }
        if (DATE.equals(type)) {
            return LocalDate.ofEpochDay(type.getLong(block, position));
        }
        if (type instanceof TimestampType timestampType) {
            if (timestampType.isShort()) {
                return fromTrinoTimestamp(type.getLong(block, position));
            }
            return fromLongTrinoTimestamp((LongTimestamp) type.getObject(block, position), timestampType.getPrecision());
        }
        if (type instanceof DecimalType decimalType) {
            if (decimalType.isShort()) {
                BigInteger unscaled = BigInteger.valueOf(type.getLong(block, position));
                return new BigDecimal(unscaled, decimalType.getScale(), new MathContext(decimalType.getPrecision()));
            }
            BigInteger unscaled = ((Int128) type.getObject(block, position)).toBigInteger();
            return new BigDecimal(unscaled, decimalType.getScale(), new MathContext(decimalType.getPrecision()));
        }
        if (type instanceof VarcharType || type instanceof CharType) {
            return ((Slice) type.getObject(block, position)).toStringUtf8();
        }
        if (type instanceof VarbinaryType) {
            return ((Slice) type.getObject(block, position)).getBytes();
        }
        if (type.equals(uuidType)) {
            return trinoUuidToJavaUuid((Slice) type.getObject(block, position));
        }
        if (type.equals(ipAddressType)) {
            // Convert Trino 16-byte IP representation to dotted-decimal / colon-hex string for ClickHouse
            byte[] bytes = ((Slice) type.getObject(block, position)).getBytes();
            try {
                if (bytes.length == 16 && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                    byte[] ipv4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
                    return InetAddresses.toAddrString(InetAddress.getByAddress(ipv4));
                }
                return InetAddresses.toAddrString(InetAddress.getByAddress(bytes));
            }
            catch (UnknownHostException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (type instanceof ArrayType arrayType) {
            return toJdbcObjectArray(arrayType.getElementType(), (Block) type.getObject(block, position));
        }
        if (type instanceof MapType mapType) {
            SqlMap sqlMap = mapType.getObject(block, position);
            Map<Object, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < sqlMap.getSize(); i++) {
                int entry = sqlMap.getRawOffset() + i;
                values.put(
                        toJdbcObjectValue(mapType.getKeyType(), sqlMap.getRawKeyBlock(), entry),
                        toJdbcObjectValue(mapType.getValueType(), sqlMap.getRawValueBlock(), entry));
            }
            return values;
        }
        if (type instanceof RowType rowType) {
            SqlRow sqlRow = rowType.getObject(block, position);
            List<Object> values = new ArrayList<>(rowType.getFields().size());
            for (int field = 0; field < rowType.getFields().size(); field++) {
                values.add(toJdbcObjectValue(rowType.getFields().get(field).getType(), sqlRow.getRawFieldBlock(field), sqlRow.getRawIndex()));
            }
            return values;
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported element type for ClickHouse array write: %s".formatted(type));
    }

    public static boolean supportsPushdown(Variable variable, RewriteContext<ParameterizedExpression> context)
    {
        JdbcTypeHandle typeHandle = ((JdbcColumnHandle) context.getAssignment(variable.getName()))
                .getJdbcTypeHandle();
        String jdbcTypeName = typeHandle.jdbcTypeName()
                .orElseThrow(() -> new TrinoException(JDBC_ERROR, "Type name is missing: " + typeHandle));
        ClickHouseColumn column = ClickHouseColumn.of("", jdbcTypeName);
        ClickHouseDataType columnDataType = column.getDataType();
        return switch (columnDataType) {
            case FixedString, String -> true;
            default -> false;
        };
    }
}
