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
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.Relation;
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectListItem;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.SetQualifier;
import com.starrocks.sql.ast.SubqueryRelation;
import com.starrocks.sql.ast.TableRelation;
import com.starrocks.sql.ast.UnionRelation;
import com.starrocks.sql.ast.ValuesRelation;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.LimitElement;
import com.starrocks.sql.ast.expression.LiteralExpr;
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
import java.util.Map;

import static com.starrocks.connector.parser.spark.SparkAstBuilder.unsupported;
import static com.starrocks.connector.parser.spark.SparkParserUtils.pos;

/**
 * Builds queries: CTE, set operations, SELECT, FROM, joins, subqueries, GROUP BY, ORDER BY and LIMIT.
 *
 * <p>Phase A covers: single tables and aliases, subqueries in FROM, VALUES, INNER/LEFT/RIGHT/FULL/CROSS/LEFT SEMI/
 * LEFT ANTI joins with ON or USING, UNION/INTERSECT/EXCEPT [ALL], GROUP BY including ROLLUP/CUBE/GROUPING SETS.
 * Not covered yet (they throw {@link SparkUnsupportedException}): LATERAL VIEW, named WINDOW clauses, TRANSFORM,
 * PIVOT, TABLESAMPLE, time travel, table valued functions, NATURAL joins, CLUSTER/DISTRIBUTE/SORT BY, query hints
 * (hints are ignored).
 */
public class SparkRelationBuilder extends SparkSqlBaseParserBaseVisitor<ParseNode> {
    private final SparkAstBuilder root;
    // CTE names visible while building a query: lower case name -> spelling at the definition. Spark resolves
    // CTE names case-insensitively, the StarRocks analyzer case-sensitively.
    private final Deque<Map<String, String>> cteScopes = new ArrayDeque<>();
    private int generatedAliasId = 0;

    public SparkRelationBuilder(SparkAstBuilder root) {
        this.root = root;
    }

    // ------------------------------------------------ query -----------------------------------------------------

    @Override
    public ParseNode visitQuery(SparkSqlBaseParser.QueryContext ctx) {
        Map<String, String> scope = new HashMap<>();
        cteScopes.push(scope);
        try {
            List<CTERelation> ctes = new ArrayList<>();
            if (ctx.ctes() != null) {
                for (SparkSqlBaseParser.NamedQueryContext namedQuery : ctx.ctes().namedQuery()) {
                    ctes.add(buildCte(namedQuery, scope));
                }
            }

            QueryRelation relation = root.visit(ctx.queryTerm(), QueryRelation.class);
            SparkSqlBaseParser.QueryOrganizationContext org = ctx.queryOrganization();
            List<OrderByElement> orderBy = new ArrayList<>();
            if (!org.order.isEmpty()) {
                for (SparkSqlBaseParser.SortItemContext item : org.order) {
                    orderBy.add(root.visit(item, OrderByElement.class));
                }
            }
            if (!org.clusterBy.isEmpty() || !org.distributeBy.isEmpty() || !org.sort.isEmpty()) {
                throw unsupported("CLUSTER BY / DISTRIBUTE BY / SORT BY");
            }
            if (org.windowClause() != null) {
                throw unsupported("named WINDOW clause");
            }
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
            cteScopes.pop();
        }
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
        return "__spark_subquery_" + generatedAliasId++;
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
        if (!ctx.lateralView().isEmpty()) {
            throw unsupported("LATERAL VIEW");
        }
        if (ctx.windowClause() != null) {
            throw unsupported("named WINDOW clause");
        }
        SparkSqlBaseParser.SelectClauseContext select = ctx.selectClause();
        // query hints (/*+ ... */) only influence performance and are ignored
        boolean distinct = select.setQuantifier() != null && select.setQuantifier().DISTINCT() != null;
        List<SelectListItem> items = new ArrayList<>();
        for (SparkSqlBaseParser.NamedExpressionContext named : select.namedExpressionSeq().namedExpression()) {
            items.add(buildSelectItem(named));
        }

        Relation from = ctx.fromClause() == null ? ValuesRelation.newDualRelation() : buildFrom(ctx.fromClause());
        Expr where = ctx.whereClause() == null ? null
                : root.visit(ctx.whereClause().booleanExpression(), Expr.class);
        GroupByClause groupBy = ctx.aggregationClause() == null ? null : buildGroupBy(ctx.aggregationClause());
        Expr having = ctx.havingClause() == null ? null
                : root.visit(ctx.havingClause().booleanExpression(), Expr.class);
        return new SelectRelation(new SelectList(items, distinct), from, where, groupBy, having, pos(ctx));
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
        if (!ctx.lateralView().isEmpty()) {
            throw unsupported("LATERAL VIEW");
        }
        if (ctx.pivotClause() != null || ctx.unpivotClause() != null) {
            throw unsupported("PIVOT/UNPIVOT");
        }
        Relation result = null;
        for (SparkSqlBaseParser.RelationContext relation : ctx.relation()) {
            Relation next = root.visit(relation, Relation.class);
            result = result == null ? next : new JoinRelation(null, result, next, null, false);
        }
        return result;
    }

    @Override
    public ParseNode visitRelation(SparkSqlBaseParser.RelationContext ctx) {
        if (ctx.LATERAL() != null) {
            throw unsupported("LATERAL");
        }
        Relation left = root.visit(ctx.relationPrimary(), Relation.class);
        for (SparkSqlBaseParser.RelationExtensionContext extension : ctx.relationExtension()) {
            if (extension.joinRelation() == null) {
                throw unsupported("PIVOT/UNPIVOT");
            }
            left = buildJoin(left, extension.joinRelation());
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
        if (ctx.sample() != null) {
            throw unsupported("TABLESAMPLE");
        }
        SparkSqlBaseParser.TableAliasContext aliasCtx = ctx.tableAlias();
        if (aliasCtx.identifierList() != null) {
            throw unsupported("column aliases of a table");
        }
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
        if (aliasCtx.strictIdentifier() != null) {
            relation.setAlias(new TableName(null, SparkParserUtils.identifier(aliasCtx.strictIdentifier())));
        }
        return relation;
    }

    @Override
    public ParseNode visitAliasedQuery(SparkSqlBaseParser.AliasedQueryContext ctx) {
        if (ctx.sample() != null) {
            throw unsupported("TABLESAMPLE");
        }
        QueryRelation query = root.visit(ctx.query(), QueryRelation.class);
        SubqueryRelation subquery = new SubqueryRelation(new QueryStatement(query));
        SparkSqlBaseParser.TableAliasContext aliasCtx = ctx.tableAlias();
        String alias = aliasCtx.strictIdentifier() == null ? nextGeneratedAlias()
                : SparkParserUtils.identifier(aliasCtx.strictIdentifier());
        subquery.setAlias(new TableName(null, alias));
        if (aliasCtx.identifierList() != null) {
            subquery.setColumnOutputNames(SparkParserUtils.identifierSeq(aliasCtx.identifierList()));
        }
        return subquery;
    }

    @Override
    public ParseNode visitAliasedRelation(SparkSqlBaseParser.AliasedRelationContext ctx) {
        if (ctx.sample() != null) {
            throw unsupported("TABLESAMPLE");
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
