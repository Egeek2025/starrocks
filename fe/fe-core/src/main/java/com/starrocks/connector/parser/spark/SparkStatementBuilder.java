// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.parser.spark;

import com.google.common.collect.Lists;
import com.starrocks.qe.SessionVariable;
import com.starrocks.qe.SparkConfMapper;
import com.starrocks.sql.ast.AddColumnClause;
import com.starrocks.sql.ast.AddColumnsClause;
import com.starrocks.sql.ast.AlterClause;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.sql.ast.AnalyzeBasicDesc;
import com.starrocks.sql.ast.AnalyzeStmt;
import com.starrocks.sql.ast.ColWithComment;
import com.starrocks.sql.ast.ColumnDef;
import com.starrocks.sql.ast.ColumnPosition;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.CreateTableAsSelectStmt;
import com.starrocks.sql.ast.CreateTableLikeStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.CreateViewStmt;
import com.starrocks.sql.ast.DescribeStmt;
import com.starrocks.sql.ast.DistributionDesc;
import com.starrocks.sql.ast.DropDbStmt;
import com.starrocks.sql.ast.DropTableStmt;
import com.starrocks.sql.ast.HashDistributionDesc;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.ListPartitionDesc;
import com.starrocks.sql.ast.ParseNode;
import com.starrocks.sql.ast.PartitionDesc;
import com.starrocks.sql.ast.PartitionRef;
import com.starrocks.sql.ast.QualifiedName;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.RefreshTableStmt;
import com.starrocks.sql.ast.SetCatalogStmt;
import com.starrocks.sql.ast.SetListItem;
import com.starrocks.sql.ast.SetType;
import com.starrocks.sql.ast.ShowColumnStmt;
import com.starrocks.sql.ast.ShowCreateTableStmt;
import com.starrocks.sql.ast.ShowDbStmt;
import com.starrocks.sql.ast.ShowTableStmt;
import com.starrocks.sql.ast.ShowVariablesStmt;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.TableRef;
import com.starrocks.sql.ast.TableRenameClause;
import com.starrocks.sql.ast.TruncateTableStmt;
import com.starrocks.sql.ast.UseDbStmt;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.LiteralExpr;
import com.starrocks.sql.ast.expression.NullLiteral;
import com.starrocks.sql.ast.expression.SlotRef;
import com.starrocks.sql.ast.expression.StringLiteral;
import com.starrocks.sql.ast.expression.TypeDef;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParserBaseVisitor;
import com.starrocks.type.Type;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.starrocks.connector.parser.spark.SparkAstBuilder.unsupported;
import static com.starrocks.connector.parser.spark.SparkParserUtils.pos;

/**
 * Translates Spark statements other than queries into StarRocks statements: INSERT, CREATE/DROP/ALTER TABLE and
 * VIEW, CTAS, session commands (SET, USE, CACHE, ...), and SHOW / DESCRIBE. A rule is picked up by
 * {@link SparkAstBuilder} through its public {@code visitXxx} method, anything not declared here is not supported
 * and falls back to the StarRocks parser.
 *
 * <p>Translation happens while parsing, so the builder can only look at the session as it is at that time. Decisions
 * that depend on the catalog of a table (native or external) are made on a best-effort basis, see
 * {@link SparkCatalogNames}.
 *
 * <p>Known gaps: temporary views are not supported (a TODO in {@link #visitCreateView}); SHOW and DESCRIBE return
 * the StarRocks result set shape, not the column names of Spark.
 */
