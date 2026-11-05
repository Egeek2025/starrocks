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

import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.ast.JoinOperator;
import com.starrocks.sql.ast.JoinRelation;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.TableFunctionRelation;
import com.starrocks.sql.ast.expression.ArithmeticExpr;
import com.starrocks.sql.ast.expression.CompoundPredicate;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.LikePredicate;
import com.starrocks.sql.ast.expression.StringLiteral;
import com.starrocks.sql.ast.expression.TimestampArithmeticExpr;
import com.starrocks.sql.common.StarRocksPlannerException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Spark specific query constructs: LATERAL VIEW and generators, named windows, SORT BY / CLUSTER BY, intervals,
 * pivot, table sample, column alias lists, hints, constructors, LIKE variants.
 */
public class SparkQueryExtTest extends SparkTestBase {
    @BeforeAll
    public static void beforeClass() throws Exception {
        SparkTestBase.beforeClass();
        starRocksAssert.withTable("create table sales(product varchar(20), yr int, amount bigint) "
                + "duplicate key(product) distributed by hash(product) buckets 1 "
                + "properties('replication_num'='1');");
        starRocksAssert.withTable("create table test_struct(c0 int, s struct<a int, b varchar(10)>) "
                + "duplicate key(c0) distributed by hash(c0) buckets 1 properties('replication_num'='1');");
    }

    // ------------------------------------------------ helpers -----------------------------------------------------

