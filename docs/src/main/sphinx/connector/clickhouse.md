---
myst:
  substitutions:
    default_domain_compaction_threshold: "`1000`"
---

# ClickHouse connector

```{raw} html
<img src="../_static/img/clickhouse.png" class="connector-logo">
```

The ClickHouse connector allows querying tables in an external
[ClickHouse](https://clickhouse.com/) server. This can be used to
query data in the databases on that server, or combine it with other data
from different catalogs accessing ClickHouse or any other supported data source.

## Requirements

To connect to a ClickHouse server, you need:

- ClickHouse (version 25.3 or higher) or Altinity (version 23.3 or higher).
- Network access from the Trino coordinator and workers to the ClickHouse
  server. Port 8123 is the default port.

## Configuration

The connector can query a ClickHouse server. Create a catalog properties file
that specifies the ClickHouse connector by setting the `connector.name` to
`clickhouse`.

For example, create the file `etc/catalog/example.properties`. Replace the
connection properties as appropriate for your setup:

```none
connector.name=clickhouse
connection-url=jdbc:clickhouse://host1:8123/
connection-user=exampleuser
connection-password=examplepassword
```

The `connection-url` defines the connection information and parameters to pass
to the ClickHouse JDBC driver. The supported parameters for the URL are
available in the [ClickHouse JDBC driver configuration](https://clickhouse.com/docs/en/integrations/java#configuration).

The `connection-user` and `connection-password` are typically required and
determine the user credentials for the connection, often a service user. You can
use {doc}`secrets </security/secrets>` to avoid actual values in the catalog
properties files.

(clickhouse-tls)=
### Connection security

If you have TLS configured with a globally-trusted certificate installed on your
data source, you can enable TLS between your cluster and the data
source by appending a parameter to the JDBC connection string set in the
`connection-url` catalog configuration property.

For example, with version 2.6.4 of the ClickHouse JDBC driver, enable TLS by
appending the `ssl=true` parameter to the `connection-url` configuration
property:

```properties
connection-url=jdbc:clickhouse://host1:8443/?ssl=true
```

For more information on TLS configuration options, see the [Clickhouse JDBC
driver documentation](https://clickhouse.com/docs/en/interfaces/jdbc/)

```{include} jdbc-authentication.fragment
```

### Multiple ClickHouse servers

If you have multiple ClickHouse servers you need to configure one
catalog for each server. To add another catalog:

- Add another properties file to `etc/catalog`
- Save it with a different name that ends in `.properties`

For example, if you name the property file `sales.properties`, Trino uses the
configured connector to create a catalog named `sales`.

```{include} jdbc-common-configurations.fragment
```

```{include} query-comment-format.fragment
```

```{include} jdbc-domain-compaction-threshold.fragment
```

```{include} jdbc-case-insensitive-matching.fragment
```

### Case-sensitive schema and table names

ClickHouse database and table names are case-sensitive, so one database can
contain both `customers` and `Customers`. Set `case-sensitive-name-matching` to
`true` in the catalog properties file to use such names in Trino:

```properties
case-sensitive-name-matching=true
```

With this property:

- A quoted name keeps its case. `"Customers"` refers to the ClickHouse table
  `Customers`, while `customers`, `"customers"`, and the unquoted `Customers`
  refer to `customers`. Database names work the same way.
- `SHOW SCHEMAS`, `SHOW TABLES`, `information_schema`, and the metadata of the
  JDBC driver list schema and table names with their exact case.
- Statements such as `DESCRIBE`, `SHOW CREATE TABLE`, `INSERT`,
  `CREATE TABLE`, `ALTER TABLE`, and `DROP TABLE` act on the table with the
  exact quoted name.

The following limitations apply:

- Column names are always lowercase.
- `USE` and the default schema of a client session are lowercase. Qualify table
  names with a schema name that contains uppercase letters instead.
- The property cannot be combined with `case-insensitive-name-matching` or
  `case-insensitive-name-matching.config-file`.
- Access control receives the exact names. Patterns in file-based access control
  are case-sensitive regular expressions. With Apache Ranger, a schema or table
  name that contains uppercase letters is sent with a leading double quote, for
  example `"Customers`, so use that form in policy resources. To tell apart two
  names that both contain uppercase letters, such as `Customers` and
  `CUSTOMERS`, set `quotedCaseSensitive` for the schema and table resources of
  the Trino service definition in Ranger.

## Querying ClickHouse

The ClickHouse connector provides a schema for every ClickHouse *database*.
Run `SHOW SCHEMAS` to see the available ClickHouse databases:

```
SHOW SCHEMAS FROM example;
```

If you have a ClickHouse database named `web`, run `SHOW TABLES` to view the
tables in this database:

```
SHOW TABLES FROM example.web;
```

Run `DESCRIBE` or `SHOW COLUMNS` to list the columns in the `clicks` table
in the `web` databases:

```
DESCRIBE example.web.clicks;
SHOW COLUMNS FROM example.web.clicks;
```

Run `SELECT` to access the `clicks` table in the `web` database:

```
SELECT * FROM example.web.clicks;
```

:::{note}
If you used a different name for your catalog properties file, use
that catalog name instead of `example` in the above examples.
:::

## Table properties

Table property usage example:

```
CREATE TABLE default.trino_ck (
  id int NOT NULL,
  birthday DATE NOT NULL,
  name VARCHAR,
  age BIGINT,
  logdate DATE NOT NULL
)
WITH (
  engine = 'MergeTree',
  order_by = ARRAY['id', 'birthday'],
  partition_by = ARRAY['toYYYYMM(logdate)'],
  primary_key = ARRAY['id'],
  sample_by = 'id'
);
```

The following are supported ClickHouse table properties from [https://clickhouse.tech/docs/en/engines/table-engines/mergetree-family/mergetree/](https://clickhouse.tech/docs/en/engines/table-engines/mergetree-family/mergetree/)

| Property name  | Default value | Description                                                                                                                            |
| -------------- | ------------- |----------------------------------------------------------------------------------------------------------------------------------------|
| `engine`       | `Log`         | Name and parameters of the engine.                                                                                                     |
| `order_by`     | (none)        | Array of columns or expressions to concatenate to create the sorting key. `tuple()` is used by default if `order_by is` not specified. |
| `partition_by` | (none)        | Array of columns or expressions to use as nested partition keys. Optional.                                                             |
| `primary_key`  | (none)        | Array of columns or expressions to concatenate to create the primary key. Optional.                                                    |
| `sample_by`    | (none)        | An expression to use for [sampling](https://clickhouse.tech/docs/en/sql-reference/statements/select/sample/). Optional.                |

Currently the connector only supports `Log` and `MergeTree` table engines
in create table statement. `ReplicatedMergeTree` engine is not yet supported.

(clickhouse-type-mapping)=
## Type mapping

Because Trino and ClickHouse each support types that the other does not, this
connector {ref}`modifies some types <type-mapping-overview>` when reading or
writing data. Data types may not map the same way in both directions between
Trino and the data source. Refer to the following sections for type mapping in
each direction.

### ClickHouse type to Trino type mapping

The connector maps ClickHouse types to the corresponding Trino types according
to the following table:

:::{list-table} ClickHouse type to Trino type mapping
:widths: 30, 25, 50
:header-rows: 1

* - ClickHouse type
  - Trino type
  - Notes
* - `Bool`
  - `BOOLEAN`
  -
* - `Int8`
  - `TINYINT`
  - `TINYINT` and `INT1` are aliases of `Int8`
* - `Int16`
  - `SMALLINT`
  -  `SMALLINT` and `INT2` are aliases of `Int16`
* - `Int32`
  - `INTEGER`
  - `INT`, `INT4`, and `INTEGER` are aliases of `Int32`
* - `Int64`
  - `BIGINT`
  - `BIGINT` is an alias of `Int64`
* - `UInt8`
  - `SMALLINT`
  -
* - `UInt16`
  - `INTEGER`
  -
* - `UInt32`
  - `BIGINT`
  -
* - `UInt64`
  - `DECIMAL(20,0)`
  -
* - `Float32`
  - `REAL`
  - `FLOAT` is an alias of `Float32`
* - `Float64`
  - `DOUBLE`
  - `DOUBLE` is an alias of `Float64`
* - `Decimal`
  - `DECIMAL` or `NUMBER`
  - Maps to Trino `DECIMAL` when `p ≤ 38`. Otherwise, maps to `NUMBER`.
* - `FixedString`
  - `VARBINARY`
  - Enabling `clickhouse.map-string-as-varchar` config property changes the
    mapping to `VARCHAR`. The bytes are read unchanged, including bytes that
    are not valid UTF-8. Query results show such bytes as the replacement
    character U+FFFD, and {func}`to_utf8` returns the original bytes.
* - `String`
  - `VARBINARY`
  - Enabling `clickhouse.map-string-as-varchar` config property changes the
    mapping to `VARCHAR`. The bytes are read unchanged, including bytes that
    are not valid UTF-8. Query results show such bytes as the replacement
    character U+FFFD, and {func}`to_utf8` returns the original bytes.
* - `Date`
  - `DATE`
  -
* - `DateTime[(timezone)]`
  - `TIMESTAMP(0) [WITH TIME ZONE]`
  -
* - `DateTime64(p)`
  - `TIMESTAMP(p)`
  - Precision `p` from 0 to 9 is read and written in full.
* - `DateTime64(p, timezone)`
  - `TIMESTAMP(p) WITH TIME ZONE`
  - Values are returned with the offset of the column time zone. Values are
    transferred in UTC, so times repeated when clocks are set back keep their
    instant.
* - `IPv4`
  - `IPADDRESS`
  -
* - `IPv6`
  - `IPADDRESS`
  -
* - `Enum8`
  - `VARCHAR`
  -
* - `Enum16`
  - `VARCHAR`
  -
* - `UUID`
  - `UUID`
  -
* - `Array(T)`
  - `array(T)`
  - Element type `T` is mapped according to the same type mapping rules.
    Nested arrays and nullable elements via `Array(Nullable(T))` are supported.
* - `Map(K, V)`
  - `map(K, V)`
  - Key and value types are mapped according to the same type mapping rules.
    For a duplicate key, the last value is returned.
* - `Tuple(T1, T2, ...)`
  - `row(T1, T2, ...)`
  - Field names of a named tuple are kept.
* - `Point`
  - `row(x double, y double)`
  -
* - `Ring`, `LineString`
  - `array(row(x double, y double))`
  -
* - `Polygon`, `MultiLineString`
  - `array(array(row(x double, y double)))`
  -
* - `MultiPolygon`
  - `array(array(array(row(x double, y double))))`
  -
:::

No other types are supported.

Values can be inserted into existing `Map`, `Tuple`, and geo columns. Strings
nested in `Map` and `Tuple` values are decoded as UTF-8 by the ClickHouse JDBC
driver.

### Trino type to ClickHouse type mapping

The connector maps Trino types to the corresponding ClickHouse types according
to the following table:

:::{list-table} Trino type to ClickHouse type mapping
:widths: 30, 25, 50
:header-rows: 1

* - Trino type
  - ClickHouse type
  - Notes
* - `BOOLEAN`
  - `Bool`
  -
* - `TINYINT`
  - `Int8`
  - `TINYINT` and `INT1` are aliases of `Int8`
* - `SMALLINT`
  - `Int16`
  -  `SMALLINT` and `INT2` are aliases of `Int16`
* - `INTEGER`
  - `Int32`
  - `INT`, `INT4`, and `INTEGER` are aliases of `Int32`
* - `BIGINT`
  - `Int64`
  - `BIGINT` is an alias of `Int64`
* - `REAL`
  - `Float32`
  - `FLOAT` is an alias of `Float32`
* - `DOUBLE`
  - `Float64`
  - `DOUBLE` is an alias of `Float64`
* - `DECIMAL(p,s)`
  - `Decimal(p,s)`
  -
* - `VARCHAR`
  - `String`
  -
* - `CHAR`
  - `String`
  -
* - `VARBINARY`
  - `String`
  - Enabling `clickhouse.map-string-as-varchar` config property changes the
    mapping to `VARCHAR`
* - `DATE`
  - `Date`
  -
* - `TIMESTAMP(0)`
  - `DateTime`
  -
* - `UUID`
  - `UUID`
  -
* - `array(T)`
  - `Array(Nullable(T))`
  - Element type `T` is mapped according to the same type mapping rules.
    For nested arrays, only the innermost scalar element is wrapped in `Nullable`;
    intermediate `Array` levels are not wrapped because ClickHouse does not support `Nullable(Array(T))`.
:::

No other types are supported.

```{include} jdbc-type-mapping.fragment
```

(clickhouse-sql-support)=
## SQL support

The connector provides read and write access to data and metadata in a
ClickHouse catalog. In addition to the [globally
available](sql-globally-available) and [read operation](sql-read-operations)
statements, the connector supports the following features:

- [](/sql/insert), see also [](clickhouse-insert)
- [](/sql/truncate)
- [](sql-schema-table-management), see also:
  - [](clickhouse-alter-table)
- [](clickhouse-procedures)
- [](clickhouse-table-functions)

(clickhouse-insert)=
```{include} non-transactional-insert.fragment
```

Inserts into existing tables read their data from the ClickHouse `input()`
table function, so the ClickHouse user does not need the `SELECT` privilege on
the table. It needs `INSERT` on the table and `CREATE TEMPORARY TABLE`, which
ClickHouse requires for table functions. With the default transactional inserts,
the user also needs the privileges to create, read and drop the temporary table.

(clickhouse-alter-table)=
```{include} alter-schema-limitation.fragment
```

(clickhouse-procedures)=
### Procedures

```{include} jdbc-procedures-flush.fragment
```
```{include} procedures-execute.fragment
```

(clickhouse-table-functions)=
### Table functions

The connector provides specific {doc}`table functions </functions/table>` to
access ClickHouse.

(clickhouse-query-function)=
#### `query(varchar) -> table`

The `query` function allows you to query the underlying database directly. It
requires syntax native to ClickHouse, because the full query is pushed down and
processed in ClickHouse. This can be useful for accessing native features which
are not available in Trino or for improving query performance in situations
where running a query natively may be faster.

```{include} query-passthrough-warning.fragment
```

As a simple example, query the `example` catalog and select an entire table:

```
SELECT
  *
FROM
  TABLE(
    example.system.query(
      query => 'SELECT
        *
      FROM
        tpch.nation'
    )
  );
```

```{include} query-table-function-ordering.fragment
```

## Performance

The connector includes a number of performance improvements, detailed in the
following sections.

(clickhouse-pushdown)=
### Pushdown

The connector supports pushdown for a number of operations:

- {ref}`limit-pushdown`

{ref}`Aggregate pushdown <aggregation-pushdown>` for the following functions:

- {func}`avg`
- {func}`count`
- {func}`max`
- {func}`min`
- {func}`sum`


```{include} pushdown-correctness-behavior.fragment
```

```{include} no-inequality-pushdown-text-type.fragment
```
