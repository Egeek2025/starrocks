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

import com.starrocks.qe.QueryWarning;
import com.starrocks.qe.SessionVariable;
import com.starrocks.qe.SetExecutor;
import com.starrocks.sql.ast.AnalyzeStmt;
import com.starrocks.sql.ast.DescribeStmt;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.RefreshTableStmt;
import com.starrocks.sql.ast.SetCatalogStmt;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.ShowColumnStmt;
import com.starrocks.sql.ast.ShowCreateTableStmt;
import com.starrocks.sql.ast.ShowDbStmt;
import com.starrocks.sql.ast.ShowTableStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.UseDbStmt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * USE, CACHE, REFRESH, MSCK, ANALYZE, ADD JAR, SHOW and DESCRIBE of the Spark dialect.
 */
public class SparkCommandMiscTest extends SparkTestBase {
    @BeforeAll
    public static void beforeClass() throws Exception {
        SparkTestBase.beforeClass();
    }

    @BeforeEach
    public void clearWarnings() {
        connectContext.clearWarnings();
    }

    @AfterEach
    public void restoreSession() {
        connectContext.getSessionVariable().setSparkDefaultCatalog("default_catalog");
        connectContext.clearWarnings();
    }

    private static <T extends StatementBase> T parse(String sql, Class<T> clazz) {
        StatementBase stmt = parseOne(sql);
        Assertions.assertTrue(clazz.isInstance(stmt), sql + " is " + stmt.getClass());
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect(), sql);
        return clazz.cast(stmt);
    }

    private List<String> executeAndGetWarnings(String sql) throws Exception {
        StatementBase stmt = analyzeSuccess(sql);
        Assertions.assertTrue(stmt instanceof SetStmt, sql);
        new SetExecutor(connectContext, (SetStmt) stmt).execute();
        return connectContext.getWarnings().stream().map(QueryWarning::getMessage).collect(Collectors.toList());
    }

    @Test
    public void testUse() {
        UseDbStmt use = parse("use test", UseDbStmt.class);
        Assertions.assertNull(use.getCatalogName());
        Assertions.assertEquals("test", use.getDbName());
        Assertions.assertEquals("test", parse("use namespace test", UseDbStmt.class).getDbName());
        // spark_catalog is the catalog of spark_default_catalog
        UseDbStmt sparkCatalog = parse("use spark_catalog.test", UseDbStmt.class);
        Assertions.assertEquals("default_catalog", sparkCatalog.getCatalogName());
        Assertions.assertEquals("test", sparkCatalog.getDbName());
        Assertions.assertEquals("hive_catalog", parse("use hive_catalog.db1", UseDbStmt.class).getCatalogName());
        SparkStatementTestUtils.assertRejected("use a.b.c", "nested namespace");
    }

    @Test
    public void testUseWithDefaultCatalogOfSpark() {
        connectContext.getSessionVariable().setSparkDefaultCatalog("hive_catalog");
        Assertions.assertEquals("hive_catalog", parse("use spark_catalog.db1", UseDbStmt.class).getCatalogName());
        Assertions.assertEquals("hive_catalog", parse("set catalog spark_catalog", SetCatalogStmt.class)
                .getCatalogName());
        Assertions.assertEquals("other", parse("set catalog other", SetCatalogStmt.class).getCatalogName());
    }

    @Test
    public void testCacheCommandsAreNoOps() throws Exception {
        for (String sql : new String[] {"cache table t0", "cache lazy table t0", "uncache table if exists t0",
                "clear cache"}) {
            SparkSetStmt stmt = parse(sql, SparkSetStmt.class);
            Assertions.assertTrue(stmt.getSetListItems().isEmpty(), sql);
            Assertions.assertEquals(1, stmt.getNotices().size(), sql);
        }
        List<String> warnings = executeAndGetWarnings("cache table t0");
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertTrue(warnings.get(0).contains("CACHE TABLE"), warnings.get(0));
        SparkStatementTestUtils.assertRejected("cache table c1 as select * from t0", "temporary view");
    }

    @Test
    public void testResourceCommandsAreNoOps() throws Exception {
        Assertions.assertTrue(parse("add jar '/tmp/udf.jar'", SparkSetStmt.class).getSetListItems().isEmpty());
        Assertions.assertTrue(parse("list jar", SparkSetStmt.class).getSetListItems().isEmpty());
        List<String> warnings = executeAndGetWarnings("add jar '/tmp/udf.jar'");
        Assertions.assertTrue(warnings.get(0).contains("CREATE GLOBAL FUNCTION"), warnings.get(0));
    }

    @Test
    public void testFunctionStatementsPointToGlobalFunction() {
        SparkStatementTestUtils.assertRejected(
                "create temporary function f as 'com.example.F' using jar '/tmp/udf.jar'",
                "CREATE GLOBAL FUNCTION");
    }

    @Test
    public void testRefreshAndRepair() {
        RefreshTableStmt refresh = parse("refresh table test.t0", RefreshTableStmt.class);
        Assertions.assertEquals("test", refresh.getDbName());
        Assertions.assertEquals("t0", refresh.getTableName());
        Assertions.assertEquals("t0", parse("msck repair table t0", RefreshTableStmt.class).getTableName());
        Assertions.assertEquals("t0", parse("repair table t0 add partitions", RefreshTableStmt.class)
                .getTableName());
        Assertions.assertEquals("t0", parse("alter table t0 recover partitions", RefreshTableStmt.class)
                .getTableName());
        SparkStatementTestUtils.assertRejected("msck repair table t0 drop partitions", "DROP");
    }

    @Test
    public void testAnalyze() {
        AnalyzeStmt all = parse("analyze table t0 compute statistics", AnalyzeStmt.class);
        Assertions.assertEquals("t0", all.getTableName());
        Assertions.assertTrue(all.getColumns().isEmpty());
        AnalyzeStmt columns = parse("analyze table test.t0 compute statistics for columns v1, v2",
                AnalyzeStmt.class);
        Assertions.assertEquals(2, columns.getColumns().size());
        Assertions.assertTrue(parse("analyze table t0 compute statistics for all columns", AnalyzeStmt.class)
                .getColumns().isEmpty());
        Assertions.assertTrue(parse("analyze table t0 compute statistics noscan", SparkSetStmt.class)
                .getSetListItems().isEmpty());
        SparkStatementTestUtils.assertRejected("analyze table t0 partition (p = 1) compute statistics", "PARTITION");
    }

    @Test
    public void testShowTables() {
        ShowTableStmt plain = parse("show tables", ShowTableStmt.class);
        Assertions.assertNull(plain.getDb());
        Assertions.assertNull(plain.getPattern());
        ShowTableStmt in = parse("show tables in test like 't*'", ShowTableStmt.class);
        Assertions.assertEquals("test", in.getDb());
        Assertions.assertEquals("t%", in.getPattern());
        ShowTableStmt withCatalog = parse("show tables from hive_catalog.db1 'a*'", ShowTableStmt.class);
        Assertions.assertEquals("hive_catalog", withCatalog.getCatalogName());
        Assertions.assertEquals("db1", withCatalog.getDb());
        SparkStatementTestUtils.assertRejected("show tables like 'a|b'", "alternatives");
    }

    @Test
    public void testShowDatabases() {
        ShowDbStmt plain = parse("show databases", ShowDbStmt.class);
        Assertions.assertNull(plain.getPattern());
        Assertions.assertEquals("t%", parse("show schemas like 't*'", ShowDbStmt.class).getPattern());
        ShowDbStmt catalog = parse("show namespaces in spark_catalog", ShowDbStmt.class);
        Assertions.assertEquals("default_catalog", catalog.getCatalogName());
    }

    @Test
    public void testShowColumnsAndCreateTable() {
        ShowColumnStmt columns = parse("show columns in t0 in test", ShowColumnStmt.class);
        Assertions.assertEquals("test", columns.getDb());
        Assertions.assertEquals("t0", columns.getTable());
        Assertions.assertEquals("t0", parse("show columns from test.t0", ShowColumnStmt.class).getTable());
        ShowCreateTableStmt create = parse("show create table test.t0", ShowCreateTableStmt.class);
        Assertions.assertEquals("t0", create.getTable());
        SparkStatementTestUtils.assertRejected("show create table t0 as serde", "SERDE");
    }

    @Test
    public void testDescribe() {
        Assertions.assertEquals("t0", parse("describe t0", DescribeStmt.class).getTableName());
        Assertions.assertEquals("t0", parse("desc table extended test.t0", DescribeStmt.class).getTableName());
        Assertions.assertEquals("t0", parse("describe formatted t0", DescribeStmt.class).getTableName());
        SparkStatementTestUtils.assertRejected("describe t0 v1", "partition or a column");
    }

    @Test
    public void testUnknownVariableIsNotMappedBySession() {
        // the spark_* variables exist with the documented defaults
        SessionVariable sv = new SessionVariable();
        Assertions.assertEquals("3.5", sv.getSparkVersion());
        Assertions.assertFalse(sv.isSparkAnsiEnabled());
        Assertions.assertFalse(sv.isSparkCaseSensitive());
        Assertions.assertTrue(sv.isSparkLegacySizeOfNull());
        Assertions.assertEquals("default_catalog", sv.getSparkDefaultCatalog());
        Assertions.assertEquals("static", sv.getSparkPartitionOverwriteMode());
        Assertions.assertFalse(sv.isSparkStrictConf());
        Assertions.assertEquals("error", sv.getSparkUnsupportedBehavior());
    }
}
