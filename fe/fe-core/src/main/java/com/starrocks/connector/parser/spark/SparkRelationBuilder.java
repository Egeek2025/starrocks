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

import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.TableName;
import com.starrocks.sql.analyzer.RelationId;
import com.starrocks.sql.ast.CTERelation;
import com.starrocks.sql.ast.ExceptRelation;
import com.starrocks.sql.ast.GroupByClause;
import com.starrocks.sql.ast.IntersectRelation;
import com.starrocks.sql.ast.JoinOperator;
import com.starrocks.sql.ast.JoinRelation;
import com.starrocks.sql.ast.OrderByElement;
import com.starrocks.sql.ast.ParseNode;
import com.starrocks.sql.ast.PivotAggregation;
import com.starrocks.sql.ast.PivotRelation;
import com.starrocks.sql.ast.PivotValue;
import com.starrocks.sql.ast.QualifiedName;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.Relation;
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectListItem;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.SetQualifier;
import com.starrocks.sql.ast.SubqueryRelation;
import com.starrocks.sql.ast.TableFunctionRelation;
import com.starrocks.sql.ast.TableRelation;
import com.starrocks.sql.ast.UnionRelation;
import com.starrocks.sql.ast.ValuesRelation;
import com.starrocks.sql.ast.expression.ArrayExpr;
import com.starrocks.sql.ast.expression.BoolLiteral;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.FunctionParams;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.LimitElement;
import com.starrocks.sql.ast.expression.LiteralExpr;
import com.starrocks.sql.ast.expression.NullLiteral;
import com.starrocks.sql.ast.expression.SlotRef;
import com.starrocks.sql.ast.expression.StringLiteral;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParserBaseVisitor;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.starrocks.connector.parser.spark.SparkAstBuilder.unsupported;
import static com.starrocks.connector.parser.spark.SparkParserUtils.pos;

/**
 * Builds queries: CTE, set operations, SELECT, FROM, joins, subqueries, GROUP BY, ORDER BY and LIMIT.
 *
 * <p>Supported: single tables and aliases (with column alias lists), subqueries in FROM, VALUES,
 * INNER/LEFT/RIGHT/FULL/CROSS/LEFT SEMI/LEFT ANTI joins with ON or USING, UNION/INTERSECT/EXCEPT [ALL],
 * GROUP BY including ROLLUP/CUBE/GROUPING SETS, and these Spark specific constructs:
 * <ul>
 *   <li>LATERAL VIEW [OUTER] explode/posexplode/explode_outer/posexplode_outer/json_tuple/stack, chained, with
 *   column aliases; and a generator in the SELECT list (at most one), which becomes a lateral view. All of them are
 *   a lateral join with {@code unnest}; explode of a map is expanded by the analyzer, see {@link SparkGenerators}.</li>
 *   <li>Named WINDOW clauses are resolved where the window is used, see {@link SparkExprBuilder}.</li>
 *   <li>SORT BY / CLUSTER BY of the outermost query become ORDER BY, DISTRIBUTE BY is ignored, and all three are
 *   dropped in subqueries.</li>
 *   <li>PIVOT, as the StarRocks pivot clause. TABLESAMPLE (n ROWS) as a LIMIT.</li>
 *   <li>Join hints BROADCAST/BROADCASTJOIN/MAPJOIN and SHUFFLE_HASH on the right table of a join; other hints are
 *   performance hints and ignored.</li>
 * </ul>
 * Not covered (they throw {@link SparkUnsupportedException}): inline, TRANSFORM, UNPIVOT, TABLESAMPLE by PERCENT,
 * BUCKET or bytes, time travel, table valued functions, NATURAL joins, LATERAL subqueries, combining ORDER BY with
 * SORT BY or CLUSTER BY.
 */
public class SparkRelationBuilder extends SparkSqlBaseParserBaseVisitor<ParseNode> {
    private final SparkAstBuilder root;
    // CTE names visible while building a query: lower case name -> spelling at the definition. Spark resolves
    // CTE names case-insensitively, the StarRocks analyzer case-sensitively.
    private final Deque<Map<String, String>> cteScopes = new ArrayDeque<>();
    private int generatedAliasId = 0;
    // nesting depth of the query being built: only the outermost query keeps its SORT BY / CLUSTER BY
    private int queryDepth = 0;

    private static final Set<String> GENERATORS = Set.of("explode", "explode_outer", "posexplode",
            "posexplode_outer", "json_tuple", "stack");
    private static final Pattern JSON_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public SparkRelationBuilder(SparkAstBuilder root) {
        this.root = root;
    }

    // ------------------------------------------------ query -----------------------------------------------------

    @Override
    public ParseNode visitQuery(SparkSqlBaseParser.QueryContext ctx) {
        Map<String, String> scope = new HashMap<>();
        cteScopes.push(scope);
        boolean outermost = queryDepth == 0;
        queryDepth++;
        try {
            List<CTERelation> ctes = new ArrayList<>();
            if (ctx.ctes() != null) {
                for (SparkSqlBaseParser.NamedQueryContext namedQuery : ctx.ctes().namedQuery()) {
                    ctes.add(buildCte(namedQuery, scope));
                }
            }

            QueryRelation relation = root.visit(ctx.queryTerm(), QueryRelation.class);
            SparkSqlBaseParser.QueryOrganizationContext org = ctx.queryOrganization();
            List<OrderByElement> orderBy = buildOrderBy(org, outermost);
            // a named WINDOW clause needs no handling here: a window reference looks its definition up
            LimitElement limit = buildLimit(org);

            boolean hasOrganization = !orderBy.isEmpty() || limit != null;
            if ((hasOrganization && (relation.hasOrderByClause() || relation.hasLimit()))
                    || (!ctes.isEmpty() && relation.hasWithClause())) {
                // the query term is a parenthesized query that already has its own clauses
                relation = wrapInSelectStar(relation);
            }
            relation.setOrderBy(orderBy);
            relation.setLimit(limit);
            ctes.forEach(relation::addCTERelation);
            return relation;
        } finally {
            queryDepth--;
            cteScopes.pop();
        }
    }

