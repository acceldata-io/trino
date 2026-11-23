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
package io.trino.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.CatalogHandle;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.EntityKindAndName;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.security.PrincipalType;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.type.Type;
import io.trino.sql.tree.GrantorSpecification;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.PrincipalSpecification;
import io.trino.sql.tree.QualifiedName;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.SystemSessionProperties.isLegacyCatalogRoles;
import static io.trino.spi.StandardErrorCode.CATALOG_NOT_FOUND;
import static io.trino.spi.StandardErrorCode.MISSING_CATALOG_NAME;
import static io.trino.spi.StandardErrorCode.MISSING_SCHEMA_NAME;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.StandardErrorCode.ROLE_NOT_FOUND;
import static io.trino.spi.StandardErrorCode.SYNTAX_ERROR;
import static io.trino.spi.connector.CatalogSchemaName.caseSensitiveCatalogSchemaName;
import static io.trino.spi.connector.ConnectorCapabilities.CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES;
import static io.trino.spi.security.PrincipalType.ROLE;
import static io.trino.spi.security.PrincipalType.USER;
import static io.trino.sql.analyzer.SemanticExceptions.semanticException;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public final class MetadataUtil
{
    private MetadataUtil() {}

    public static String checkCatalogName(String catalogName)
    {
        return checkLowerCase(catalogName, "catalogName");
    }

    public static String checkSchemaName(String schemaName)
    {
        return checkLowerCase(schemaName, "schemaName");
    }

    public static String checkTableName(String tableName)
    {
        return checkLowerCase(tableName, "tableName");
    }

    public static String checkLowerCase(String value, String name)
    {
        if (value == null) {
            throw new NullPointerException(format("%s is null", name));
        }
        checkArgument(value.equals(value.toLowerCase(ENGLISH)), "%s is not lowercase: %s", name, value);
        return value;
    }

    public static ColumnMetadata findColumnMetadata(ConnectorTableMetadata tableMetadata, String columnName)
    {
        for (ColumnMetadata columnMetadata : tableMetadata.getColumns()) {
            if (columnName.equals(columnMetadata.getName())) {
                return columnMetadata;
            }
        }
        return null;
    }

    public static CatalogHandle getRequiredCatalogHandle(Metadata metadata, Session session, Node node, String catalogName)
    {
        return metadata.getCatalogHandle(session, catalogName)
                .orElseThrow(() -> semanticException(CATALOG_NOT_FOUND, node, "Catalog '%s' not found", catalogName));
    }

    public static CatalogSchemaName createCatalogSchemaName(Session session, Node node, Optional<QualifiedName> schema)
    {
        String catalogName = session.getCatalog().orElse(null);
        String schemaName = session.getSchema().orElse(null);

        if (schema.isPresent()) {
            List<String> parts = schema.get().getParts();
            if (parts.size() > 2) {
                throw semanticException(SYNTAX_ERROR, node, "Too many parts in schema name: %s", schema.get());
            }
            if (parts.size() == 2) {
                catalogName = parts.get(0);
            }
            schemaName = schema.get().getSuffix();
        }

        if (catalogName == null) {
            throw semanticException(MISSING_CATALOG_NAME, node, "Catalog must be specified when session catalog is not set");
        }
        if (schemaName == null) {
            throw semanticException(MISSING_SCHEMA_NAME, node, "Schema must be specified when session schema is not set");
        }

        return new CatalogSchemaName(catalogName, schemaName);
    }

    /**
     * Like {@link #createCatalogSchemaName(Session, Node, Optional)}, but keeps the case of a quoted
     * schema name when the catalog has case-sensitive schema and table names.
     */
    public static CatalogSchemaName createCatalogSchemaName(Session session, Node node, Optional<QualifiedName> schema, Metadata metadata)
    {
        CatalogSchemaName schemaName = createCatalogSchemaName(session, node, schema);
        if (schema.isEmpty()) {
            return schemaName;
        }
        Identifier schemaIdentifier = schema.get().getOriginalParts().getLast();
        if (!keepsCase(schemaIdentifier) || !hasCaseSensitiveNames(metadata, session, schemaName.getCatalogName())) {
            return schemaName;
        }
        return caseSensitiveCatalogSchemaName(schemaName.getCatalogName(), schemaIdentifier.getValue());
    }

    public static QualifiedObjectName createQualifiedObjectName(Session session, Node node, QualifiedName name)
    {
        requireNonNull(session, "session is null");
        requireNonNull(name, "name is null");
        if (name.getParts().size() > 3) {
            throw new TrinoException(SYNTAX_ERROR, format("Too many dots in table name: %s", name));
        }

        List<String> parts = name.getParts().reversed();
        String objectName = parts.get(0);
        String schemaName = (parts.size() > 1) ? parts.get(1) : session.getSchema().orElseThrow(() ->
                semanticException(MISSING_SCHEMA_NAME, node, "Schema must be specified when session schema is not set"));
        String catalogName = (parts.size() > 2) ? parts.get(2) : session.getCatalog().orElseThrow(() ->
                semanticException(MISSING_CATALOG_NAME, node, "Catalog must be specified when session catalog is not set"));

        return new QualifiedObjectName(catalogName, schemaName, objectName);
    }

    /**
     * Like {@link #createQualifiedObjectName(Session, Node, QualifiedName)}, but keeps the case of quoted
     * schema and table names when the catalog has case-sensitive schema and table names.
     */
    public static QualifiedObjectName createQualifiedObjectName(Session session, Node node, QualifiedName name, Metadata metadata)
    {
        return keepQuotedNameCase(session, createQualifiedObjectName(session, node, name), name, metadata);
    }

    /**
     * Returns {@code objectName}, which was resolved from {@code name}, with the case of the quoted schema
     * and table parts of {@code name} kept when the catalog has case-sensitive schema and table names.
     */
    public static QualifiedObjectName keepQuotedNameCase(Session session, QualifiedObjectName objectName, QualifiedName name, Metadata metadata)
    {
        List<Identifier> parts = name.getOriginalParts().reversed();
        boolean keepTableCase = keepsCase(parts.get(0));
        boolean keepSchemaCase = parts.size() > 1 && keepsCase(parts.get(1));
        if ((!keepTableCase && !keepSchemaCase) || !hasCaseSensitiveNames(metadata, session, objectName.catalogName())) {
            return objectName;
        }
        return new QualifiedObjectName(
                objectName.catalogName(),
                keepSchemaCase ? parts.get(1).getValue() : objectName.schemaName(),
                keepTableCase ? parts.get(0).getValue() : objectName.objectName());
    }

    public static boolean hasCaseSensitiveNames(Metadata metadata, Session session, String catalogName)
    {
        return metadata.getCatalogHandle(session, catalogName)
                .map(catalogHandle -> metadata.getConnectorCapabilities(session, catalogHandle).contains(CASE_SENSITIVE_SCHEMA_AND_TABLE_NAMES))
                .orElse(false);
    }

    /**
     * Returns the schema or table name for {@code identifier}: lowercased, except that a quoted name keeps
     * its case when {@code caseSensitiveNames} is set.
     */
    public static String canonicalName(Identifier identifier, boolean caseSensitiveNames)
    {
        if (caseSensitiveNames && keepsCase(identifier)) {
            return identifier.getValue();
        }
        return identifier.getValue().toLowerCase(ENGLISH);
    }

    private static boolean keepsCase(Identifier identifier)
    {
        return identifier.isDelimited() && !identifier.getValue().equals(identifier.getValue().toLowerCase(ENGLISH));
    }

    public static EntityKindAndName createEntityKindAndName(String entityKind, QualifiedName name)
    {
        return new EntityKindAndName(entityKind, name.getParts());
    }

    public static TrinoPrincipal createPrincipal(Session session, GrantorSpecification specification)
    {
        GrantorSpecification.Type type = specification.type();
        return switch (type) {
            case PRINCIPAL -> createPrincipal(specification.principal().get());
            case CURRENT_USER -> new TrinoPrincipal(USER, session.getIdentity().getUser());
            // TODO: will be implemented once the "SET ROLE" statement is introduced
            case CURRENT_ROLE -> throw new UnsupportedOperationException("CURRENT_ROLE is not yet supported");
        };
    }

    public static TrinoPrincipal createPrincipal(PrincipalSpecification specification)
    {
        PrincipalSpecification.Type type = specification.type();
        return switch (type) {
            case UNSPECIFIED, USER -> new TrinoPrincipal(USER, specification.name().getValue());
            case ROLE -> new TrinoPrincipal(ROLE, specification.name().getValue());
        };
    }

    public static PrincipalSpecification createPrincipal(TrinoPrincipal principal)
    {
        PrincipalType type = principal.getType();
        return switch (type) {
            case USER -> new PrincipalSpecification(PrincipalSpecification.Type.USER, new Identifier(principal.getName()));
            case ROLE -> new PrincipalSpecification(PrincipalSpecification.Type.ROLE, new Identifier(principal.getName()));
        };
    }

    public static boolean tableExists(Metadata metadata, Session session, String table)
    {
        if (session.getCatalog().isEmpty() || session.getSchema().isEmpty()) {
            return false;
        }
        QualifiedObjectName name = new QualifiedObjectName(session.getCatalog().get(), session.getSchema().get(), table);
        return metadata.getTableHandle(session, name).isPresent();
    }

    public static void checkRoleExists(Session session, Node node, Metadata metadata, TrinoPrincipal principal, Optional<String> catalog)
    {
        if (principal.getType() == ROLE) {
            checkRoleExists(session, node, metadata, principal.getName(), catalog);
        }
    }

    public static void checkRoleExists(Session session, Node node, Metadata metadata, String role, Optional<String> catalog)
    {
        if (!metadata.roleExists(session, role, catalog)) {
            throw semanticException(ROLE_NOT_FOUND, node, "Role '%s' does not exist%s", role, catalog.map(c -> format(" in catalog '%s'", c)).orElse(""));
        }
    }

    public static Optional<String> processRoleCommandCatalog(Metadata metadata, Session session, Node node, Optional<String> catalog)
    {
        boolean legacyCatalogRoles = isLegacyCatalogRoles(session);
        // old role commands use only supported catalog roles and used session catalog as the default
        if (catalog.isEmpty() && legacyCatalogRoles) {
            catalog = session.getCatalog();
            if (catalog.isEmpty()) {
                throw semanticException(MISSING_CATALOG_NAME, node, "Session catalog must be set");
            }
        }
        catalog.ifPresent(catalogName -> getRequiredCatalogHandle(metadata, session, node, catalogName));

        if (catalog.isPresent() && !metadata.isCatalogManagedSecurity(session, catalog.get())) {
            throw semanticException(NOT_SUPPORTED, node, "Catalog '%s' does not support role management", catalog.get());
        }

        return catalog;
    }

    public static class TableMetadataBuilder
    {
        public static TableMetadataBuilder tableMetadataBuilder(SchemaTableName tableName)
        {
            return new TableMetadataBuilder(tableName);
        }

        private final SchemaTableName tableName;
        private final ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        private final ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();
        private final Optional<String> comment;

        private TableMetadataBuilder(SchemaTableName tableName)
        {
            this(tableName, Optional.empty());
        }

        private TableMetadataBuilder(SchemaTableName tableName, Optional<String> comment)
        {
            this.tableName = tableName;
            this.comment = comment;
        }

        public TableMetadataBuilder column(String columnName, Type type)
        {
            columns.add(new ColumnMetadata(columnName, type));
            return this;
        }

        public TableMetadataBuilder hiddenColumn(String columnName, Type type)
        {
            columns.add(ColumnMetadata.builder()
                    .setName(columnName)
                    .setType(type)
                    .setHidden(true)
                    .build());
            return this;
        }

        public TableMetadataBuilder property(String name, Object value)
        {
            properties.put(name, value);
            return this;
        }

        public ConnectorTableMetadata build()
        {
            return new ConnectorTableMetadata(tableName, columns.build(), properties.buildOrThrow(), comment);
        }
    }
}
