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
import com.starrocks.sql.analyzer.AstToSQLBuilder;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.SqlParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

public class SparkParserBasicTest extends SparkTestBase {
    @BeforeAll
    public static void beforeClass() throws Exception {
        SparkTestBase.beforeClass();
    }

    @Test
    public void testParsedDialectIsMarked() {
        StatementBase stmt = parseOne("select v1 from t0");
        Assertions.assertTrue(stmt instanceof QueryStatement);
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect());
        // parsing must not change the session
        Assertions.assertFalse(connectContext.isRelationAliasCaseInsensitive());
    }

    @Test
    public void testSelectProjection() throws Exception {
        assertPlanContains("select * from t0", "OUTPUT EXPRS:1: v1 | 2: v2 | 3: v3");
        assertPlanContains("select t0.* from t0", "OUTPUT EXPRS:1: v1 | 2: v2 | 3: v3");
        assertPlanContains("select v1 as a, v2 + 1 as b from t0", "OUTPUT EXPRS:1: v1 | 4: expr");
        assertPlanContains("select test.t0.v1 from test.t0", "OUTPUT EXPRS:1: v1");
        assertPlanContains("select `v1` from `t0`", "OUTPUT EXPRS:1: v1");
        assertPlanContains("select 1 + 2", "OUTPUT EXPRS");
    }

    @Test
    public void testWhereAndPredicates() throws Exception {
        assertPlanContains("select v1 from t0 where v2 = 1 and v3 <> 2", "PREDICATES");
        assertPlanContains("select v1 from t0 where v2 = 1 or v3 != 2", "PREDICATES");
        assertPlanContains("select v1 from t0 where not (v2 = 1)", "PREDICATES");
        assertPlanContains("select v1 from t0 where v2 is null", "PREDICATES: 2: v2 IS NULL");
        assertPlanContains("select v1 from t0 where v2 is not null", "PREDICATES: 2: v2 IS NOT NULL");
        assertPlanContains("select v1 from t0 where v2 in (1, 2, 3)", "PREDICATES: 2: v2 IN (1, 2, 3)");
        assertPlanContains("select v1 from t0 where v2 not in (1, 2, 3)", "NOT IN");
        assertPlanContains("select v1 from t0 where v2 between 1 and 10", "PREDICATES");
        assertPlanContains("select v1 from t0 where v2 not between 1 and 10", "PREDICATES");
        assertPlanContains("select ta from tall where ta like 'a%'", "LIKE");
        assertPlanContains("select ta from tall where ta rlike '^a.*'", "REGEXP");
        assertPlanContains("select ta from tall where ta not like 'a%'", "NOT");
        assertPlanContains("select ta from tall where ta ilike 'A%'", "lower");
        assertPlanContains("select v1 from t0 where v2 <=> 1", "PREDICATES");
        assertPlanContains("select v1 from t0 where v1 is distinct from v2", "PREDICATES");
    }

    @Test
    public void testLiterals() throws Exception {
        assertPlanContains("select 1, 2L, 3S, 4Y, 1.5, 1.5BD, 1e3, 1.5D, 1.5F, 'abc', true, null, \"dq\"",
                "OUTPUT EXPRS");
        assertPlanContains("select date '2020-01-02', timestamp '2020-01-02 03:04:05'", "OUTPUT EXPRS");
        assertPlanContains("select v1 from t0 where v2 = -5", "PREDICATES");
        // adjacent literals are concatenated and backslash escapes are resolved
        StatementBase stmt = analyzeSuccess("select 'a' 'b', 'x\\ty'");
        String sql = AstToSQLBuilder.toSQL(stmt);
        Assertions.assertTrue(sql.contains("'ab'"), sql);
        Assertions.assertTrue(sql.contains("x\ty"), sql);
    }

    @Test
    public void testArithmetic() throws Exception {
        assertPlanContains("select v1 + v2, v1 - v2, v1 * v2, v1 / v2, v1 % v2, v1 div v2 from t0", "OUTPUT EXPRS");
        assertPlanContains("select v1 & v2, v1 | v2, v1 ^ v2, ~v1, -v1 from t0", "OUTPUT EXPRS");
        assertPlanContains("select ta || 'x' from tall", "concat");
    }

    @Test
    public void testCaseAndCast() throws Exception {
        assertPlanContains("select case when v1 > 1 then 'a' when v1 > 0 then 'b' else 'c' end from t0",
                "CASE WHEN");
        assertPlanContains("select case v1 when 1 then 'a' else 'b' end from t0", "CASE");
        assertPlanContains("select cast(v1 as string) from t0", "VARCHAR(65533)");
        assertPlanContains("select cast(v1 as int), cast(v1 as long), cast(v1 as double) from t0", "CAST");
        assertPlanContains("select cast(ta as timestamp) from tall", "DATETIME");
        assertPlanContains("select cast(ta as binary) from tall", "VARBINARY");
        assertPlanContains("select cast(v1 as decimal(10, 2)) from t0", "DECIMAL");
        assertPlanContains("select cast(v1 as decimal) from t0", "DECIMAL");
        assertPlanContains("select cast(ta as varchar(10)), cast(ta as char(3)) from tall", "CAST");
        assertPlanContains("select cast(ta as date), cast(v1 as boolean) from tall, t0", "CAST");
    }

    @Test
    public void testAggregation() throws Exception {
        assertPlanContains("select v1, count(v2) from t0 group by v1", "AGGREGATE", "group by: 1: v1");
        assertPlanContains("select count(*) from t0", "count(*)");
        assertPlanContains("select count(distinct v1), sum(v2), min(v3), max(v3), avg(v3) from t0", "AGGREGATE");
        assertPlanContains("select v1, sum(v2) from t0 group by v1 having sum(v2) > 10", "PREDICATES");
        assertPlanContains("select v1 % 5 as k, count(v2) from t0 group by 1", "AGGREGATE");
        assertPlanContains("select v1, count(*) filter (where v2 > 1) from t0 group by v1", "AGGREGATE");
        assertPlanContains("select v1, sum(v2) filter (where v2 > 1) from t0 group by v1", "AGGREGATE");
    }

    @Test
    public void testGroupingAnalytics() throws Exception {
        assertPlanContains("select v1, v2, sum(v3) from t0 group by rollup(v1, v2)", "REPEAT");
        assertPlanContains("select v1, v2, sum(v3) from t0 group by cube(v1, v2)", "REPEAT");
        assertPlanContains("select v1, v2, sum(v3) from t0 group by v1, v2 with rollup", "REPEAT");
        assertPlanContains("select v1, v2, sum(v3) from t0 group by v1, v2 with cube", "REPEAT");
        assertPlanContains("select v1, v2, sum(v3) from t0 group by grouping sets ((v1, v2), (v1), ())", "REPEAT");
        assertPlanContains("select v1, v2, sum(v3), grouping(v1) from t0 group by rollup(v1, v2)", "REPEAT");
    }

    @Test
    public void testOrderByAndLimit() throws Exception {
        assertPlanContains("select v1 from t0 order by v1 desc nulls last limit 10", "TOP-N", "limit: 10");
        assertPlanContains("select v1 from t0 order by v1 asc nulls first, v2 desc", "SORT");
        assertPlanContains("select v1 from t0 order by v1 limit 10 offset 5", "offset: 5");
        assertPlanContains("select v1 from t0 limit 3", "limit: 3");
        assertPlanContains("select v1 from t0 limit all", "OUTPUT EXPRS");
    }

    @Test
    public void testJoins() throws Exception {
        assertPlanContains("select * from t0 join t1 on t0.v1 = t1.v4", "INNER JOIN", "HASH JOIN");
        assertPlanContains("select * from t0 inner join t1 on v1 = v4", "INNER JOIN");
        assertPlanContains("select * from t0 left join t1 on v1 = v4", "LEFT OUTER JOIN");
        assertPlanContains("select * from t0 left outer join t1 on v1 = v4", "LEFT OUTER JOIN");
        assertPlanContains("select * from t0 right join t1 on v1 = v4", "RIGHT OUTER JOIN");
        assertPlanContains("select * from t0 full outer join t1 on v1 = v4", "FULL OUTER JOIN");
        assertPlanContains("select * from t0 cross join t1", "CROSS JOIN");
        assertPlanContains("select * from t0, t1", "CROSS JOIN");
        assertPlanContains("select * from t0 left semi join t1 on v1 = v4", "LEFT SEMI JOIN");
        assertPlanContains("select * from t0 semi join t1 on v1 = v4", "LEFT SEMI JOIN");
        assertPlanContains("select * from t0 left anti join t1 on v1 = v4", "LEFT ANTI JOIN");
        assertPlanContains("select * from t0 anti join t1 on v1 = v4", "LEFT ANTI JOIN");
        assertPlanContains("select * from t0 a join t1 b on a.v1 = b.v4 join t0 c on b.v4 = c.v1", "JOIN");
        assertPlanContains("select * from t0 join (select v1 as v1 from t0) t on t0.v1 = t.v1", "JOIN");
    }

    @Test
    public void testJoinUsing() throws Exception {
        starRocksAssert.withTable("create table tu1(k bigint, a bigint) duplicate key(k) " +
                "distributed by hash(k) buckets 1 properties('replication_num'='1');");
        starRocksAssert.withTable("create table tu2(k bigint, b bigint) duplicate key(k) " +
                "distributed by hash(k) buckets 1 properties('replication_num'='1');");
        assertPlanContains("select * from tu1 join tu2 using (k)", "JOIN");
    }

    @Test
    public void testAliasesAndSubqueries() throws Exception {
        assertPlanContains("select a.v1 from t0 as a", "OUTPUT EXPRS:1: v1");
        assertPlanContains("select a.v1 from t0 a", "OUTPUT EXPRS:1: v1");
        assertPlanContains("select x.c from (select v1 as c from t0) x", "OUTPUT EXPRS");
        assertPlanContains("select x.c from (select v1 from t0) as x(c)", "OUTPUT EXPRS");
        // Spark accepts a subquery without alias
        assertPlanContains("select c from (select v1 as c from t0)", "OUTPUT EXPRS");
        assertPlanContains("select * from (values (1, 'a'), (2, 'b')) as t(id, name)", "OUTPUT EXPRS");
        assertPlanContains("select * from values (1, 'a'), (2, 'b') as t(id, name)", "OUTPUT EXPRS");
    }

    @Test
    public void testRelationAliasIsCaseInsensitive() throws Exception {
        // the alias is declared as "A" and referenced as "a" and "A"
        assertPlanContains("select a.v1, A.v2 from t0 A", "OUTPUT EXPRS:1: v1 | 2: v2");
        assertPlanContains("select t.C from (select v1 as c from t0) T", "OUTPUT EXPRS");
        // analyzing must restore the session flag
        Assertions.assertFalse(connectContext.isRelationAliasCaseInsensitive());
    }

    @Test
    public void testSubqueryExpressions() throws Exception {
        assertPlanContains("select v1 from t0 where v2 in (select v5 from t1)", "LEFT SEMI JOIN");
        assertPlanContains("select v1 from t0 where v2 not in (select v5 from t1)", "JOIN");
        assertPlanContains("select v1 from t0 where exists (select 1 from t1 where v4 = v1)", "LEFT SEMI JOIN");
        assertPlanContains("select v1 from t0 where not exists (select 1 from t1 where v4 = v1)", "LEFT ANTI JOIN");
        assertPlanContains("select v1, (select max(v4) from t1) from t0", "OUTPUT EXPRS");
        assertPlanContains("select v1 from t0 where v2 > (select max(v5) from t1)", "JOIN");
    }

    @Test
    public void testWithClause() throws Exception {
        assertPlanContains("with c as (select v1, v2 from t0) select v1 from c", "OUTPUT EXPRS");
        assertPlanContains("with c1 as (select v1 from t0), c2 as (select v1 from c1) select * from c2",
                "OUTPUT EXPRS");
        assertPlanContains("with c (a, b) as (select v1, v2 from t0) select a, b from c", "OUTPUT EXPRS");
        // CTE names are case-insensitive in Spark
        assertPlanContains("with MyCte as (select v1 from t0) select * from mycte", "OUTPUT EXPRS");
        assertPlanContains("with c as (select v1 from t0) select a.v1 from c a join c b on a.v1 = b.v1", "JOIN");
    }

    @Test
    public void testSetOperations() throws Exception {
        assertPlanContains("select v1 from t0 union all select v4 from t1", "UNION");
        assertPlanContains("select v1 from t0 union select v4 from t1", "UNION", "AGGREGATE");
        assertPlanContains("select v1 from t0 union distinct select v4 from t1", "UNION");
        assertPlanContains("select v1 from t0 intersect select v4 from t1", "INTERSECT");
        assertPlanContains("select v1 from t0 except select v4 from t1", "EXCEPT");
        assertPlanContains("select v1 from t0 minus select v4 from t1", "EXCEPT");
        assertPlanContains("select v1 from t0 union all select v4 from t1 union all select v7 from "
                + "(select v1 as v7 from t0) x order by 1 limit 5", "UNION");
        assertPlanContains("(select v1 from t0 limit 2) union all (select v4 from t1 limit 3)", "UNION");
    }

    @Test
    public void testWindowFunctions() throws Exception {
        assertPlanContains("select row_number() over (partition by v1 order by v2) from t0", "ANALYTIC");
        assertPlanContains("select rank() over (order by v2 desc), dense_rank() over (order by v2) from t0",
                "ANALYTIC");
        assertPlanContains("select sum(v3) over (partition by v1 order by v2 rows between unbounded preceding "
                + "and current row) from t0", "ANALYTIC", "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW");
        assertPlanContains("select sum(v3) over (partition by v1 order by v2 rows between 1 preceding "
                + "and 1 following) from t0", "ANALYTIC", "1 PRECEDING");
        assertPlanContains("select sum(v3) over (order by v2 range between unbounded preceding and unbounded "
                + "following) from t0", "ANALYTIC");
        assertPlanContains("select sum(v3) over (partition by v1) from t0", "ANALYTIC");
        assertPlanContains("select lag(v3, 1) over (partition by v1 order by v2) from t0", "ANALYTIC");
        assertPlanContains("select first_value(v3) ignore nulls over (partition by v1 order by v2) from t0",
                "ANALYTIC");
        assertPlanContains("select count(distinct v3) over (partition by v1) from t0", "ANALYTIC");
    }

    @Test
    public void testFunctionsAndSpecialForms() throws Exception {
        assertPlanContains("select abs(v1), coalesce(v1, v2, 0), if(v1 > 1, 1, 2) from t0", "OUTPUT EXPRS");
        assertPlanContains("select substring(ta, 1, 2), substr(ta, 2), trim(ta), ltrim(ta), upper(ta) from tall",
                "OUTPUT EXPRS");
        assertPlanContains("select substring(ta from 1 for 2), trim(both from ta), trim(leading from ta) from tall",
                "OUTPUT EXPRS");
        assertPlanContains("select extract(year from th), year(th), month(th) from tall", "OUTPUT EXPRS");
        assertPlanContains("select position('a' in ta) from tall", "OUTPUT EXPRS");
        assertPlanContains("select current_date, current_timestamp from t0", "OUTPUT EXPRS");
        assertPlanContains("select array_contains(c2, 1) from test_array", "OUTPUT EXPRS");
        assertPlanContains("select transform(c2, x -> x + 1) from test_array", "OUTPUT EXPRS");
    }

    @Test
    public void testSubscriptIsZeroBased() throws Exception {
        // Spark arrays are 0-based: c2[0] is the first element, which StarRocks addresses as c2[1]
        assertPlanContains("select c2[0] from test_array", "c2[1]");
        assertPlanContains("select c2[1] from test_array", "c2[2]");
        assertPlanContains("select c0 from test_array where c2[0] > 10", "c2[1] > 10");
        // non-literal subscripts are shifted too
        assertPlanContains("select c2[c0] from test_array", "c0 + 1");
        // a map is not shifted
        assertPlanContains("select c1[1] from test_map", "c1[1]");
        // analyzing again must not shift again
        StatementBase stmt = analyzeSuccess("select c2[0] from test_array");
        analyzeAgain(stmt);
        String sql = AstToSQLBuilder.toSQL(stmt);
        Assertions.assertTrue(sql.contains("[1]"), sql);
    }

    private static void analyzeAgain(StatementBase stmt) {
        Analyzer.analyze(stmt, connectContext);
    }

    @Test
    public void testExplain() throws Exception {
        StatementBase stmt = parseOne("explain select v1 from t0");
        Assertions.assertTrue(stmt.isExplain());
        Assertions.assertEquals(StatementBase.ExplainLevel.NORMAL, stmt.getExplainLevel());
        Assertions.assertEquals(StatementBase.ExplainLevel.COSTS,
                parseOne("explain cost select v1 from t0").getExplainLevel());
        Assertions.assertEquals(StatementBase.ExplainLevel.LOGICAL,
                parseOne("explain logical select v1 from t0").getExplainLevel());
        Assertions.assertEquals(StatementBase.ExplainLevel.VERBOSE,
                parseOne("explain extended select v1 from t0").getExplainLevel());
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect());
    }

    @Test
    public void testMultipleStatementsAndComments() {
        List<StatementBase> statements = SqlParser.parse(
                "-- comment\nselect v1 from t0; /* block */ select v2 from t0;;", connectContext.getSessionVariable());
        Assertions.assertEquals(2, statements.size());
        statements.forEach(s -> Assertions.assertEquals(ParsedDialect.SPARK, s.getParsedDialect()));
        Assertions.assertEquals(0, statements.get(0).getOrigStmt().idx);
        Assertions.assertEquals(1, statements.get(1).getOrigStmt().idx);
    }

    @Test
    public void testKeywordsAsIdentifiers() throws Exception {
        starRocksAssert.withTable("create table kw(`date` bigint, `end` bigint) duplicate key(`date`) "
                + "distributed by hash(`date`) buckets 1 properties('replication_num'='1');");
        // non-reserved keywords are valid identifiers in Spark, reserved ones need back quotes
        assertPlanContains("select `date`, `end` from kw", "OUTPUT EXPRS");
        assertPlanContains("select a.`date` from kw a", "OUTPUT EXPRS");
    }

    @Test
    public void testUpperCaseKeywordsAndMixedCaseIdentifiers() throws Exception {
        assertPlanContains("SELECT V1, V2 FROM t0 WHERE V3 > 1 ORDER BY V1 LIMIT 5", "OUTPUT EXPRS");
    }
}