    /**
     * ORDER BY, or in the outermost query the ordering that SORT BY / CLUSTER BY ask for. Those sort within each
     * partition, a total order is a valid result of it; DISTRIBUTE BY only affects the partitioning and is ignored.
     * In a subquery the order is not observable and dropped.
     */
    private List<OrderByElement> buildOrderBy(SparkSqlBaseParser.QueryOrganizationContext org, boolean outermost) {
        boolean hasLocalOrder = !org.clusterBy.isEmpty() || !org.sort.isEmpty();
        if (!org.order.isEmpty() && hasLocalOrder) {
            throw unsupported("combination of ORDER BY with SORT BY / CLUSTER BY");
        }
        if (!org.clusterBy.isEmpty() && (!org.sort.isEmpty() || !org.distributeBy.isEmpty())) {
            throw unsupported("combination of CLUSTER BY with SORT BY / DISTRIBUTE BY");
        }
        List<OrderByElement> orderBy = new ArrayList<>();
        if (!org.order.isEmpty()) {
            for (SparkSqlBaseParser.SortItemContext item : org.order) {
                orderBy.add(root.visit(item, OrderByElement.class));
            }
        } else if (outermost && !org.sort.isEmpty()) {
            for (SparkSqlBaseParser.SortItemContext item : org.sort) {
                orderBy.add(root.visit(item, OrderByElement.class));
            }
        } else if (outermost && !org.clusterBy.isEmpty()) {
            for (SparkSqlBaseParser.ExpressionContext item : org.clusterBy) {
                // ascending, nulls first: the Spark default
                orderBy.add(new OrderByElement(root.visit(item, Expr.class), true, true, pos(item)));
            }
        }
        return orderBy;
    }

    private CTERelation buildCte(SparkSqlBaseParser.NamedQueryContext ctx, Map<String, String> scope) {
        String name = SparkParserUtils.identifier(ctx.name);
        QueryRelation query = root.visit(ctx.query(), QueryRelation.class);
        List<String> columnNames = ctx.columnAliases == null ? null : SparkParserUtils.identifierSeq(ctx.columnAliases);
        // the name becomes visible after its own body: Spark has no recursive CTE
        scope.put(name.toLowerCase(), name);
        return new CTERelation(RelationId.of(query).hashCode(), name, columnNames, new QueryStatement(query),
                false, true, pos(ctx), CTERelation.CTEMaterializationHint.NONE);
    }

    private LimitElement buildLimit(SparkSqlBaseParser.QueryOrganizationContext org) {
        long limit = LimitElement.NO_LIMIT.getLimit();
        long offset = 0;
        boolean present = false;
        if (org.limit != null) {
            limit = constantLong(org.limit, "LIMIT");
            present = true;
        }
        if (org.offset != null) {
            offset = constantLong(org.offset, "OFFSET");
            present = true;
        }
        return present ? new LimitElement(offset, limit, pos(org)) : null;
    }

    private long constantLong(SparkSqlBaseParser.ExpressionContext ctx, String clause) {
        Expr expr = root.visit(ctx, Expr.class);
        if (expr instanceof LiteralExpr && ((LiteralExpr) expr).getType().isIntegerType()) {
            return ((LiteralExpr) expr).getLongValue();
        }
        throw unsupported(clause + " with a non-literal value");
    }

    private QueryRelation wrapInSelectStar(QueryRelation inner) {
        SubqueryRelation subquery = new SubqueryRelation(new QueryStatement(inner));
        subquery.setAlias(new TableName(null, nextGeneratedAlias()));
        List<SelectListItem> items = new ArrayList<>();
        items.add(new SelectListItem((TableName) null, NodePosition.ZERO, new ArrayList<>()));
        return new SelectRelation(new SelectList(items, false), subquery, null, null, null);
    }

    private String nextGeneratedAlias() {
        return nextGeneratedAlias("__spark_subquery_");
    }

    private String nextGeneratedAlias(String prefix) {
        return prefix + generatedAliasId++;
    }

    @Override
    public ParseNode visitSubquery(SparkSqlBaseParser.SubqueryContext ctx) {
        return root.visit(ctx.query(), QueryRelation.class);
    }

    @Override
    public ParseNode visitSetOperation(SparkSqlBaseParser.SetOperationContext ctx) {
        QueryRelation left = root.visit(ctx.left, QueryRelation.class);
        QueryRelation right = root.visit(ctx.right, QueryRelation.class);
        boolean distinct = ctx.setQuantifier() == null || ctx.setQuantifier().ALL() == null;
        SetQualifier qualifier = distinct ? SetQualifier.DISTINCT : SetQualifier.ALL;
        NodePosition pos = pos(ctx);
        // a left operand with its own ORDER BY/LIMIT/WITH is a parenthesized query and must not be merged
        boolean mergeable = !left.hasOrderByClause() && !left.hasLimit() && !left.hasWithClause();
        switch (ctx.operator.getType()) {
            case com.starrocks.sql.parser.spark.gen.SparkSqlBaseLexer.UNION:
                if (mergeable && left instanceof UnionRelation && ((UnionRelation) left).getQualifier() == qualifier) {
                    ((UnionRelation) left).addRelation(right);
                    return left;
                }
                return new UnionRelation(new ArrayList<>(List.of(left, right)), qualifier, pos);
            case com.starrocks.sql.parser.spark.gen.SparkSqlBaseLexer.INTERSECT:
                if (mergeable && left instanceof IntersectRelation
                        && ((IntersectRelation) left).getQualifier() == qualifier) {
                    ((IntersectRelation) left).addRelation(right);
                    return left;
                }
                return new IntersectRelation(new ArrayList<>(List.of(left, right)), qualifier, pos);
            default:
                // EXCEPT and MINUS
                if (mergeable && left instanceof ExceptRelation
                        && ((ExceptRelation) left).getQualifier() == qualifier) {
                    ((ExceptRelation) left).addRelation(right);
                    return left;
                }
                return new ExceptRelation(new ArrayList<>(List.of(left, right)), qualifier, pos);
        }
    }