    private static SelectRelation selectOf(String sql) {
        StatementBase stmt = parseOne(sql);
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect(), sql);
        QueryRelation relation = ((QueryStatement) stmt).getQueryRelation();
        Assertions.assertTrue(relation instanceof SelectRelation, sql);
        return (SelectRelation) relation;
    }

    private static List<String> columnNames(String sql) {
        StatementBase stmt = analyzeSuccess(sql);
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect(), sql);
        return ((QueryStatement) stmt).getQueryRelation().getColumnOutputNames();
    }

    private static Expr firstItem(String sql) {
        return selectOf(sql).getSelectList().getItems().get(0).getExpr();
    }

    /**
     * The statement is rejected by the Spark parser (and would fall back to the StarRocks parser).
     */
    private static void assertUnsupported(String sql, String feature) {
        connectContext.getSessionVariable().setEnableDialectDowngrade(false);
        try {
            parseOne(sql);
            Assertions.fail("Expected SparkUnsupportedException for: " + sql);
        } catch (StarRocksPlannerException e) {
            Assertions.assertTrue(e instanceof SparkUnsupportedException, e.getMessage());
            Assertions.assertTrue(e.getMessage().contains(feature), e.getMessage());
        } finally {
            connectContext.getSessionVariable().setEnableDialectDowngrade(true);
        }
    }

    // --------------------------------------------- lateral view ---------------------------------------------------

    @Test
    public void testLateralViewExplodeArray() throws Exception {
        Assertions.assertEquals(List.of("c0", "x"),
                columnNames("select c0, x from test_array lateral view explode(c2) tf as x"));
        // AS is optional
        Assertions.assertEquals(List.of("c0", "x"),
                columnNames("select c0, x from test_array lateral view explode(c2) tf x"));
        // Spark names the column of explode(array) col
        Assertions.assertEquals(List.of("col"), columnNames("select col from test_array lateral view explode(c2) tf"));
        Assertions.assertEquals(List.of("col"),
                columnNames("select tf.col from test_array lateral view explode(c2) tf"));
        assertPlanContains("select c0, x from test_array lateral view explode(c2) tf as x", "TableValueFunction");
        assertPlanContains("select c0, x from test_array lateral view explode(c2) tf as x where x > 1",
                "TableValueFunction");
    }

    @Test
    public void testLateralViewStructure() {
        // inner lateral view: cross join of the table and the table function
        JoinRelation inner = (JoinRelation) selectOf(
                "select x from test_array lateral view explode(c2) tf as x").getRelation();
        Assertions.assertEquals(JoinOperator.CROSS_JOIN, inner.getJoinOp());
        Assertions.assertTrue(inner.isLateral());
        Assertions.assertTrue(inner.getRight() instanceof TableFunctionRelation);
        Assertions.assertEquals("unnest", ((TableFunctionRelation) inner.getRight()).getFunctionName().getFunction());
        Assertions.assertEquals("tf", inner.getRight().getAlias().getTbl());

        // OUTER and the _outer generators: left join on true
        for (String sql : new String[] {
                "select x from test_array lateral view outer explode(c2) tf as x",
                "select x from test_array lateral view explode_outer(c2) tf as x"}) {
            JoinRelation outer = (JoinRelation) selectOf(sql).getRelation();
            Assertions.assertEquals(JoinOperator.LEFT_OUTER_JOIN, outer.getJoinOp(), sql);
            Assertions.assertTrue(outer.isLateral(), sql);
        }
    }

    @Test
    public void testLateralViewOuter() throws Exception {
        Assertions.assertEquals(List.of("c0", "x"),
                columnNames("select c0, x from test_array lateral view outer explode(c2) tf as x"));
        assertPlanContains("select c0, x from test_array lateral view outer explode(c2) tf as x",
                "TableValueFunction");
        Assertions.assertEquals(List.of("col"),
                columnNames("select tf.col from test_array lateral view explode_outer(c2) tf"));
    }

    @Test
    public void testLateralViewPosexplode() throws Exception {
        Assertions.assertEquals(List.of("p", "v"),
                columnNames("select p, v from test_array lateral view posexplode(c2) tf as p, v"));
        Assertions.assertEquals(List.of("pos", "col"),
                columnNames("select tf.* from test_array lateral view posexplode(c2) tf"));
        // positions are 0-based
        assertPlanContains("select p, v from test_array lateral view posexplode(c2) tf as p, v",
                "TableValueFunction", "array_generate");
        assertPlanContains("select p, v from test_array lateral view posexplode_outer(c2) tf as p, v",
                "TableValueFunction");
    }

    @Test
    public void testLateralViewExplodeMap() throws Exception {
        // StarRocks unnest takes arrays only, the analyzer expands the map to its keys and values
        Assertions.assertEquals(List.of("k", "v"),
                columnNames("select k, v from test_map lateral view explode(c1) tf as k, v"));
        Assertions.assertEquals(List.of("key", "value"),
                columnNames("select tf.* from test_map lateral view explode(c1) tf"));
        Assertions.assertEquals(List.of("p", "k", "v"),
                columnNames("select p, k, v from test_map lateral view posexplode(c1) tf as p, k, v"));
        Assertions.assertEquals(List.of("pos", "key", "value"),
                columnNames("select tf.* from test_map lateral view posexplode(c1) tf"));
        assertPlanContains("select k, v from test_map lateral view explode(c1) tf as k, v",
                "TableValueFunction", "map_keys", "map_values");
        assertPlanContains("select k, v from test_map lateral view outer explode(c1) tf as k, v",
                "TableValueFunction");
        // the array of maps is still an array
        Assertions.assertEquals(List.of("m"),
                columnNames("select m from test_map lateral view explode(c2) tf as m"));
        // a second analysis of the same statement must not expand again
        StatementBase stmt = analyzeSuccess("select k, v from test_map lateral view explode(c1) tf as k, v");
        Analyzer.analyze(stmt, connectContext);
    }

    @Test
    public void testLateralViewChained() throws Exception {
        Assertions.assertEquals(List.of("c0", "a", "b"), columnNames(
                "select c0, a, b from test_array lateral view explode(c1) t1 as a lateral view explode(c2) t2 as b"));
        // a later generator can use the columns of an earlier one, here the elements of an array of maps
        Assertions.assertEquals(List.of("k", "v"), columnNames(
                "select k, v from test_map lateral view explode(c2) t1 as m lateral view explode(m) t2 as k, v"));
        assertPlanContains("select c0, a, b from test_array lateral view explode(c1) t1 as a "
                + "lateral view outer explode(c2) t2 as b", "TableValueFunction");
    }

    @Test
    public void testLateralViewJsonTuple() throws Exception {
        Assertions.assertEquals(List.of("ta", "a", "b"),
                columnNames("select ta, a, b from tall lateral view json_tuple(ta, 'x', 'y') j as a, b"));
        Assertions.assertEquals(List.of("c0", "c1"),
                columnNames("select j.* from tall lateral view json_tuple(ta, 'x', 'y') j"));
        assertPlanContains("select a from tall lateral view json_tuple(ta, 'x') j as a",
                "TableValueFunction", "get_json_string");
        assertUnsupported("select a from tall lateral view json_tuple(ta, tb) j as a", "json_tuple");
    }

    @Test
    public void testLateralViewStack() throws Exception {
        Assertions.assertEquals(List.of("k", "v"),
                columnNames("select k, v from t0 lateral view stack(2, 'a', 1, 'b', 2) s as k, v"));
        Assertions.assertEquals(List.of("col0", "col1"),
                columnNames("select s.* from t0 lateral view stack(2, 'a', 1, 'b', 2) s"));
        // the last row is padded with NULL
        Assertions.assertEquals(List.of("col0", "col1"),
                columnNames("select s.* from t0 lateral view stack(2, 1, 2, 3) s"));
        assertPlanContains("select k, v from t0 lateral view stack(2, 'a', 1, 'b', 2) s as k, v",
                "TableValueFunction");
        assertUnsupported("select * from t0 lateral view stack(v1, 'a') s", "stack");
    }

    @Test
    public void testLateralViewUnsupported() {
        assertUnsupported("select * from test_array lateral view inline(c2) t as a", "generator inline");
        assertUnsupported("select * from test_array lateral view my_udtf(c2) t as a", "generator my_udtf");
    }

    // ---------------------------------------- generator in SELECT list --------------------------------------------

    @Test
    public void testGeneratorInSelectList() throws Exception {
        Assertions.assertEquals(List.of("c0", "x"), columnNames("select c0, explode(c2) as x from test_array"));
        Assertions.assertEquals(List.of("x", "c0"), columnNames("select explode(c2) x, c0 from test_array"));
        Assertions.assertEquals(List.of("col"), columnNames("select explode(c2) from test_array"));
        Assertions.assertEquals(List.of("key", "value"), columnNames("select explode(c1) from test_map"));
        Assertions.assertEquals(List.of("k", "v"), columnNames("select explode(c1) as (k, v) from test_map"));
        Assertions.assertEquals(List.of("c0", "p", "v"),
                columnNames("select c0, posexplode(c2) as (p, v) from test_array"));
        Assertions.assertEquals(List.of("pos", "col"), columnNames("select posexplode(c2) from test_array"));
        Assertions.assertEquals(List.of("a", "b"), columnNames("select json_tuple(ta, 'x', 'y') as (a, b) from tall"));
        assertPlanContains("select c0, explode(c2) as x from test_array", "TableValueFunction");
        assertPlanContains("select c0, explode_outer(c2) as x from test_array where c0 > 1", "TableValueFunction");
        assertPlanContains("select explode(array(1, 2, 3)) as x", "TableValueFunction");
    }

    @Test
    public void testGeneratorInSelectListIsLateralJoin() {
        JoinRelation join = (JoinRelation) selectOf("select c0, explode(c2) as x from test_array").getRelation();
        Assertions.assertEquals(JoinOperator.CROSS_JOIN, join.getJoinOp());
        Assertions.assertTrue(join.getRight() instanceof TableFunctionRelation);
        join = (JoinRelation) selectOf("select c0, explode_outer(c2) as x from test_array").getRelation();
        Assertions.assertEquals(JoinOperator.LEFT_OUTER_JOIN, join.getJoinOp());
    }

    @Test
    public void testGeneratorInSelectListUnsupported() {
        assertUnsupported("select explode(c2), explode(c2) from test_array", "more than one generator");
        assertUnsupported("select *, explode(c2) from test_array", "together with a generator");
    }

    // ------------------------------------------------- windows -------------------------------------------------

    @Test
    public void testNamedWindow() throws Exception {
        assertPlanContains("select sum(v3) over w from t0 window w as (partition by v1 order by v2)", "ANALYTIC");
        assertPlanContains("select sum(v3) over (w) from t0 window w as (partition by v1 order by v2)", "ANALYTIC");
        // several windows and several uses
        assertPlanContains("select sum(v3) over w1, rank() over w2, avg(v3) over w1 from t0 "
                + "window w1 as (partition by v1), w2 as (order by v2 desc)", "ANALYTIC");
        // a window defined as another window, with a frame
        assertPlanContains("select sum(v3) over w2 from t0 window w1 as (partition by v1 order by v2 "
                        + "rows between unbounded preceding and current row), w2 as w1",
                "ANALYTIC", "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW");
        // window names are case-insensitive
        assertPlanContains("select sum(v3) over W from t0 window w as (partition by v1)", "ANALYTIC");
        // the window clause of a subquery
        assertPlanContains("select * from (select sum(v3) over w as s from t0 window w as (partition by v2)) x",
                "ANALYTIC");
    }

    // ------------------------------------------ sort by / cluster by ----------------------------------------------

    @Test
    public void testSortByClusterByDistributeBy() throws Exception {
        // the outermost query orders
        SelectRelation select = selectOf("select v1, v2 from t0 sort by v2 desc");
        Assertions.assertEquals(1, select.getOrderBy().size());
        Assertions.assertFalse(select.getOrderBy().get(0).getIsAsc());
        select = selectOf("select v1, v2 from t0 cluster by v1, v2");
        Assertions.assertEquals(2, select.getOrderBy().size());
        Assertions.assertTrue(select.getOrderBy().get(0).getIsAsc());
        select = selectOf("select v1, v2 from t0 distribute by v1 sort by v2");
        Assertions.assertEquals(1, select.getOrderBy().size());
        // DISTRIBUTE BY alone does not order
        select = selectOf("select v1, v2 from t0 distribute by v1");
        Assertions.assertFalse(select.hasOrderByClause());
        assertPlanContains("select v1, v2 from t0 sort by v2", "SORT");
        assertPlanContains("select v1, v2 from t0 cluster by v1 limit 3", "TOP-N");

        // subqueries and CTEs drop them
        assertPlanContains("select x.v1 from (select v1 from t0 distribute by v1 sort by v1) x", "OUTPUT EXPRS");
        assertPlanContains("with c as (select v1 from t0 cluster by v1) select v1 from c", "OUTPUT EXPRS");
        assertPlanNotContains("select x.v1 from (select v1 from t0 sort by v1) x", "SORT");
        assertPlanNotContains("select v1 from t0 where v2 in (select v5 from t1 sort by v5)", "SORT");
    }

    @Test
    public void testSortByCombinationsAreUnsupported() {
        assertUnsupported("select v1 from t0 order by v1 sort by v2", "combination of ORDER BY");
        assertUnsupported("select v1 from t0 cluster by v1 sort by v2", "combination of CLUSTER BY");
    }

    // ------------------------------------------------ intervals ------------------------------------------------

    private static void assertInterval(Expr expr, ArithmeticExpr.Operator op, String unit) {
        Assertions.assertTrue(expr instanceof TimestampArithmeticExpr, expr.getClass().getName());
        TimestampArithmeticExpr arithmetic = (TimestampArithmeticExpr) expr;
        Assertions.assertEquals(op, arithmetic.getOp());
        Assertions.assertEquals(unit, arithmetic.getTimeUnitIdent());
    }

    @Test
    public void testIntervalLiterals() throws Exception {
        assertInterval(firstItem("select th + interval 1 day from tall"), ArithmeticExpr.Operator.ADD, "DAY");
        assertInterval(firstItem("select th - interval '1' day from tall"), ArithmeticExpr.Operator.SUBTRACT, "DAY");
        assertInterval(firstItem("select interval 3 hours + th from tall"), ArithmeticExpr.Operator.ADD, "HOUR");
        assertInterval(firstItem("select th + interval 2 weeks from tall"), ArithmeticExpr.Operator.ADD, "WEEK");
        assertInterval(firstItem("select th + interval 1 month from tall"), ArithmeticExpr.Operator.ADD, "MONTH");
        assertInterval(firstItem("select th - interval 2 years from tall"), ArithmeticExpr.Operator.SUBTRACT,
                "YEAR");
        assertInterval(firstItem("select th + interval 5 minutes from tall"), ArithmeticExpr.Operator.ADD, "MINUTE");
        assertInterval(firstItem("select th + interval 5 seconds from tall"), ArithmeticExpr.Operator.ADD, "SECOND");
        // fractional seconds and milliseconds are microseconds
        assertInterval(firstItem("select th + interval 1.5 seconds from tall"), ArithmeticExpr.Operator.ADD,
                "MICROSECOND");
        assertInterval(firstItem("select th + interval 20 milliseconds from tall"), ArithmeticExpr.Operator.ADD,
                "MICROSECOND");
        assertInterval(firstItem("select th + interval -1 day from tall"), ArithmeticExpr.Operator.ADD, "DAY");

        assertPlanContains("select th + interval 1 day from tall", "days_add");
        assertPlanContains("select th - interval 1 day from tall", "days_sub");
        assertPlanContains("select ta from tall where th > current_timestamp - interval 7 days", "PREDICATES");
        assertPlanContains("select th + interval 1 month from tall", "months_add");
    }

    @Test
    public void testMultiUnitIntervals() throws Exception {
        // INTERVAL 1 DAY 2 HOURS is the chain (x + 1 day) + 2 hours
        Expr expr = firstItem("select th + interval 1 day 2 hours from tall");
        assertInterval(expr, ArithmeticExpr.Operator.ADD, "HOUR");
        assertInterval(expr.getChild(0), ArithmeticExpr.Operator.ADD, "DAY");
        expr = firstItem("select th - interval '1' day '2' hour from tall");
        assertInterval(expr, ArithmeticExpr.Operator.SUBTRACT, "HOUR");
        assertInterval(expr.getChild(0), ArithmeticExpr.Operator.SUBTRACT, "DAY");
        assertPlanContains("select th + interval 1 day 2 hours 3 minutes from tall", "minutes_add", "hours_add",
                "days_add");
    }

    @Test
    public void testUnitToUnitIntervals() throws Exception {
        Expr expr = firstItem("select th + interval '1-2' year to month from tall");
        assertInterval(expr, ArithmeticExpr.Operator.ADD, "MONTH");
        assertInterval(expr.getChild(0), ArithmeticExpr.Operator.ADD, "YEAR");
        expr = firstItem("select th - interval '-1-2' year to month from tall");
        // the sign applies to both fields
        assertInterval(expr, ArithmeticExpr.Operator.SUBTRACT, "MONTH");
        Assertions.assertEquals(-2, ((IntLiteral) expr.getChild(1)).getValue());
        expr = firstItem("select th + interval '1 02:03:04' day to second from tall");
        assertInterval(expr, ArithmeticExpr.Operator.ADD, "SECOND");
        assertInterval(expr.getChild(0), ArithmeticExpr.Operator.ADD, "MINUTE");
        expr = firstItem("select th + interval '1 02:03:04.5' day to second from tall");
        assertInterval(expr, ArithmeticExpr.Operator.ADD, "MICROSECOND");
        assertInterval(firstItem("select th + interval '10:20' hour to minute from tall"),
                ArithmeticExpr.Operator.ADD, "MINUTE");
        assertInterval(firstItem("select th + interval '10:20' minute to second from tall"),
                ArithmeticExpr.Operator.ADD, "SECOND");
        assertPlanContains("select th + interval '1-2' year to month from tall", "years_add", "months_add");
    }

    @Test
    public void testStandaloneIntervalIsUnsupported() {
        assertUnsupported("select interval 1 day", "INTERVAL");
        assertUnsupported("select abs(interval 1 day)", "INTERVAL");
        assertUnsupported("select interval 1 day * 2", "INTERVAL");
        assertUnsupported("select interval 1 day - th from tall", "INTERVAL");
        assertUnsupported("select interval 1 day + interval 2 day", "INTERVAL");
    }

    // ---------------------------------------------- cast, like, ... ----------------------------------------------

    @Test
    public void testTryCast() throws Exception {
        // a failing CAST gives NULL in StarRocks, which is what TRY_CAST asks for
        assertPlanContains("select try_cast(ta as int) from tall", "CAST");
        assertPlanContains("select try_cast(ta as double), try_cast(ta as date) from tall", "CAST");
        Assertions.assertEquals(List.of("v"), columnNames("select try_cast(ta as bigint) as v from tall"));
    }

    @Test
    public void testLikeEscape() throws Exception {
        // the escape character is rewritten to the backslash of StarRocks
        Expr predicate = selectOf("select ta from tall where ta like 'a#%b_#_' escape '#'").getPredicate();
        Assertions.assertTrue(predicate instanceof LikePredicate);
        Assertions.assertEquals("a\\%b_\\_", ((StringLiteral) predicate.getChild(1)).getValue());
        // a literal backslash is escaped, because it is the escape character of StarRocks
        predicate = selectOf("select ta from tall where ta like 'a\\\\b#%' escape '#'").getPredicate();
        Assertions.assertEquals("a\\\\b\\%", ((StringLiteral) predicate.getChild(1)).getValue());
        assertPlanContains("select ta from tall where ta not like 'x!_y' escape '!'", "NOT", "LIKE");
        assertUnsupported("select ta from tall where ta like ta escape '#'", "ESCAPE");
    }

    @Test
    public void testLikeAnyAll() throws Exception {
        Expr predicate = selectOf("select ta from tall where ta like any ('a%', 'b%', 'c%')").getPredicate();
        Assertions.assertTrue(predicate instanceof CompoundPredicate);
        Assertions.assertEquals(CompoundPredicate.Operator.OR, ((CompoundPredicate) predicate).getOp());
        predicate = selectOf("select ta from tall where ta like some ('a%', 'b%')").getPredicate();
        Assertions.assertEquals(CompoundPredicate.Operator.OR, ((CompoundPredicate) predicate).getOp());
        predicate = selectOf("select ta from tall where ta like all ('a%', '%b')").getPredicate();
        Assertions.assertEquals(CompoundPredicate.Operator.AND, ((CompoundPredicate) predicate).getOp());
        predicate = selectOf("select ta from tall where ta not like any ('a%', 'b%')").getPredicate();
        Assertions.assertEquals(CompoundPredicate.Operator.NOT, ((CompoundPredicate) predicate).getOp());
        assertPlanContains("select ta from tall where ta like any ('a%', 'b%')", "LIKE", "OR");
        assertPlanContains("select ta from tall where ta like all ('a%', '%b')", "LIKE");
        assertPlanContains("select ta from tall where ta ilike any ('a%', 'b%')", "lower");
    }

    // ----------------------------------------------- constructors ------------------------------------------------

    @Test
    public void testConstructors() throws Exception {
        assertPlanContains("select array(1, 2, 3), array('a'), array() from t0", "OUTPUT EXPRS");
        assertPlanContains("select map('a', 1, 'b', 2), map() from t0", "OUTPUT EXPRS");
        assertPlanContains("select named_struct('a', v1, 'b', v2) from t0", "OUTPUT EXPRS");
        assertPlanContains("select struct(v1, v2), struct(1, 'x') from t0", "OUTPUT EXPRS");
        assertPlanContains("select map_from_arrays(array(1, 2), array('a', 'b')) from t0", "OUTPUT EXPRS");
        assertPlanContains("select array(v1, v2)[0], map('a', v1)['a'] from t0", "OUTPUT EXPRS");
    }

    @Test
    public void testStructFieldNames() {
        // fields are named after the alias or the column, otherwise colN
        FunctionCallExpr struct = (FunctionCallExpr) firstItem("select struct(v1, v2 as b, v3 + 1, t0.v3) from t0");
        Assertions.assertEquals("named_struct", struct.getFunctionName());
        Assertions.assertEquals("v1", ((StringLiteral) struct.getChild(0)).getValue());
        Assertions.assertEquals("b", ((StringLiteral) struct.getChild(2)).getValue());
        Assertions.assertEquals("col3", ((StringLiteral) struct.getChild(4)).getValue());
        Assertions.assertEquals("v3", ((StringLiteral) struct.getChild(6)).getValue());
        assertUnsupported("select struct() from t0", "struct()");
    }

    @Test
    public void testStructFieldAccessAndMapSubscript() throws Exception {
        assertPlanContains("select s.a, test_struct.s.b from test_struct", "OUTPUT EXPRS");
        assertPlanContains("select c1[1], c3['k'] from test_map", "OUTPUT EXPRS");
    }

    // ------------------------------------------ relation alias lists ---------------------------------------------

    @Test
    public void testColumnAliasListsOnRelations() throws Exception {
        Assertions.assertEquals(List.of("a", "c"), columnNames("select x.a, x.c from t0 as x(a, b, c)"));
        Assertions.assertEquals(List.of("a", "b", "c"), columnNames("select * from t0 x(a, b, c)"));
        Assertions.assertEquals(List.of("a"), columnNames("select a from (select v1 from t0) as x(a)"));
        Assertions.assertEquals(List.of("id", "name"),
                columnNames("select * from (values (1, 'a'), (2, 'b')) as v(id, name)"));
        Assertions.assertEquals(List.of("id", "name"), columnNames("select * from values (1, 'a') as v(id, name)"));
        assertPlanContains("select x.a from t0 x(a, b, c) where x.b > 1", "PREDICATES");
        assertPlanContains("select * from t0 x(a, b, c) join t1 y(d, e, f) on x.a = y.d", "JOIN");
        analyzeFail("select * from t0 x(a, b)", "different column counts");
    }

    // ------------------------------------------------- pivot -----------------------------------------------------

    @Test
    public void testPivot() throws Exception {
        Assertions.assertEquals(List.of("product", "2020", "2021"),
                columnNames("select * from sales pivot (sum(amount) for yr in (2020, 2021))"));
        Assertions.assertEquals(List.of("product", "a", "b"),
                columnNames("select * from sales pivot (sum(amount) as s for yr in (2020 as a, 2021 b))"));
        Assertions.assertEquals(List.of("product", "a_s", "a_c", "b_s", "b_c"),
                columnNames("select * from sales pivot (sum(amount) as s, count(amount) as c "
                        + "for yr in (2020 as a, 2021 as b))"));
        assertPlanContains("select * from sales pivot (sum(amount) for yr in (2020, 2021))", "AGGREGATE");
        Assertions.assertEquals(List.of("yr", "x", "y"),
                columnNames("select * from sales pivot (sum(amount) for product in ('x', 'y'))"));
        analyzeSuccess("select * from (select product, yr, amount from sales) pivot (sum(amount) for yr in (2020))");
    }

    @Test
    public void testUnpivotAndNaturalJoinAreUnsupported() {
        assertUnsupported("select * from sales unpivot (amount for k in (yr))", "UNPIVOT");
        assertUnsupported("select * from t0 natural join t1", "NATURAL JOIN");
    }

    // ----------------------------------------------- table sample ---------------------------------------------

    @Test
    public void testTableSample() throws Exception {
        assertPlanContains("select v1 from t0 tablesample (5 rows)", "limit: 5");
        assertPlanContains("select x.v1 from t0 tablesample (5 rows) as x", "limit: 5");
        assertPlanContains("select t0.v1 from t0 tablesample (5 rows) where v2 > 1", "limit: 5");
        assertPlanContains("select x.v1 from (select v1 from t0) tablesample (3 rows) x", "limit: 3");
        Assertions.assertEquals(List.of("a"), columnNames("select a from t0 tablesample (5 rows) x(a, b, c)"));
        assertUnsupported("select * from t0 tablesample (10 percent)", "TABLESAMPLE");
        assertUnsupported("select * from t0 tablesample (bucket 1 out of 4)", "TABLESAMPLE");
    }

    // ------------------------------------------------- hints ---------------------------------------------------

    @Test
    public void testJoinHints() throws Exception {
        JoinRelation join = (JoinRelation) selectOf(
                "select /*+ BROADCAST(t1) */ * from t0 join t1 on v1 = v4").getRelation();
        Assertions.assertEquals("BROADCAST", join.getJoinHint());
        join = (JoinRelation) selectOf("select /*+ BROADCASTJOIN(b) */ * from t0 a join t1 b on v1 = v4")
                .getRelation();
        Assertions.assertEquals("BROADCAST", join.getJoinHint());
        join = (JoinRelation) selectOf("select /*+ MAPJOIN(t1) */ * from t0 join t1 on v1 = v4").getRelation();
        Assertions.assertEquals("BROADCAST", join.getJoinHint());
        join = (JoinRelation) selectOf("select /*+ SHUFFLE_HASH(t1) */ * from t0 join t1 on v1 = v4").getRelation();
        Assertions.assertEquals("SHUFFLE", join.getJoinHint());
        assertPlanContains("select /*+ BROADCAST(t1) */ * from t0 join t1 on v1 = v4", "BROADCAST");
        assertPlanContains("select /*+ SHUFFLE_HASH(t1) */ * from t0 join t1 on v1 = v4", "PARTITIONED");

        // hints that only change performance, or cannot be applied, are ignored
        for (String hint : new String[] {"MERGE(t1)", "REPARTITION(3)", "COALESCE(3)", "BROADCAST(t0)",
                "BROADCAST(unknown)", "SHUFFLE_HASH(t1), BROADCAST(t1)", "REBALANCE"}) {
            analyzeSuccess("select /*+ " + hint + " */ * from t0 join t1 on v1 = v4");
        }
        join = (JoinRelation) selectOf("select /*+ MERGE(t1) */ * from t0 join t1 on v1 = v4").getRelation();
        Assertions.assertEquals("", join.getJoinHint());
        // a broadcast of the preserved side is not possible
        analyzeSuccess("select /*+ BROADCAST(t1) */ * from t0 right join t1 on v1 = v4");
        join = (JoinRelation) selectOf("select /*+ BROADCAST(t1) */ * from t0 right join t1 on v1 = v4")
                .getRelation();
        Assertions.assertEquals("", join.getJoinHint());
    }
}