public class SparkStatementBuilder extends SparkSqlBaseParserBaseVisitor<ParseNode> {
    // file formats a Hive or Iceberg catalog can create
    private static final Set<String> FILE_FORMATS = Set.of("parquet", "orc", "avro", "textfile");
    // providers a native table accepts and ignores: its storage is always the StarRocks one
    private static final Set<String> NATIVE_IGNORED_PROVIDERS = Set.of("parquet", "orc", "avro", "hive");
    // the key of a SET statement that is a plain variable or configuration name
    private static final Pattern CONF_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]*");
    // the first word of SET statements that are StarRocks (MySQL) syntax and no configuration
    private static final Set<String> NON_CONF_SET_WORDS = Set.of("password", "names", "character", "role", "property",
            "global", "session", "local", "persist", "transaction");
    // a value that cannot be an expression
    private static final Pattern PLAIN_VALUE = Pattern.compile("[A-Za-z0-9_.:,/\\-]*");

    private final SparkAstBuilder root;
    private final SparkCatalogNames catalogs;

    public SparkStatementBuilder(SparkAstBuilder root, SessionVariable sessionVariable) {
        this.root = root;
        this.catalogs = new SparkCatalogNames(sessionVariable);
    }

    // ------------------------------------------------ INSERT -----------------------------------------------------

    @Override
    public ParseNode visitDmlStatement(SparkSqlBaseParser.DmlStatementContext ctx) {
        if (ctx.ctes() != null) {
            throw unsupported("WITH clause in front of a DML statement");
        }
        return root.visit(ctx.dmlStatementNoWith());
    }

    @Override
    public ParseNode visitSingleInsertQuery(SparkSqlBaseParser.SingleInsertQueryContext ctx) {
        QueryStatement query = new QueryStatement(root.visit(ctx.query(), QueryRelation.class));
        SparkSqlBaseParser.InsertIntoContext insert = ctx.insertInto();
        if (insert instanceof SparkSqlBaseParser.InsertOverwriteTableContext) {
            SparkSqlBaseParser.InsertOverwriteTableContext overwrite =
                    (SparkSqlBaseParser.InsertOverwriteTableContext) insert;
            return buildInsert(ctx, overwrite.identifierReference(), overwrite.partitionSpec(),
                    overwrite.IF() != null, overwrite.identifierList(), overwrite.NAME() != null, true, query);
        } else if (insert instanceof SparkSqlBaseParser.InsertIntoTableContext) {
            SparkSqlBaseParser.InsertIntoTableContext into = (SparkSqlBaseParser.InsertIntoTableContext) insert;
            return buildInsert(ctx, into.identifierReference(), into.partitionSpec(), into.IF() != null,
                    into.identifierList(), into.NAME() != null, false, query);
        }
        // INSERT INTO ... REPLACE WHERE, INSERT OVERWRITE DIRECTORY
        throw unsupported(insert);
    }

    private InsertStmt buildInsert(ParserRuleContext ctx, SparkSqlBaseParser.IdentifierReferenceContext target,
                                   SparkSqlBaseParser.PartitionSpecContext spec, boolean ifNotExists,
                                   SparkSqlBaseParser.IdentifierListContext columns, boolean byName,
                                   boolean overwrite, QueryStatement query) {
        if (ifNotExists) {
            throw unsupported("INSERT ... PARTITION ... IF NOT EXISTS");
        }
        NodePosition pos = pos(ctx);
        PartitionRef partitionRef = null;
        List<String> staticColumns = new ArrayList<>();
        if (spec != null) {
            List<String> names = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            int dynamic = 0;
            for (SparkSqlBaseParser.PartitionValContext val : spec.partitionVal()) {
                String name = SparkParserUtils.identifier(val.identifier());
                if (val.constant() != null) {
                    names.add(name);
                    values.add(root.visit(val.constant(), Expr.class));
                } else if (val.DEFAULT() != null) {
                    throw unsupported("PARTITION (" + name + " = DEFAULT)");
                } else {
                    dynamic++;
                }
            }
            if (!names.isEmpty() && dynamic > 0) {
                // StarRocks takes either every partition column with a value, or none of them
                throw unsupported("INSERT with static and dynamic partition columns mixed in PARTITION (...), "
                        + "give a value for every partition column or for none");
            }
            // a partition spec with all columns dynamic is the same as no spec: the partition columns are the last
            // columns of the query, in the order of the table
            if (!names.isEmpty()) {
                partitionRef = new PartitionRef(null, false, names, values, pos(spec));
                staticColumns.addAll(names);
            }
        }
        List<String> targetColumns = null;
        if (columns != null) {
            // StarRocks matches target columns in lower case
            targetColumns = new ArrayList<>();
            for (String column : SparkParserUtils.identifierSeq(columns)) {
                targetColumns.add(column.toLowerCase(Locale.ROOT));
            }
            // Spark leaves the static partition columns out of the column list, StarRocks wants all of them
            for (String column : staticColumns) {
                if (!targetColumns.contains(column.toLowerCase(Locale.ROOT))) {
                    targetColumns.add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
        TableRef tableRef = new TableRef(qualifiedName(target), partitionRef, pos);
        InsertStmt stmt = new InsertStmt(tableRef, partitionRef, null, targetColumns, query, overwrite,
                new HashMap<>(), pos);
        if (byName) {
            stmt.setColumnMatchPolicy(InsertStmt.ColumnMatchPolicy.NAME);
        }
        // TODO(spark): spark_partition_overwrite_mode. Spark's default (static) overwrites the whole table for a
        //  dynamic partition INSERT OVERWRITE, StarRocks overwrites the partitions that are written.
        return stmt;
    }

    // ------------------------------------------------- tables ----------------------------------------------------

    @Override
    public ParseNode visitCreateTable(SparkSqlBaseParser.CreateTableContext ctx) {
        SparkSqlBaseParser.CreateTableHeaderContext header = ctx.createTableHeader();
        if (header.TEMPORARY() != null) {
            throw unsupported("CREATE TEMPORARY TABLE");
        }
        NodePosition pos = pos(ctx);
        QualifiedName name = qualifiedName(header.identifierReference());
        TableRef tableRef = new TableRef(name, null, pos(header.identifierReference()));
        String catalogType = SparkCatalogNames.externalCatalogType(catalogs.catalogOf(name.getParts()));
        boolean external = catalogType != null;
        boolean isCtas = ctx.query() != null;

        SparkSqlBaseParser.CreateTableClausesContext clauses = ctx.createTableClauses();
        if (clauses.options != null) {
            throw unsupported("CREATE TABLE ... OPTIONS");
        }
        if (!clauses.skewSpec().isEmpty()) {
            throw unsupported("CREATE TABLE ... SKEWED BY");
        }
        if (!clauses.rowFormat().isEmpty()) {
            throw unsupported("CREATE TABLE ... ROW FORMAT");
        }
        String provider = ctx.tableProvider() == null ? null
                : String.join(".", SparkParserUtils.multipartIdentifier(ctx.tableProvider().multipartIdentifier()))
                .toLowerCase(Locale.ROOT);
        String storedAs = storedAs(clauses.createFileFormat());
        Map<String, String> properties = new LinkedHashMap<>(properties(clauses.propertyList()));
        String comment = comment(clauses.commentSpec());

        if (header.EXTERNAL() != null && !external) {
            throw unsupported("CREATE EXTERNAL TABLE in the StarRocks internal catalog");
        }
        // storage: only an external catalog has file formats and locations
        String fileFormat = fileFormat(provider, storedAs, external);
        if (external) {
            if (fileFormat != null) {
                properties.put("file_format", fileFormat);
            }
            if (!clauses.locationSpec().isEmpty()) {
                String location = SparkParserUtils.stringLit(lastOf(clauses.locationSpec()).stringLit());
                properties.put(header.EXTERNAL() != null && "hive".equals(catalogType)
                        ? "external_location" : "location", location);
            } else if (header.EXTERNAL() != null) {
                throw unsupported("CREATE EXTERNAL TABLE without LOCATION");
            }
        } else if (!clauses.locationSpec().isEmpty()) {
            throw unsupported("CREATE TABLE ... LOCATION for a StarRocks native table");
        }
        DistributionDesc distribution = null;
        if (!clauses.bucketSpec().isEmpty()) {
            if (external) {
                throw unsupported("CREATE TABLE ... CLUSTERED BY for an external catalog");
            }
            distribution = distribution(lastOf(clauses.bucketSpec()));
        }

        // partition columns: identity columns of the table, or new columns in the Hive style
        List<String> partitionColumns = new ArrayList<>();
        List<ColumnDef> hiveStyleColumns = new ArrayList<>();
        if (clauses.partitioning != null) {
            for (SparkSqlBaseParser.PartitionFieldContext field : clauses.partitioning.fields) {
                if (field instanceof SparkSqlBaseParser.PartitionColumnContext) {
                    SparkSqlBaseParser.ColTypeContext col = ((SparkSqlBaseParser.PartitionColumnContext) field).colType();
                    hiveStyleColumns.add(columnDef(SparkParserUtils.identifier(col.colName), col.dataType(),
                            col.NOT() != null, col.commentSpec(), null));
                    partitionColumns.add(SparkParserUtils.identifier(col.colName));
                } else {
                    partitionColumns.add(identityTransform((SparkSqlBaseParser.PartitionTransformContext) field));
                }
            }
        }

        if (isCtas) {
            if (ctx.createOrReplaceTableColTypeList() != null || !hiveStyleColumns.isEmpty()) {
                throw new ParsingException("Schema may not be specified in a Create Table As Select (CTAS) statement",
                        pos);
            }
            QueryStatement query = new QueryStatement(root.visit(ctx.query(), QueryRelation.class));
            PartitionDesc partitionDesc = partitionDesc(partitionColumns, pos);
            CreateTableStmt createTable = new CreateTableStmt(header.IF() != null, false, tableRef, null, "", null,
                    partitionDesc, distribution, emptyToNull(properties), null, comment);
            return new CreateTableAsSelectStmt(createTable, null, query, pos);
        }

        if (ctx.createOrReplaceTableColTypeList() == null && hiveStyleColumns.isEmpty()) {
            throw unsupported("CREATE TABLE without a column list or a query");
        }
        List<ColumnDef> columns = new ArrayList<>();
        if (ctx.createOrReplaceTableColTypeList() != null) {
            for (SparkSqlBaseParser.CreateOrReplaceTableColTypeContext col
                    : ctx.createOrReplaceTableColTypeList().createOrReplaceTableColType()) {
                columns.add(columnDef(col));
            }
        }
        columns.addAll(hiveStyleColumns);
        columns = partitionColumnsLast(columns, partitionColumns, !external);
        PartitionDesc partitionDesc = partitionDesc(partitionColumns, pos);
        CreateTableStmt stmt = new CreateTableStmt(header.IF() != null, false, tableRef, columns, "", null,
                partitionDesc, distribution, emptyToNull(properties), null, comment);
        return stmt;
    }

    /**
     * CREATE TABLE t LIKE s. The table properties are kept, any other clause is not supported.
     */
    @Override
    public ParseNode visitCreateTableLike(SparkSqlBaseParser.CreateTableLikeContext ctx) {
        if (!ctx.tableProvider().isEmpty() || !ctx.rowFormat().isEmpty() || !ctx.createFileFormat().isEmpty()
                || !ctx.locationSpec().isEmpty()) {
            throw unsupported("CREATE TABLE ... LIKE with USING, ROW FORMAT, STORED AS or LOCATION");
        }
        NodePosition pos = pos(ctx);
        TableRef target = new TableRef(qualifiedName(tableIdentifier(ctx.target)), null, pos(ctx.target));
        TableRef source = new TableRef(qualifiedName(tableIdentifier(ctx.source)), null, pos(ctx.source));
        Map<String, String> properties = properties(ctx.propertyList());
        return new CreateTableLikeStmt(ctx.IF() != null, target, source, null, null, properties, pos);
    }

    @Override
    public ParseNode visitDropTable(SparkSqlBaseParser.DropTableContext ctx) {
        TableRef tableRef = tableRef(ctx.identifierReference());
        // PURGE skips the trash, which is what FORCE does
        return new DropTableStmt(ctx.IF() != null, tableRef, false, ctx.PURGE() != null, pos(ctx));
    }

    @Override
    public ParseNode visitDropView(SparkSqlBaseParser.DropViewContext ctx) {
        return new DropTableStmt(ctx.IF() != null, tableRef(ctx.identifierReference()), true, false, pos(ctx));
    }

    @Override
    public ParseNode visitTruncateTable(SparkSqlBaseParser.TruncateTableContext ctx) {
        if (ctx.partitionSpec() != null) {
            throw unsupported("TRUNCATE TABLE ... PARTITION");
        }
        return new TruncateTableStmt(tableRef(ctx.identifierReference()), pos(ctx));
    }

    @Override
    public ParseNode visitRenameTable(SparkSqlBaseParser.RenameTableContext ctx) {
        if (ctx.VIEW() != null) {
            throw unsupported("ALTER VIEW ... RENAME TO");
        }
        QualifiedName from = qualifiedName(ctx.from);
        List<String> to = SparkParserUtils.multipartIdentifier(ctx.multipartIdentifier());
        // StarRocks renames a table inside its database only
        List<String> fromParts = from.getParts();
        if (to.size() > 1 && !to.subList(0, to.size() - 1).equals(
                fromParts.subList(Math.max(0, fromParts.size() - to.size()), fromParts.size() - 1))) {
            throw unsupported("ALTER TABLE ... RENAME TO a table in another database");
        }
        AlterClause clause = new TableRenameClause(to.get(to.size() - 1), pos(ctx));
        return new AlterTableStmt(new TableRef(from, null, pos(ctx.from)), Lists.newArrayList(clause), pos(ctx));
    }

    @Override
    public ParseNode visitAddTableColumns(SparkSqlBaseParser.AddTableColumnsContext ctx) {
        List<ColumnDef> columns = new ArrayList<>();
        ColumnPosition position = null;
        List<SparkSqlBaseParser.QualifiedColTypeWithPositionContext> defs =
                ctx.qualifiedColTypeWithPositionList().qualifiedColTypeWithPosition();
        for (SparkSqlBaseParser.QualifiedColTypeWithPositionContext def : defs) {
            List<String> name = SparkParserUtils.multipartIdentifier(def.multipartIdentifier());
            if (name.size() != 1) {
                throw unsupported("ALTER TABLE ADD COLUMNS with a nested column " + String.join(".", name));
            }
            boolean notNull = false;
            SparkSqlBaseParser.CommentSpecContext comment = null;
            SparkSqlBaseParser.DefaultExpressionContext defaultExpr = null;
            for (SparkSqlBaseParser.ColDefinitionDescriptorWithPositionContext option
                    : def.colDefinitionDescriptorWithPosition()) {
                if (option.NOT() != null) {
                    notNull = true;
                } else if (option.commentSpec() != null) {
                    comment = option.commentSpec();
                } else if (option.defaultExpression() != null) {
                    defaultExpr = option.defaultExpression();
                } else {
                    SparkSqlBaseParser.ColPositionContext colPosition = option.colPosition();
                    position = colPosition.FIRST() != null ? ColumnPosition.FIRST
                            : new ColumnPosition(SparkParserUtils.identifier(colPosition.afterCol), pos(colPosition));
                }
            }
            columns.add(columnDef(name.get(0), def.dataType(), notNull, comment, defaultExpr));
        }
        if (columns.size() > 1 && position != null) {
            throw unsupported("ALTER TABLE ADD COLUMNS with FIRST or AFTER for several columns");
        }
        AlterClause clause = columns.size() == 1
                ? new AddColumnClause(columns.get(0), position, null, null, pos(ctx))
                : new AddColumnsClause(columns, null, null, pos(ctx));
        return new AlterTableStmt(tableRef(ctx.identifierReference()), Lists.newArrayList(clause), pos(ctx));
    }

    // -------------------------------------------------- views ----------------------------------------------------

    @Override
    public ParseNode visitCreateView(SparkSqlBaseParser.CreateViewContext ctx) {
        if (ctx.TEMPORARY() != null) {
            // TODO(spark): temporary views (CREATE [OR REPLACE] [GLOBAL] TEMP VIEW) need a session registry that
            //  the analyzer consults before the catalog (design 05 section 4): QueryAnalyzer.resolveTableRef would
            //  expand a registry entry as a view, and the table collectors of PlannerMetaLocker, AnalyzerUtils and
            //  QueryAnalyzer.ExternalTablesOnlyVisitor must skip the temp view names. That touches the shared
            //  analyzer and the lock collection, so it is left out until it can be tested; use CREATE VIEW or a
            //  CTE meanwhile.
            throw unsupported("CREATE [OR REPLACE] [GLOBAL] TEMPORARY VIEW (temporary views are not supported, "
                    + "use CREATE VIEW or a CTE)");
        }
        if (!ctx.PARTITIONED().isEmpty()) {
            throw unsupported("CREATE VIEW ... PARTITIONED ON");
        }
        if (ctx.IF() != null && ctx.REPLACE() != null) {
            throw new ParsingException("CREATE VIEW can not combine IF NOT EXISTS and OR REPLACE", pos(ctx));
        }
        if (!ctx.propertyList().isEmpty()) {
            throw unsupported("CREATE VIEW ... TBLPROPERTIES");
        }
        List<ColWithComment> columns = null;
        if (ctx.identifierCommentList() != null) {
            columns = new ArrayList<>();
            for (SparkSqlBaseParser.IdentifierCommentContext col : ctx.identifierCommentList().identifierComment()) {
                columns.add(new ColWithComment(SparkParserUtils.identifier(col.identifier()),
                        col.commentSpec() == null ? null : SparkParserUtils.stringLit(col.commentSpec().stringLit()),
                        pos(col)));
            }
        }
        QueryStatement query = new QueryStatement(root.visit(ctx.query(), QueryRelation.class));
        CreateViewStmt stmt = new CreateViewStmt(ctx.IF() != null, ctx.REPLACE() != null,
                tableRef(ctx.identifierReference()), columns, comment(ctx.commentSpec()), false, false, query,
                pos(ctx), new HashMap<>());
        // ViewAnalyzer cuts the query text out of the statement text with these indexes. The text is Spark SQL;
        // the definition that is stored and replayed is generated from the analyzed AST, it is StarRocks SQL.
        stmt.setQueryStartIndex(ctx.query().start.getStartIndex());
        stmt.setQueryStopIndex(ctx.query().stop.getStopIndex() + 1);
        return stmt;
    }

    // ------------------------------------------- databases / catalogs ---------------------------------------------

    @Override
    public ParseNode visitUse(SparkSqlBaseParser.UseContext ctx) {
        return buildUse(ctx, ctx.identifierReference());
    }

    @Override
    public ParseNode visitUseNamespace(SparkSqlBaseParser.UseNamespaceContext ctx) {
        return buildUse(ctx, ctx.identifierReference());
    }

    private UseDbStmt buildUse(ParserRuleContext ctx, SparkSqlBaseParser.IdentifierReferenceContext ref) {
        List<String> parts = identifierParts(ref);
        if (parts.size() == 1) {
            return new UseDbStmt(null, parts.get(0), pos(ctx));
        } else if (parts.size() == 2) {
            // spark_catalog is the session catalog of Spark, the catalog of spark_default_catalog here
            return new UseDbStmt(catalogs.mapCatalog(parts.get(0)), parts.get(1), pos(ctx));
        }
        throw unsupported("USE with a nested namespace " + String.join(".", parts));
    }

    @Override
    public ParseNode visitSetCatalog(SparkSqlBaseParser.SetCatalogContext ctx) {
        String name = ctx.identifier() != null ? SparkParserUtils.identifier(ctx.identifier())
                : SparkParserUtils.stringLit(ctx.stringLit());
        return new SetCatalogStmt(catalogs.mapCatalog(name), pos(ctx));
    }

    @Override
    public ParseNode visitCreateNamespace(SparkSqlBaseParser.CreateNamespaceContext ctx) {
        if (!ctx.commentSpec().isEmpty() || !ctx.locationSpec().isEmpty()
                || ctx.propertyList() != null && !ctx.propertyList().isEmpty()) {
            throw unsupported("CREATE DATABASE with COMMENT, LOCATION or properties");
        }
        List<String> parts = identifierParts(ctx.identifierReference());
        return new CreateDbStmt(ctx.EXISTS() != null, catalogOf(parts), databaseOf(parts), new HashMap<>(), pos(ctx));
    }

    @Override
    public ParseNode visitDropNamespace(SparkSqlBaseParser.DropNamespaceContext ctx) {
        if (ctx.CASCADE() != null) {
            // StarRocks only drops an empty database without FORCE, and FORCE skips the recycle bin
            throw unsupported("DROP DATABASE ... CASCADE");
        }
        List<String> parts = identifierParts(ctx.identifierReference());
        return new DropDbStmt(ctx.EXISTS() != null, catalogOf(parts), databaseOf(parts), false, pos(ctx));
    }

    // ------------------------------------------------- session -------------------------------------------------

    @Override
    public ParseNode visitSetConfiguration(SparkSqlBaseParser.SetConfigurationContext ctx) {
        return buildSet(ctx);
    }

    @Override
    public ParseNode visitSetQuotedConfiguration(SparkSqlBaseParser.SetQuotedConfigurationContext ctx) {
        return buildSet(ctx);
    }

    /**
     * Spark parses the raw text after SET: the key is everything in front of the first "=", the value everything
     * behind it.
     */
    private ParseNode buildSet(ParserRuleContext ctx) {
        NodePosition pos = pos(ctx);
        String rest = textAfterKeyword(ctx);
        if (rest.isEmpty() || rest.equals("-v")) {
            // list the variables
            return new ShowVariablesStmt(SetType.SESSION, null, null, pos);
        }
        int eq = rest.indexOf('=');
        String key = unquoteKey(eq < 0 ? rest : rest.substring(0, eq).trim());
        if (!CONF_KEY.matcher(key).matches() || NON_CONF_SET_WORDS.contains(key.toLowerCase(Locale.ROOT))) {
            // SET GLOBAL x = 1, SET @a = 1, SET NAMES utf8 ... are StarRocks syntax
            throw unsupported("SET " + SparkParserUtils.abbreviate(rest));
        }
        if (eq < 0) {
            // SET key shows the value of key
            String variable = SparkConfMapper.variableOf(key);
            if (variable == null) {
                throw unsupported("SET " + key + " for a configuration without a StarRocks counterpart");
            }
            return new ShowVariablesStmt(SetType.SESSION, variable, null, pos);
        }
        String rawValue = rest.substring(eq + 1).trim();
        if (!SparkConfMapper.isConfKey(key)) {
            // a StarRocks variable: values that may be expressions are left to the StarRocks parser
            String unquoted = unquoteValue(rawValue);
            if (unquoted.equals(rawValue) && !PLAIN_VALUE.matcher(rawValue).matches()) {
                throw unsupported("SET " + SparkParserUtils.abbreviate(rest));
            }
            if (unquoted.equals(rawValue) && rawValue.equalsIgnoreCase("default")) {
                return setStmt(SparkConfMapper.reset(key), pos);
            }
        }
        return setStmt(SparkConfMapper.set(key, rawValue), pos);
    }

    private static SparkSetStmt setStmt(SparkConfMapper.Result result, NodePosition pos) {
        return new SparkSetStmt(new ArrayList<SetListItem>(result.getVariables()), result.getNotices(),
                result.getAnsiMode(), pos);
    }

    @Override
    public ParseNode visitResetConfiguration(SparkSqlBaseParser.ResetConfigurationContext ctx) {
        return buildReset(ctx);
    }

    @Override
    public ParseNode visitResetQuotedConfiguration(SparkSqlBaseParser.ResetQuotedConfigurationContext ctx) {
        return buildReset(ctx);
    }

    private ParseNode buildReset(ParserRuleContext ctx) {
        String key = unquoteKey(textAfterKeyword(ctx));
        if (key.isEmpty()) {
            throw unsupported("RESET without a configuration key");
        }
        if (!CONF_KEY.matcher(key).matches()) {
            throw unsupported("RESET " + SparkParserUtils.abbreviate(key));
        }
        return setStmt(SparkConfMapper.reset(key), pos(ctx));
    }

    @Override
    public ParseNode visitSetTimeZone(SparkSqlBaseParser.SetTimeZoneContext ctx) {
        if (ctx.timezone() == null) {
            throw unsupported(ctx);
        }
        SparkSqlBaseParser.TimezoneContext zone = ctx.timezone();
        SystemVariable variable;
        if (zone.LOCAL() != null) {
            variable = new SystemVariable(SetType.SESSION, SessionVariable.TIME_ZONE, null);
        } else {
            variable = new SystemVariable(SetType.SESSION, SessionVariable.TIME_ZONE,
                    new StringLiteral(SparkParserUtils.stringLit(zone.stringLit())));
        }
        return new SparkSetStmt(Lists.newArrayList(variable), null, null, pos(ctx));
    }

    @Override
    public ParseNode visitCacheTable(SparkSqlBaseParser.CacheTableContext ctx) {
        if (ctx.query() != null) {
            throw unsupported("CACHE TABLE ... AS SELECT (it creates a temporary view)");
        }
        return SparkSetStmt.noop("CACHE TABLE is ignored: StarRocks has no table cache to fill by command", pos(ctx));
    }

    @Override
    public ParseNode visitUncacheTable(SparkSqlBaseParser.UncacheTableContext ctx) {
        return SparkSetStmt.noop("UNCACHE TABLE is ignored: StarRocks has no table cache to drop by command",
                pos(ctx));
    }

    @Override
    public ParseNode visitClearCache(SparkSqlBaseParser.ClearCacheContext ctx) {
        return SparkSetStmt.noop("CLEAR CACHE is ignored: StarRocks has no table cache to clear by command",
                pos(ctx));
    }

    /**
     * ADD JAR / ADD FILE / LIST JAR ...
     */
    @Override
    public ParseNode visitManageResource(SparkSqlBaseParser.ManageResourceContext ctx) {
        String what = SparkParserUtils.abbreviate(SparkParserUtils.originalText(ctx));
        return SparkSetStmt.noop("'" + what + "' is ignored: resources are not added to a StarRocks session, "
                + "register a UDF jar with CREATE GLOBAL FUNCTION", pos(ctx));
    }

    @Override
    public ParseNode visitCreateFunction(SparkSqlBaseParser.CreateFunctionContext ctx) {
        throw unsupported("CREATE [TEMPORARY] FUNCTION ... AS 'class' [USING JAR ...] is not supported, "
                + "use CREATE GLOBAL FUNCTION to register a UDF");
    }

    // ------------------------------------------------ commands ---------------------------------------------------

    @Override
    public ParseNode visitRefreshTable(SparkSqlBaseParser.RefreshTableContext ctx) {
        return new RefreshTableStmt(tableRef(ctx.identifierReference()), null, pos(ctx));
    }

    @Override
    public ParseNode visitRepairTable(SparkSqlBaseParser.RepairTableContext ctx) {
        if (ctx.option != null && ctx.option.getType() != SparkSqlBaseParser.ADD) {
            throw unsupported("MSCK REPAIR TABLE ... " + ctx.option.getText() + " PARTITIONS");
        }
        return new RefreshTableStmt(tableRef(ctx.identifierReference()), null, pos(ctx));
    }

    @Override
    public ParseNode visitRecoverPartitions(SparkSqlBaseParser.RecoverPartitionsContext ctx) {
        return new RefreshTableStmt(tableRef(ctx.identifierReference()), null, pos(ctx));
    }

    @Override
    public ParseNode visitAnalyze(SparkSqlBaseParser.AnalyzeContext ctx) {
        if (ctx.partitionSpec() != null) {
            throw unsupported("ANALYZE TABLE ... PARTITION");
        }
        NodePosition pos = pos(ctx);
        if (ctx.identifier() != null) {
            if (ctx.identifier().getText().equalsIgnoreCase("noscan")) {
                // NOSCAN only collects the size of the table
                return SparkSetStmt.noop("ANALYZE TABLE ... NOSCAN is ignored: StarRocks collects row counts by "
                        + "itself", pos);
            }
            throw unsupported(ctx);
        }
        List<Expr> columns = new ArrayList<>();
        if (ctx.identifierSeq() != null) {
            for (SparkSqlBaseParser.ErrorCapturingIdentifierContext column : ctx.identifierSeq().ident) {
                columns.add(new SlotRef(null, SparkParserUtils.identifier(column)));
            }
        }
        return new AnalyzeStmt(tableRef(ctx.identifierReference()), columns, null, new HashMap<>(), false, false,
                false, new AnalyzeBasicDesc(), pos);
    }

    // -------------------------------------------------- show -----------------------------------------------------

    @Override
    public ParseNode visitShowTables(SparkSqlBaseParser.ShowTablesContext ctx) {
        String catalog = null;
        String database = null;
        if (ctx.identifierReference() != null) {
            List<String> parts = identifierParts(ctx.identifierReference());
            catalog = parts.size() == 1 ? null : catalogOf(parts);
            database = databaseOf(parts);
        }
        return new ShowTableStmt(database, false, likePattern(ctx.pattern), null, catalog, pos(ctx));
    }

    @Override
    public ParseNode visitShowNamespaces(SparkSqlBaseParser.ShowNamespacesContext ctx) {
        String catalog = null;
        if (ctx.multipartIdentifier() != null) {
            List<String> parts = SparkParserUtils.multipartIdentifier(ctx.multipartIdentifier());
            if (parts.size() != 1) {
                throw unsupported("SHOW DATABASES in a nested namespace");
            }
            catalog = catalogs.mapCatalog(parts.get(0));
        }
        return new ShowDbStmt(likePattern(ctx.pattern), null, catalog, pos(ctx));
    }

    @Override
    public ParseNode visitShowColumns(SparkSqlBaseParser.ShowColumnsContext ctx) {
        List<String> table = new ArrayList<>(identifierParts(ctx.table));
        if (ctx.ns != null) {
            // SHOW COLUMNS IN t IN db
            List<String> ns = SparkParserUtils.multipartIdentifier(ctx.ns);
            if (table.size() != 1) {
                throw unsupported("SHOW COLUMNS with a qualified table and a namespace");
            }
            table.addAll(0, ns);
        }
        QualifiedName name = qualifiedName(table, pos(ctx.table));
        return new ShowColumnStmt(new TableRef(name, null, pos(ctx.table)), null, false, null, pos(ctx));
    }

    @Override
    public ParseNode visitShowCreateTable(SparkSqlBaseParser.ShowCreateTableContext ctx) {
        if (ctx.SERDE() != null) {
            throw unsupported("SHOW CREATE TABLE ... AS SERDE");
        }
        return new ShowCreateTableStmt(tableRef(ctx.identifierReference()),
                ShowCreateTableStmt.CreateTableType.TABLE, pos(ctx));
    }

    @Override
    public ParseNode visitDescribeRelation(SparkSqlBaseParser.DescribeRelationContext ctx) {
        if (ctx.partitionSpec() != null || ctx.describeColName() != null) {
            throw unsupported("DESCRIBE with a partition or a column");
        }
        // EXTENDED and FORMATTED show more table properties in Spark, StarRocks has one DESC
        return new DescribeStmt(tableRef(ctx.identifierReference()), false, pos(ctx));
    }

    // ------------------------------------------------ helpers ---------------------------------------------------

    private List<String> identifierParts(SparkSqlBaseParser.IdentifierReferenceContext ctx) {
        if (ctx.multipartIdentifier() == null) {
            throw unsupported("IDENTIFIER(...) reference");
        }
        return SparkParserUtils.multipartIdentifier(ctx.multipartIdentifier());
    }

    private QualifiedName qualifiedName(SparkSqlBaseParser.IdentifierReferenceContext ctx) {
        return qualifiedName(identifierParts(ctx), pos(ctx));
    }

    private QualifiedName qualifiedName(List<String> parts, NodePosition pos) {
        List<String> qualified = catalogs.qualify(parts);
        if (qualified.size() > 3) {
            throw unsupported("table name with " + qualified.size() + " parts: " + String.join(".", qualified));
        }
        return QualifiedName.of(qualified, pos);
    }

    private TableRef tableRef(SparkSqlBaseParser.IdentifierReferenceContext ctx) {
        return new TableRef(qualifiedName(ctx), null, pos(ctx));
    }

    private static List<String> tableIdentifier(SparkSqlBaseParser.TableIdentifierContext ctx) {
        List<String> parts = new ArrayList<>();
        if (ctx.db != null) {
            parts.add(SparkParserUtils.identifier(ctx.db));
        }
        parts.add(SparkParserUtils.identifier(ctx.table));
        return parts;
    }

    private QualifiedName qualifiedName(List<String> parts) {
        return qualifiedName(parts, NodePosition.ZERO);
    }

    /**
     * The catalog of a database reference [catalog.]db, mapped for Spark; the empty string means the current catalog
     * for CREATE and DROP DATABASE, null for SHOW.
     */
    private String catalogOf(List<String> parts) {
        if (parts.size() == 1) {
            return "";
        } else if (parts.size() == 2) {
            return catalogs.mapCatalog(parts.get(0));
        }
        throw unsupported("database with a nested namespace " + String.join(".", parts));
    }

    private static String databaseOf(List<String> parts) {
        return parts.get(parts.size() - 1);
    }

    private static <T> T lastOf(List<T> list) {
        return list.get(list.size() - 1);
    }

    private static <K, V> Map<K, V> emptyToNull(Map<K, V> map) {
        return map.isEmpty() ? null : map;
    }

    /**
     * The text of a SET or RESET statement behind its first keyword.
     */
    private static String textAfterKeyword(ParserRuleContext ctx) {
        String text = SparkParserUtils.originalText(ctx).trim();
        int end = ctx.start.getText().length();
        return text.substring(Math.min(end, text.length())).trim();
    }

    private static String unquoteKey(String key) {
        if (key.length() >= 2 && key.charAt(0) == '`' && key.charAt(key.length() - 1) == '`') {
            return key.substring(1, key.length() - 1).replace("``", "`");
        }
        return key;
    }

    private static String unquoteValue(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '\'' || first == '"' || first == '`') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * Spark's pattern for SHOW TABLES / DATABASES is a glob: * matches anything and | separates alternatives.
     * StarRocks uses a LIKE pattern.
     */
    private static String likePattern(SparkSqlBaseParser.StringLitContext pattern) {
        if (pattern == null) {
            return null;
        }
        String glob = SparkParserUtils.stringLit(pattern);
        if (glob.indexOf('|') >= 0) {
            throw unsupported("a pattern with alternatives (|) in SHOW");
        }
        return glob.replace("*", "%");
    }

    private static String comment(SparkSqlBaseParser.CommentSpecContext ctx) {
        return ctx == null ? null : SparkParserUtils.stringLit(ctx.stringLit());
    }

    private static String comment(List<SparkSqlBaseParser.CommentSpecContext> specs) {
        return specs.isEmpty() ? null : comment(lastOf(specs));
    }

    private static String storedAs(List<SparkSqlBaseParser.CreateFileFormatContext> formats) {
        if (formats.isEmpty()) {
            return null;
        }
        if (formats.size() > 1) {
            throw new ParsingException("Found duplicate clauses: STORED AS", pos(formats.get(1)));
        }
        SparkSqlBaseParser.FileFormatContext format = formats.get(0).fileFormat();
        if (!(format instanceof SparkSqlBaseParser.GenericFileFormatContext)) {
            throw unsupported("STORED AS INPUTFORMAT ... OUTPUTFORMAT ... / STORED BY");
        }
        return ((SparkSqlBaseParser.GenericFileFormatContext) format).identifier().getText().toLowerCase(Locale.ROOT);
    }

    /**
     * The file_format property of an external table, null for the default format. The provider (USING) and the
     * Hive style STORED AS are the same thing for StarRocks.
     */
    private static String fileFormat(String provider, String storedAs, boolean external) {
        if (provider != null && storedAs != null && !provider.equals("hive")) {
            throw new ParsingException("Syntax error: both USING and STORED AS are specified");
        }
        String format = null;
        if (provider != null && !provider.equals("hive")) {
            if (provider.equals("iceberg")) {
                if (!external) {
                    throw unsupported("USING iceberg for a StarRocks native table");
                }
            } else if (FILE_FORMATS.contains(provider)) {
                format = provider;
            } else if (!NATIVE_IGNORED_PROVIDERS.contains(provider)) {
                throw unsupported("USING " + provider);
            }
        }
        if (storedAs != null) {
            if (!FILE_FORMATS.contains(storedAs)) {
                throw unsupported("STORED AS " + storedAs);
            }
            format = storedAs;
        }
        return external ? format : null;
    }

    private static DistributionDesc distribution(SparkSqlBaseParser.BucketSpecContext ctx) {
        if (ctx.SORTED() != null) {
            throw unsupported("CLUSTERED BY ... SORTED BY");
        }
        List<String> columns = SparkParserUtils.identifierSeq(ctx.identifierList());
        return new HashDistributionDesc(Integer.parseInt(ctx.INTEGER_VALUE().getText()), columns, pos(ctx));
    }

    private static String identityTransform(SparkSqlBaseParser.PartitionTransformContext ctx) {
        if (!(ctx.transform() instanceof SparkSqlBaseParser.IdentityTransformContext)) {
            throw unsupported("PARTITIONED BY with a transform " + SparkParserUtils.abbreviate(ctx.getText()));
        }
        SparkSqlBaseParser.QualifiedNameContext name =
                ((SparkSqlBaseParser.IdentityTransformContext) ctx.transform()).qualifiedName();
        if (name.identifier().size() != 1) {
            throw unsupported("PARTITIONED BY a nested column " + name.getText());
        }
        return SparkParserUtils.identifier(name.identifier(0));
    }

    /**
     * Partitioning by columns is a list partition by the values of the columns, which is what Hive and Iceberg
     * identity partitions are, and an automatic partition of a native table.
     */
    private static PartitionDesc partitionDesc(List<String> columns, NodePosition pos) {
        if (columns.isEmpty()) {
            return null;
        }
        ListPartitionDesc desc = new ListPartitionDesc(new ArrayList<>(columns), new ArrayList<>(), pos);
        desc.setAutoPartitionTable(true);
        return desc;
    }

    /**
     * Spark moves the partition columns of a table to the end of its schema, and so does Hive. A native table
     * partitioned by the value of a column needs it NOT NULL.
     */
    private static List<ColumnDef> partitionColumnsLast(List<ColumnDef> columns, List<String> partitionColumns,
                                                        boolean notNull) {
        if (partitionColumns.isEmpty()) {
            return columns;
        }
        List<ColumnDef> result = new ArrayList<>();
        for (ColumnDef column : columns) {
            if (partitionColumns.stream().noneMatch(p -> p.equalsIgnoreCase(column.getName()))) {
                result.add(column);
            }
        }
        for (String name : partitionColumns) {
            ColumnDef found = columns.stream().filter(c -> c.getName().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> new ParsingException("Partition column [" + name
                            + "] does not exist in column list", NodePosition.ZERO));
            if (notNull) {
                found.setAllowNull(false);
            }
            result.add(found);
        }
        return result;
    }

    private ColumnDef columnDef(SparkSqlBaseParser.CreateOrReplaceTableColTypeContext ctx) {
        boolean notNull = false;
        SparkSqlBaseParser.CommentSpecContext comment = null;
        SparkSqlBaseParser.DefaultExpressionContext defaultExpr = null;
        for (SparkSqlBaseParser.ColDefinitionOptionContext option : ctx.colDefinitionOption()) {
            if (option.NOT() != null) {
                notNull = true;
            } else if (option.commentSpec() != null) {
                comment = option.commentSpec();
            } else if (option.defaultExpression() != null) {
                defaultExpr = option.defaultExpression();
            } else {
                throw unsupported("generated column " + SparkParserUtils.identifier(ctx.colName));
            }
        }
        return columnDef(SparkParserUtils.identifier(ctx.colName), ctx.dataType(), notNull, comment, defaultExpr);
    }

    private ColumnDef columnDef(String name, SparkSqlBaseParser.DataTypeContext dataType, boolean notNull,
                                SparkSqlBaseParser.CommentSpecContext comment,
                                SparkSqlBaseParser.DefaultExpressionContext defaultExpr) {
        Type type = root.toType(dataType);
        ColumnDef.DefaultValueDef defaultValue = ColumnDef.DefaultValueDef.NOT_SET;
        if (defaultExpr != null) {
            Expr expr = root.visit(defaultExpr.expression(), Expr.class);
            if (expr instanceof NullLiteral) {
                defaultValue = ColumnDef.DefaultValueDef.NULL_DEFAULT_VALUE;
            } else if (expr instanceof LiteralExpr) {
                defaultValue = new ColumnDef.DefaultValueDef(true, new StringLiteral(((LiteralExpr) expr)
                        .getStringValue()));
            } else {
                throw unsupported("DEFAULT " + SparkParserUtils.abbreviate(defaultExpr.getText())
                        + " is not a literal");
            }
        }
        ColumnDef column = new ColumnDef(name, new TypeDef(type, pos(dataType)), null, false, null, null,
                notNull ? Boolean.FALSE : null, defaultValue, null, null,
                comment == null ? "" : SparkParserUtils.stringLit(comment.stringLit()), pos(dataType));
        column.setExplicitSqlType(true);
        return column;
    }

    private static Map<String, String> properties(List<SparkSqlBaseParser.PropertyListContext> lists) {
        Map<String, String> result = new LinkedHashMap<>();
        for (SparkSqlBaseParser.PropertyListContext list : lists) {
            result.putAll(properties(list));
        }
        return result;
    }

    private static Map<String, String> properties(SparkSqlBaseParser.PropertyListContext list) {
        Map<String, String> result = new LinkedHashMap<>();
        if (list == null) {
            return result;
        }
        for (SparkSqlBaseParser.PropertyContext property : list.property()) {
            SparkSqlBaseParser.PropertyKeyContext key = property.key;
            String name;
            if (key.stringLit() != null) {
                name = SparkParserUtils.stringLit(key.stringLit());
            } else {
                List<String> parts = new ArrayList<>();
                for (SparkSqlBaseParser.IdentifierContext part : key.identifier()) {
                    parts.add(SparkParserUtils.identifier(part));
                }
                name = String.join(".", parts);
            }
            SparkSqlBaseParser.PropertyValueContext value = property.value;
            String text = "";
            if (value != null) {
                text = value.stringLit() != null ? SparkParserUtils.stringLit(value.stringLit()) : value.getText();
            }
            result.put(name, text);
        }
        return result;
    }
}