    @Override
    public ParseNode visitTransformQuerySpecification(SparkSqlBaseParser.TransformQuerySpecificationContext ctx) {
        throw unsupported("TRANSFORM ... USING");
    }

    @Override
    public ParseNode visitRegularQuerySpecification(SparkSqlBaseParser.RegularQuerySpecificationContext ctx) {
        SparkSqlBaseParser.SelectClauseContext select = ctx.selectClause();
        boolean distinct = select.setQuantifier() != null && select.setQuantifier().DISTINCT() != null;

        Relation from;
        if (ctx.fromClause() == null) {
            if (!ctx.lateralView().isEmpty()) {
                throw unsupported("LATERAL VIEW without FROM");
            }
            from = ValuesRelation.newDualRelation();
        } else {
            from = buildFrom(ctx.fromClause());
        }
        for (SparkSqlBaseParser.LateralViewContext lateralView : ctx.lateralView()) {
            from = buildLateralView(from, lateralView);
        }

        List<SelectListItem> items = new ArrayList<>();
        boolean hasGenerator = false;
        boolean hasStar = false;
        for (SparkSqlBaseParser.NamedExpressionContext named : select.namedExpressionSeq().namedExpression()) {
            SparkSqlBaseParser.FunctionCallContext generator = asGenerator(named);
            if (generator == null) {
                hasStar |= isUnqualifiedStar(named);
                items.add(buildSelectItem(named));
                continue;
            }
            if (hasGenerator) {
                throw unsupported("more than one generator in the SELECT list");
            }
            hasGenerator = true;
            // the generator becomes a lateral view, and its columns are selected where the generator was
            String alias = nextGeneratedAlias("__spark_generator_");
            from = buildSelectGenerator(from, generator, named, alias);
            items.add(new SelectListItem(new TableName(null, alias), pos(named), new ArrayList<>()));
        }
        if (hasGenerator && hasStar) {
            throw unsupported("* together with a generator in the SELECT list");
        }
        applyJoinHints(from, select.hints);

        Expr where = ctx.whereClause() == null ? null
                : root.visit(ctx.whereClause().booleanExpression(), Expr.class);
        GroupByClause groupBy = ctx.aggregationClause() == null ? null : buildGroupBy(ctx.aggregationClause());
        Expr having = ctx.havingClause() == null ? null
                : root.visit(ctx.havingClause().booleanExpression(), Expr.class);
        return new SelectRelation(new SelectList(items, distinct), from, where, groupBy, having, pos(ctx));
    }

    private static boolean isUnqualifiedStar(SparkSqlBaseParser.NamedExpressionContext ctx) {
        ParseTree inner = SparkParserUtils.unwrapSingleChild(ctx.expression());
        return inner instanceof SparkSqlBaseParser.StarContext
                && ((SparkSqlBaseParser.StarContext) inner).qualifiedName() == null;
    }

    // ------------------------------------------- generators ----------------------------------------------------

    /**
     * A generator is built for LATERAL VIEW or for a generator in the SELECT list: a table function plus the
     * information whether rows without output are kept (outer).
     */
    private static final class Generator {
        private final TableFunctionRelation function;
        private final boolean outer;

        private Generator(TableFunctionRelation function, boolean outer) {
            this.function = function;
            this.outer = outer;
        }
    }

    private Relation buildLateralView(Relation left, SparkSqlBaseParser.LateralViewContext ctx) {
        List<Expr> args = new ArrayList<>();
        for (SparkSqlBaseParser.ExpressionContext arg : ctx.expression()) {
            args.add(root.visit(arg, Expr.class));
        }
        List<String> columns = null;
        if (!ctx.colName.isEmpty()) {
            columns = new ArrayList<>();
            for (SparkSqlBaseParser.IdentifierContext column : ctx.colName) {
                columns.add(SparkParserUtils.identifier(column));
            }
        }
        List<String> nameParts = new ArrayList<>();
        for (SparkSqlBaseParser.IdentifierContext part : ctx.qualifiedName().identifier()) {
            nameParts.add(SparkParserUtils.identifier(part));
        }
        String name = String.join(".", nameParts).toLowerCase(Locale.ROOT);
        Generator generator = buildGenerator(name, args, columns, pos(ctx));
        generator.function.setAlias(new TableName(null, SparkParserUtils.identifier(ctx.tblName)));
        return lateralJoin(left, generator, ctx.OUTER() != null, pos(ctx));
    }

    /**
     * A generator in the SELECT list: {@code SELECT explode(a) AS x, b FROM t} is the same as
     * {@code SELECT x, b FROM t LATERAL VIEW explode(a) alias AS x}.
     */
    private Relation buildSelectGenerator(Relation left, SparkSqlBaseParser.FunctionCallContext call,
                                          SparkSqlBaseParser.NamedExpressionContext named, String alias) {
        List<Expr> args = new ArrayList<>();
        for (SparkSqlBaseParser.FunctionArgumentContext arg : call.argument) {
            if (arg.expression() == null) {
                throw unsupported("named argument of a generator");
            }
            args.add(root.visit(arg.expression(), Expr.class));
        }
        List<String> columns = null;
        if (named.name != null) {
            columns = List.of(SparkParserUtils.identifier(named.name));
        } else if (named.identifierList() != null) {
            columns = SparkParserUtils.identifierSeq(named.identifierList());
        }
        Generator generator = buildGenerator(generatorName(call), args, columns, pos(call));
        generator.function.setAlias(new TableName(null, alias));
        return lateralJoin(left, generator, false, pos(named));
    }

    private static Relation lateralJoin(Relation left, Generator generator, boolean outerKeyword, NodePosition pos) {
        if (generator.outer || outerKeyword) {
            // supported by the analyzer for unnest only, and only as ON true
            return new JoinRelation(JoinOperator.LEFT_OUTER_JOIN, left, generator.function, new BoolLiteral(true),
                    true, pos);
        }
        return new JoinRelation(JoinOperator.CROSS_JOIN, left, generator.function, null, true, pos);
    }

    private static String generatorName(SparkSqlBaseParser.FunctionCallContext ctx) {
        SparkSqlBaseParser.FunctionNameContext name = ctx.functionName();
        if (name.qualifiedName() == null || name.qualifiedName().identifier().size() != 1) {
            return null;
        }
        return SparkParserUtils.identifier(name.qualifiedName().identifier(0)).toLowerCase(Locale.ROOT);
    }

    /**
     * The function call of a select item when it is a generator, otherwise null.
     */
    private static SparkSqlBaseParser.FunctionCallContext asGenerator(SparkSqlBaseParser.NamedExpressionContext ctx) {
        ParseTree inner = SparkParserUtils.unwrapSingleChild(ctx.expression());
        if (!(inner instanceof SparkSqlBaseParser.FunctionCallContext)) {
            return null;
        }
        SparkSqlBaseParser.FunctionCallContext call = (SparkSqlBaseParser.FunctionCallContext) inner;
        String name = generatorName(call);
        if (name == null || !GENERATORS.contains(name) || call.windowSpec() != null || call.where != null) {
            return null;
        }
        return call;
    }

    /**
     * Translates a Spark generator to a table function over {@code unnest}. Everything but explode and posexplode
     * is built from arrays of the values, so that unnest of several arrays yields them as columns:
     * <ul>
     *   <li>json_tuple(js, 'a', 'b'): unnest([get_json_string(js, '$.a')], [get_json_string(js, '$.b')])</li>
     *   <li>stack(n, v1, ..., vk): unnest([v1, v3, ...], [v2, v4, ...]) with one array per output column, padded
     *   with NULL</li>
     * </ul>
     */
    private Generator buildGenerator(String name, List<Expr> args, List<String> columnNames, NodePosition pos) {
        if (name == null || !GENERATORS.contains(name)) {
            throw unsupported("generator " + name);
        }
        TableFunctionRelation function;
        boolean outer = name.endsWith("_outer");
        switch (name) {
            case "explode":
            case "explode_outer":
            case "posexplode":
            case "posexplode_outer":
                if (args.size() != 1) {
                    throw new ParsingException(name + " expects exactly one argument", pos);
                }
                function = new TableFunctionRelation(FunctionSet.UNNEST, new FunctionParams(false, args), pos);
                function.setSparkGenerator(name.startsWith("pos") ? TableFunctionRelation.SparkGenerator.POSEXPLODE
                        : TableFunctionRelation.SparkGenerator.EXPLODE);
                function.setColumnOutputNames(columnNames);
                break;
            case "json_tuple":
                function = buildJsonTuple(args, columnNames, pos);
                break;
            default:
                function = buildStack(args, columnNames, pos);
                break;
        }
        return new Generator(function, outer);
    }

    private TableFunctionRelation buildJsonTuple(List<Expr> args, List<String> columnNames, NodePosition pos) {
        if (args.size() < 2) {
            throw new ParsingException("json_tuple requires at least two arguments", pos);
        }
        List<Expr> arrays = new ArrayList<>();
        List<String> defaultNames = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            Expr key = args.get(i);
            if (!(key instanceof StringLiteral) || !JSON_KEY.matcher(((StringLiteral) key).getValue()).matches()) {
                throw unsupported("json_tuple with a field name that is not a simple string literal");
            }
            Expr json = i == 1 ? args.get(0) : args.get(0).clone();
            Expr value = new FunctionCallExpr("get_json_string", new ArrayList<>(List.of(json,
                    new StringLiteral("$." + ((StringLiteral) key).getValue()))), pos);
            arrays.add(new ArrayExpr(null, new ArrayList<>(List.of(value)), pos));
            defaultNames.add("c" + (i - 1));
        }
        TableFunctionRelation function = new TableFunctionRelation(FunctionSet.UNNEST,
                new FunctionParams(false, arrays), pos);
        function.setColumnOutputNames(columnNames != null ? columnNames : defaultNames);
        return function;
    }

    private TableFunctionRelation buildStack(List<Expr> args, List<String> columnNames, NodePosition pos) {
        if (args.size() < 2 || !(args.get(0) instanceof IntLiteral) || ((IntLiteral) args.get(0)).getValue() < 1) {
            throw unsupported("stack without a constant positive row count and at least one value");
        }
        int rows = (int) ((IntLiteral) args.get(0)).getValue();
        int values = args.size() - 1;
        int columns = (values + rows - 1) / rows;
        List<Expr> arrays = new ArrayList<>();
        List<String> defaultNames = new ArrayList<>();
        for (int column = 0; column < columns; column++) {
            List<Expr> items = new ArrayList<>();
            for (int row = 0; row < rows; row++) {
                int index = row * columns + column;
                items.add(index < values ? args.get(index + 1) : new NullLiteral(pos));
            }
            arrays.add(new ArrayExpr(null, items, pos));
            defaultNames.add("col" + column);
        }
        TableFunctionRelation function = new TableFunctionRelation(FunctionSet.UNNEST,
                new FunctionParams(false, arrays), pos);
        function.setColumnOutputNames(columnNames != null ? columnNames : defaultNames);
        return function;
    }

    // ----------------------------------------------- hints -----------------------------------------------------

    /**
     * BROADCAST / BROADCASTJOIN / MAPJOIN (table) and SHUFFLE_HASH (table) become the join hint of the join that
     * has the table as its right input. Hints change the plan, never the result, so everything that cannot be
     * expressed (the table is the left input, the join type does not allow it, MERGE, REPARTITION, COALESCE, ...)
     * is ignored.
     */
    private void applyJoinHints(Relation from, List<SparkSqlBaseParser.HintContext> hints) {
        for (SparkSqlBaseParser.HintContext hint : hints) {
            for (SparkSqlBaseParser.HintStatementContext statement : hint.hintStatements) {
                String name = SparkParserUtils.identifier(statement.hintName).toUpperCase(Locale.ROOT);
                String joinHint;
                switch (name) {
                    case "BROADCAST":
                    case "BROADCASTJOIN":
                    case "MAPJOIN":
                        joinHint = "BROADCAST";
                        break;
                    case "SHUFFLE_HASH":
                        joinHint = "SHUFFLE";
                        break;
                    default:
                        continue;
                }
                for (SparkSqlBaseParser.PrimaryExpressionContext parameter : statement.parameters) {
                    String text = parameter.getText().toLowerCase(Locale.ROOT);
                    applyJoinHint(from, text.substring(text.lastIndexOf('.') + 1).replace("`", ""), joinHint);
                }
            }
        }
    }

    private void applyJoinHint(Relation relation, String tableName, String joinHint) {
        if (!(relation instanceof JoinRelation)) {
            return;
        }
        JoinRelation join = (JoinRelation) relation;
        if (join.getJoinHint().isEmpty() && tableName.equals(resolveName(join.getRight()))
                && joinAllowsHint(join, joinHint)) {
            join.setJoinHint(joinHint);
        }
        applyJoinHint(join.getLeft(), tableName, joinHint);
    }

    private static String resolveName(Relation relation) {
        if (relation instanceof TableRelation) {
            TableRelation table = (TableRelation) relation;
            return (table.getAlias() != null ? table.getAlias().getTbl() : table.getName().getTbl())
                    .toLowerCase(Locale.ROOT);
        }
        if (relation instanceof SubqueryRelation && relation.getAlias() != null) {
            return relation.getAlias().getTbl().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean joinAllowsHint(JoinRelation join, String joinHint) {
        JoinOperator op = join.getJoinOp();
        if (joinHint.equals("BROADCAST")) {
            return op != JoinOperator.RIGHT_OUTER_JOIN && op != JoinOperator.FULL_OUTER_JOIN
                    && op != JoinOperator.RIGHT_SEMI_JOIN && op != JoinOperator.RIGHT_ANTI_JOIN;
        }
        return op != JoinOperator.CROSS_JOIN && (join.getOnPredicate() != null || join.getUsingColNames() != null);
    }

    private SelectListItem buildSelectItem(SparkSqlBaseParser.NamedExpressionContext ctx) {
        if (ctx.identifierList() != null) {
            throw unsupported("multiple aliases in SELECT list");
        }
        ParseTree inner = SparkParserUtils.unwrapSingleChild(ctx.expression());
        if (inner instanceof SparkSqlBaseParser.StarContext) {
            SparkSqlBaseParser.StarContext star = (SparkSqlBaseParser.StarContext) inner;
            if (ctx.name != null) {
                throw unsupported("alias of *");
            }
            TableName table = null;
            if (star.qualifiedName() != null) {
                List<String> parts = new ArrayList<>();
                for (SparkSqlBaseParser.IdentifierContext id : star.qualifiedName().identifier()) {
                    parts.add(SparkParserUtils.identifier(id));
                }
                table = toTableName(parts, pos(star));
            }
            return new SelectListItem(table, pos(ctx), new ArrayList<>());
        }
        Expr expr = root.visit(ctx.expression(), Expr.class);
        String alias = ctx.name == null ? null : SparkParserUtils.identifier(ctx.name);
        return new SelectListItem(expr, alias, pos(ctx));
    }

    // ---------------------------------------------- group by ---------------------------------------------------

    private GroupByClause buildGroupBy(SparkSqlBaseParser.AggregationClauseContext ctx) {
        NodePosition pos = pos(ctx);
        if (!ctx.groupingExpressionsWithGroupingAnalytics.isEmpty()) {
            List<SparkSqlBaseParser.GroupByClauseContext> elements = ctx.groupingExpressionsWithGroupingAnalytics;
            boolean hasAnalytics = elements.stream().anyMatch(e -> e.groupingAnalytics() != null);
            if (!hasAnalytics) {
                ArrayList<Expr> exprs = new ArrayList<>();
                for (SparkSqlBaseParser.GroupByClauseContext element : elements) {
                    exprs.add(root.visit(element.expression(), Expr.class));
                }
                return new GroupByClause(exprs, GroupByClause.GroupingType.GROUP_BY, pos);
            }
            if (elements.size() > 1) {
                throw unsupported("GROUP BY combining plain expressions with ROLLUP/CUBE/GROUPING SETS");
            }
            return buildGroupingAnalytics(elements.get(0).groupingAnalytics(), pos);
        }

        ArrayList<Expr> exprs = new ArrayList<>();
        for (SparkSqlBaseParser.ExpressionContext expr : ctx.groupingExpressions) {
            exprs.add(root.visit(expr, Expr.class));
        }
        if (ctx.kind == null) {
            return new GroupByClause(exprs, GroupByClause.GroupingType.GROUP_BY, pos);
        }
        switch (ctx.kind.getType()) {
            case com.starrocks.sql.parser.spark.gen.SparkSqlBaseLexer.ROLLUP:
                return new GroupByClause(exprs, GroupByClause.GroupingType.ROLLUP, pos);
            case com.starrocks.sql.parser.spark.gen.SparkSqlBaseLexer.CUBE:
                return new GroupByClause(exprs, GroupByClause.GroupingType.CUBE, pos);
            default:
                // GROUP BY a, b GROUPING SETS ((a), (b)): the sets are what matters
                List<ArrayList<Expr>> sets = new ArrayList<>();
                for (SparkSqlBaseParser.GroupingSetContext set : ctx.groupingSet()) {
                    sets.add(groupingSetExprs(set));
                }
                return new GroupByClause(sets, GroupByClause.GroupingType.GROUPING_SETS, pos);
        }
    }

    private GroupByClause buildGroupingAnalytics(SparkSqlBaseParser.GroupingAnalyticsContext ctx, NodePosition pos) {
        if (ctx.ROLLUP() != null || ctx.CUBE() != null) {
            ArrayList<Expr> exprs = new ArrayList<>();
            for (SparkSqlBaseParser.GroupingSetContext set : ctx.groupingSet()) {
                ArrayList<Expr> setExprs = groupingSetExprs(set);
                if (setExprs.size() != 1) {
                    throw unsupported("composite column in ROLLUP/CUBE");
                }
                exprs.add(setExprs.get(0));
            }
            return new GroupByClause(exprs,
                    ctx.ROLLUP() != null ? GroupByClause.GroupingType.ROLLUP : GroupByClause.GroupingType.CUBE, pos);
        }
        List<ArrayList<Expr>> sets = new ArrayList<>();
        for (SparkSqlBaseParser.GroupingElementContext element : ctx.groupingElement()) {
            if (element.groupingSet() == null) {
                throw unsupported("nested ROLLUP/CUBE in GROUPING SETS");
            }
            sets.add(groupingSetExprs(element.groupingSet()));
        }
        return new GroupByClause(sets, GroupByClause.GroupingType.GROUPING_SETS, pos);
    }

    private ArrayList<Expr> groupingSetExprs(SparkSqlBaseParser.GroupingSetContext ctx) {
        ArrayList<Expr> exprs = new ArrayList<>();
        for (SparkSqlBaseParser.ExpressionContext expr : ctx.expression()) {
            exprs.add(root.visit(expr, Expr.class));
        }
        return exprs;
    }

    // ----------------------------------------------- order by --------------------------------------------------

    @Override
    public ParseNode visitSortItem(SparkSqlBaseParser.SortItemContext ctx) {
        boolean asc = ctx.DESC() == null;
        // Spark: NULLS FIRST is the default for ASC and NULLS LAST for DESC
        boolean nullsFirst = ctx.nullOrder == null ? asc : ctx.FIRST() != null;
        return new OrderByElement(root.visit(ctx.expression(), Expr.class), asc, nullsFirst, pos(ctx));
    }

    // ----------------------------------------------- from ------------------------------------------------------

    private Relation buildFrom(SparkSqlBaseParser.FromClauseContext ctx) {
        if (ctx.unpivotClause() != null) {
            throw unsupported("UNPIVOT");
        }
        Relation result = null;
        for (SparkSqlBaseParser.RelationContext relation : ctx.relation()) {
            Relation next = root.visit(relation, Relation.class);
            result = result == null ? next : new JoinRelation(null, result, next, null, false);
        }
        for (SparkSqlBaseParser.LateralViewContext lateralView : ctx.lateralView()) {
            result = buildLateralView(result, lateralView);
        }
        if (ctx.pivotClause() != null) {
            result = buildPivot(result, ctx.pivotClause());
        }
        return result;
    }

    /**
     * PIVOT (agg [AS a], ... FOR col IN (value [AS v], ...)) is the StarRocks pivot clause. The values have to be
     * literals.
     */
    private Relation buildPivot(Relation input, SparkSqlBaseParser.PivotClauseContext ctx) {
        List<PivotAggregation> aggregations = new ArrayList<>();
        for (SparkSqlBaseParser.NamedExpressionContext named : ctx.aggregates.namedExpression()) {
            Expr aggregate = root.visit(named.expression(), Expr.class);
            if (!(aggregate instanceof FunctionCallExpr) || named.identifierList() != null) {
                throw unsupported("PIVOT aggregate that is not a function call");
            }
            String alias = named.name == null ? null : SparkParserUtils.identifier(named.name);
            aggregations.add(new PivotAggregation((FunctionCallExpr) aggregate, alias, pos(named)));
        }

        List<SlotRef> columns = new ArrayList<>();
        for (SparkSqlBaseParser.IdentifierContext identifier : ctx.pivotColumn().identifiers) {
            columns.add(new SlotRef(QualifiedName.of(List.of(SparkParserUtils.identifier(identifier)), pos(ctx))));
        }

        List<PivotValue> values = new ArrayList<>();
        for (SparkSqlBaseParser.PivotValueContext value : ctx.pivotValues) {
            List<LiteralExpr> literals = new ArrayList<>();
            ParseTree inner = SparkParserUtils.unwrapSingleChild(value.expression());
            List<SparkSqlBaseParser.ExpressionContext> parts = new ArrayList<>();
            if (inner instanceof SparkSqlBaseParser.RowConstructorContext) {
                for (SparkSqlBaseParser.NamedExpressionContext item
                        : ((SparkSqlBaseParser.RowConstructorContext) inner).namedExpression()) {
                    parts.add(item.expression());
                }
            } else {
                parts.add(value.expression());
            }
            for (SparkSqlBaseParser.ExpressionContext part : parts) {
                Expr literal = root.visit(part, Expr.class);
                if (!(literal instanceof LiteralExpr)) {
                    throw unsupported("PIVOT value that is not a literal");
                }
                literals.add((LiteralExpr) literal);
            }
            String alias = value.identifier() == null ? null : SparkParserUtils.identifier(value.identifier());
            if (alias == null && literals.size() == 1 && literals.get(0) instanceof StringLiteral) {
                // Spark names the column after the string, without quotes
                alias = ((StringLiteral) literals.get(0)).getValue();
            }
            if (literals.size() != columns.size()) {
                throw new ParsingException("The number of PIVOT values does not match the number of PIVOT columns",
                        pos(value));
            }
            values.add(new PivotValue(literals, alias, pos(value)));
        }
        return new PivotRelation(input, aggregations, columns, values, pos(ctx));
    }

    @Override
    public ParseNode visitRelation(SparkSqlBaseParser.RelationContext ctx) {
        if (ctx.LATERAL() != null) {
            throw unsupported("LATERAL");
        }
        Relation left = root.visit(ctx.relationPrimary(), Relation.class);
        for (SparkSqlBaseParser.RelationExtensionContext extension : ctx.relationExtension()) {
            if (extension.pivotClause() != null) {
                left = buildPivot(left, extension.pivotClause());
            } else if (extension.joinRelation() != null) {
                left = buildJoin(left, extension.joinRelation());
            } else {
                throw unsupported("UNPIVOT");
            }
        }
        return left;
    }

    private Relation buildJoin(Relation left, SparkSqlBaseParser.JoinRelationContext ctx) {
        if (ctx.NATURAL() != null) {
            throw unsupported("NATURAL JOIN");
        }
        if (ctx.LATERAL() != null) {
            throw unsupported("LATERAL join");
        }
        Relation right = root.visit(ctx.right, Relation.class);
        SparkSqlBaseParser.JoinTypeContext type = ctx.joinType();
        SparkSqlBaseParser.JoinCriteriaContext criteria = ctx.joinCriteria();

        JoinOperator op;
        if (type.CROSS() != null) {
            op = criteria == null ? JoinOperator.CROSS_JOIN : JoinOperator.INNER_JOIN;
        } else if (type.SEMI() != null) {
            op = JoinOperator.LEFT_SEMI_JOIN;
        } else if (type.ANTI() != null) {
            op = JoinOperator.LEFT_ANTI_JOIN;
        } else if (type.LEFT() != null) {
            op = JoinOperator.LEFT_OUTER_JOIN;
        } else if (type.RIGHT() != null) {
            op = JoinOperator.RIGHT_OUTER_JOIN;
        } else if (type.FULL() != null) {
            op = JoinOperator.FULL_OUTER_JOIN;
        } else {
            // plain JOIN / INNER JOIN; without a condition Spark treats it as a cross join
            op = criteria == null ? JoinOperator.CROSS_JOIN : JoinOperator.INNER_JOIN;
        }

        Expr predicate = null;
        List<String> usingColumns = null;
        if (criteria != null) {
            if (criteria.ON() != null) {
                predicate = root.visit(criteria.booleanExpression(), Expr.class);
            } else {
                usingColumns = SparkParserUtils.identifierSeq(criteria.identifierList());
            }
        } else if (op == JoinOperator.LEFT_SEMI_JOIN || op == JoinOperator.LEFT_ANTI_JOIN
                || op == JoinOperator.LEFT_OUTER_JOIN || op == JoinOperator.RIGHT_OUTER_JOIN
                || op == JoinOperator.FULL_OUTER_JOIN) {
            throw new ParsingException("Join condition is missing for " + type.getText() + " JOIN", pos(ctx));
        }
        JoinRelation join = new JoinRelation(op, left, right, predicate, false, pos(ctx));
        join.setUsingColNames(usingColumns);
        return join;
    }

    @Override
    public ParseNode visitTableName(SparkSqlBaseParser.TableNameContext ctx) {
        if (ctx.temporalClause() != null) {
            throw unsupported("time travel (VERSION/TIMESTAMP AS OF)");
        }
        SparkSqlBaseParser.TableAliasContext aliasCtx = ctx.tableAlias();
        if (ctx.identifierReference().multipartIdentifier() == null) {
            throw unsupported("IDENTIFIER(...) table reference");
        }
        List<String> parts = SparkParserUtils.multipartIdentifier(ctx.identifierReference().multipartIdentifier());
        if (parts.size() == 1) {
            // keep the spelling of the CTE definition, the analyzer matches CTE names case-sensitively
            for (Map<String, String> scope : cteScopes) {
                String declared = scope.get(parts.get(0).toLowerCase());
                if (declared != null) {
                    parts = List.of(declared);
                    break;
                }
            }
        }
        TableRelation relation = new TableRelation(toTableName(parts, pos(ctx)), null, new ArrayList<>(),
                new ArrayList<>(), pos(ctx));
        String alias = aliasCtx.strictIdentifier() == null ? null
                : SparkParserUtils.identifier(aliasCtx.strictIdentifier());
        if (ctx.sample() == null && aliasCtx.identifierList() == null) {
            if (alias != null) {
                relation.setAlias(new TableName(null, alias));
            }
            return relation;
        }
        // TABLESAMPLE or a column alias list: (SELECT * FROM t [LIMIT n]) alias (c1, c2, ...)
        SelectRelation select = selectStar(relation);
        if (ctx.sample() != null) {
            select.setLimit(sampleLimit(ctx.sample()));
        }
        SubqueryRelation subquery = new SubqueryRelation(new QueryStatement(select));
        subquery.setAlias(new TableName(null, alias != null ? alias : parts.get(parts.size() - 1)));
        if (aliasCtx.identifierList() != null) {
            subquery.setColumnOutputNames(SparkParserUtils.identifierSeq(aliasCtx.identifierList()));
        }
        return subquery;
    }

    /**
     * TABLESAMPLE (n ROWS) keeps at most n rows. The other methods sample randomly and cannot be reproduced.
     */
    private LimitElement sampleLimit(SparkSqlBaseParser.SampleContext ctx) {
        if (!(ctx.sampleMethod() instanceof SparkSqlBaseParser.SampleByRowsContext)) {
            throw unsupported("TABLESAMPLE by PERCENT, BUCKET or bytes");
        }
        SparkSqlBaseParser.SampleByRowsContext rows = (SparkSqlBaseParser.SampleByRowsContext) ctx.sampleMethod();
        return new LimitElement(0, constantLong(rows.expression(), "TABLESAMPLE"), pos(ctx));
    }

    private static SelectRelation selectStar(Relation from) {
        List<SelectListItem> items = new ArrayList<>();
        items.add(new SelectListItem((TableName) null, NodePosition.ZERO, new ArrayList<>()));
        return new SelectRelation(new SelectList(items, false), from, null, null, null);
    }

    @Override
    public ParseNode visitAliasedQuery(SparkSqlBaseParser.AliasedQueryContext ctx) {
        QueryRelation query = root.visit(ctx.query(), QueryRelation.class);
        SubqueryRelation subquery = new SubqueryRelation(new QueryStatement(query));
        SparkSqlBaseParser.TableAliasContext aliasCtx = ctx.tableAlias();
        String alias = aliasCtx.strictIdentifier() == null ? nextGeneratedAlias()
                : SparkParserUtils.identifier(aliasCtx.strictIdentifier());
        subquery.setAlias(new TableName(null, alias));
        if (aliasCtx.identifierList() != null) {
            subquery.setColumnOutputNames(SparkParserUtils.identifierSeq(aliasCtx.identifierList()));
        }
        if (ctx.sample() == null) {
            return subquery;
        }
        // the sample is taken after the column aliases are applied
        SelectRelation select = selectStar(subquery);
        select.setLimit(sampleLimit(ctx.sample()));
        SubqueryRelation sampled = new SubqueryRelation(new QueryStatement(select));
        sampled.setAlias(new TableName(null, alias));
        return sampled;
    }

    @Override
    public ParseNode visitAliasedRelation(SparkSqlBaseParser.AliasedRelationContext ctx) {
        if (ctx.sample() != null) {
            throw unsupported("TABLESAMPLE of a parenthesized relation");
        }
        if (ctx.tableAlias().strictIdentifier() != null) {
            throw unsupported("alias of a parenthesized relation");
        }
        return root.visit(ctx.relation());
    }

    @Override
    public ParseNode visitTableValuedFunction(SparkSqlBaseParser.TableValuedFunctionContext ctx) {
        throw unsupported("table valued function " + ctx.functionTable().funcName.getText());
    }

    @Override
    public ParseNode visitInlineTable(SparkSqlBaseParser.InlineTableContext ctx) {
        List<List<Expr>> rows = new ArrayList<>();
        for (SparkSqlBaseParser.ExpressionContext rowCtx : ctx.expression()) {
            List<Expr> row = new ArrayList<>();
            ParseTree inner = SparkParserUtils.unwrapSingleChild(rowCtx);
            if (inner instanceof SparkSqlBaseParser.RowConstructorContext) {
                for (SparkSqlBaseParser.NamedExpressionContext item
                        : ((SparkSqlBaseParser.RowConstructorContext) inner).namedExpression()) {
                    row.add(root.visit(item.expression(), Expr.class));
                }
            } else {
                row.add(root.visit(rowCtx, Expr.class));
            }
            if (!rows.isEmpty() && rows.get(0).size() != row.size()) {
                throw new ParsingException("VALUES rows must have the same number of columns", pos(rowCtx));
            }
            rows.add(row);
        }
        SparkSqlBaseParser.TableAliasContext aliasCtx = ctx.tableAlias();
        List<String> names = new ArrayList<>();
        if (aliasCtx.identifierList() != null) {
            names.addAll(SparkParserUtils.identifierSeq(aliasCtx.identifierList()));
            if (names.size() != rows.get(0).size()) {
                throw new ParsingException("The number of column aliases does not match the VALUES columns",
                        pos(ctx));
            }
        } else {
            for (int i = 1; i <= rows.get(0).size(); i++) {
                names.add("col" + i);
            }
        }
        ValuesRelation values = new ValuesRelation(rows, names, pos(ctx));
        if (aliasCtx.strictIdentifier() != null) {
            values.setAlias(new TableName(null, SparkParserUtils.identifier(aliasCtx.strictIdentifier())));
        }
        return values;
    }

    @Override
    public ParseNode visitInlineTableDefault1(SparkSqlBaseParser.InlineTableDefault1Context ctx) {
        return root.visit(ctx.inlineTable());
    }

    @Override
    public ParseNode visitInlineTableDefault2(SparkSqlBaseParser.InlineTableDefault2Context ctx) {
        return root.visit(ctx.inlineTable());
    }

    // ----------------------------------------------- helpers ---------------------------------------------------

    static TableName toTableName(List<String> parts, NodePosition pos) {
        switch (parts.size()) {
            case 1:
                return new TableName(null, null, parts.get(0), pos);
            case 2:
                return new TableName(null, parts.get(0), parts.get(1), pos);
            case 3:
                return new TableName(parts.get(0), parts.get(1), parts.get(2), pos);
            default:
                throw unsupported("table name with " + parts.size() + " parts: " + String.join(".", parts));
        }
    }
}
