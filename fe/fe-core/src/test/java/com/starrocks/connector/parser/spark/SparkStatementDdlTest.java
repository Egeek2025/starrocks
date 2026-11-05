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

import com.starrocks.sql.ast.AddColumnClause;
import com.starrocks.sql.ast.AddColumnsClause;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.sql.ast.ColumnDef;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.CreateTableAsSelectStmt;
import com.starrocks.sql.ast.CreateTableLikeStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.CreateViewStmt;
import com.starrocks.sql.ast.DropDbStmt;
import com.starrocks.sql.ast.DropTableStmt;
import com.starrocks.sql.ast.ListPartitionDesc;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.TableRenameClause;
import com.starrocks.sql.ast.TruncateTableStmt;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CREATE TABLE / CTAS / LIKE / DROP / ALTER / VIEW of the Spark dialect.
 */
public class SparkStatementDdlTest extends SparkTestBase {
    @BeforeAll
    public static void beforeClass() throws Exception {
        SparkTestBase.beforeClass();
    }

    private static <T extends StatementBase> T parse(String sql, Class<T> clazz) {
        StatementBase stmt = parseOne(sql);
        Assertions.assertTrue(clazz.isInstance(stmt), sql + " is " + stmt.getClass());
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect(), sql);
        return clazz.cast(stmt);
    }

    private static List<String> columnNames(CreateTableStmt stmt) {
        return stmt.getColumnDefs().stream().map(ColumnDef::getName).collect(Collectors.toList());
    }

    @Test
    public void testCreateTableUsing() {
        CreateTableStmt stmt = parse("create table if not exists spark_ddl_t1 (id bigint not null, name string "
                + "comment 'n', amount decimal(10, 2)) using parquet comment 'c' "
                + "tblproperties ('replication_num' = '1')", CreateTableStmt.class);
        Assertions.assertTrue(stmt.isSetIfNotExists());
        Assertions.assertEquals("spark_ddl_t1", stmt.getTableName());
        Assertions.assertEquals(List.of("id", "name", "amount"), columnNames(stmt));
        Assertions.assertFalse(stmt.getColumnDefs().get(0).isAllowNull());
        Assertions.assertEquals("c", stmt.getComment());
        Assertions.assertEquals("1", stmt.getProperties().get("replication_num"));
        // the keys and the distribution of a native table are left for the analyzer to derive
        Assertions.assertNull(stmt.getKeysDesc());
        Assertions.assertNull(stmt.getDistributionDesc());
        Assertions.assertNull(stmt.getPartitionDesc());
    }

    @Test
    public void testCreateTableAnalyze() {
        CreateTableStmt stmt = (CreateTableStmt) analyzeSuccess("create table spark_ddl_t2 (id bigint, name string) "
                + "using parquet tblproperties ('replication_num' = '1')");
        Assertions.assertNotNull(stmt.getKeysDesc());
        Assertions.assertEquals(com.starrocks.sql.ast.KeysType.DUP_KEYS, stmt.getKeysDesc().getKeysType());
        Assertions.assertNotNull(stmt.getDistributionDesc());
    }

    @Test
    public void testPartitionColumnsAreLast() {
        CreateTableStmt stmt = parse("create table spark_ddl_t3 (dt string, id bigint, region string) using orc "
                + "partitioned by (region, dt) tblproperties ('replication_num' = '1')", CreateTableStmt.class);
        Assertions.assertEquals(List.of("id", "region", "dt"), columnNames(stmt));
        Assertions.assertTrue(stmt.getPartitionDesc() instanceof ListPartitionDesc);
        Assertions.assertEquals(List.of("region", "dt"),
                ((ListPartitionDesc) stmt.getPartitionDesc()).getPartitionColNames());
        // a native table is partitioned by NOT NULL columns
        Assertions.assertFalse(stmt.getColumnDefs().get(1).isAllowNull());
        Assertions.assertFalse(stmt.getColumnDefs().get(2).isAllowNull());
    }

    @Test
    public void testHiveStylePartitionColumns() {
        CreateTableStmt stmt = parse("create table spark_ddl_t4 (id bigint) partitioned by (dt string) "
                + "tblproperties ('replication_num' = '1')", CreateTableStmt.class);
        Assertions.assertEquals(List.of("id", "dt"), columnNames(stmt));
        Assertions.assertTrue(stmt.getPartitionDesc() instanceof ListPartitionDesc);
    }

    @Test
    public void testBucketedTable() {
        CreateTableStmt stmt = parse("create table spark_ddl_t5 (id bigint, name string) using parquet "
                + "clustered by (id) into 8 buckets", CreateTableStmt.class);
        Assertions.assertNotNull(stmt.getDistributionDesc());
        Assertions.assertEquals(8, stmt.getDistributionDesc().getBuckets());
    }

    @Test
    public void testCreateTableRejected() {
        SparkStatementTestUtils.assertRejected("create temporary table spark_ddl_t6 (id int) using parquet",
                "TEMPORARY TABLE");
        SparkStatementTestUtils.assertRejected("create external table spark_ddl_t6 (id int) using parquet "
                + "location '/tmp/x'", "EXTERNAL TABLE");
        SparkStatementTestUtils.assertRejected("create table spark_ddl_t6 (id int) using delta", "USING delta");
        SparkStatementTestUtils.assertRejected("create table spark_ddl_t6 (id int) using parquet "
                + "location '/tmp/x'", "LOCATION");
        SparkStatementTestUtils.assertRejected("create table spark_ddl_t6 (id int) using parquet "
                + "partitioned by (bucket(4, id))", "transform");
        SparkStatementTestUtils.assertRejected("create table spark_ddl_t6 using parquet", "without a column list");
    }

    @Test
    public void testCreateTableLike() {
        CreateTableLikeStmt stmt = parse("create table if not exists test.spark_ddl_like like test.t0 "
                + "tblproperties ('replication_num' = '1')", CreateTableLikeStmt.class);
        Assertions.assertTrue(stmt.isSetIfNotExists());
        Assertions.assertEquals("spark_ddl_like", stmt.getTableName());
        Assertions.assertEquals("t0", stmt.getExistedTableName());
        Assertions.assertEquals("test", stmt.getExistedDbName());
        Assertions.assertEquals("1", stmt.getProperties().get("replication_num"));
        SparkStatementTestUtils.assertRejected("create table spark_ddl_like2 like t0 using parquet", "LIKE");
    }

    @Test
    public void testCtas() {
        CreateTableAsSelectStmt stmt = parse("create table spark_ddl_ctas using parquet partitioned by (v1) "
                + "tblproperties ('replication_num' = '1') as select v1, v2 from t0", CreateTableAsSelectStmt.class);
        CreateTableStmt create = stmt.getCreateTableStmt();
        Assertions.assertEquals("spark_ddl_ctas", create.getTableName());
        Assertions.assertTrue(create.getPartitionDesc() instanceof ListPartitionDesc);
        Assertions.assertEquals("1", create.getProperties().get("replication_num"));
        Assertions.assertNotNull(stmt.getQueryStatement());
        // AS may be left out
        parse("create table spark_ddl_ctas2 select v1 from t0", CreateTableAsSelectStmt.class);
        SparkStatementTestUtils.assertRejected("create table spark_ddl_ctas3 (v1 bigint) using parquet "
                + "as select v1 from t0", "Schema may not be specified");
    }

    @Test
    public void testCtasAnalyze() {
        analyzeSuccess("create table spark_ddl_ctas4 using parquet tblproperties ('replication_num' = '1') "
                + "as select v1, v2 from t0");
    }

    @Test
    public void testDropTableAndView() {
        DropTableStmt drop = parse("drop table if exists test.t0 purge", DropTableStmt.class);
        Assertions.assertTrue(drop.isSetIfExists());
        Assertions.assertTrue(drop.isForceDrop());
        Assertions.assertFalse(drop.isView());
        Assertions.assertEquals("test", drop.getDbName());
        Assertions.assertEquals("t0", drop.getTableName());
        Assertions.assertFalse(parse("drop table t0", DropTableStmt.class).isSetIfExists());

        DropTableStmt dropView = parse("drop view if exists v1", DropTableStmt.class);
        Assertions.assertTrue(dropView.isView());
        Assertions.assertTrue(dropView.isSetIfExists());
    }

    @Test
    public void testTruncate() {
        TruncateTableStmt truncate = parse("truncate table t0", TruncateTableStmt.class);
        Assertions.assertEquals("t0", truncate.getTblRef().getTableName());
        SparkStatementTestUtils.assertRejected("truncate table t0 partition (p = 1)", "PARTITION");
    }

    @Test
    public void testAlterTable() {
        AlterTableStmt rename = parse("alter table test.t0 rename to t0_renamed", AlterTableStmt.class);
        Assertions.assertTrue(rename.getAlterClauseList().get(0) instanceof TableRenameClause);
        Assertions.assertEquals("t0_renamed",
                ((TableRenameClause) rename.getAlterClauseList().get(0)).getNewTableName());
        // a table can not move to another database
        SparkStatementTestUtils.assertRejected("alter table test.t0 rename to other.t0", "another database");

        AlterTableStmt add = parse("alter table t0 add columns (c1 int comment 'x', c2 string)",
                AlterTableStmt.class);
        Assertions.assertTrue(add.getAlterClauseList().get(0) instanceof AddColumnsClause);
        Assertions.assertEquals(2, ((AddColumnsClause) add.getAlterClauseList().get(0)).getColumnDefs().size());

        AlterTableStmt addOne = parse("alter table t0 add column c3 bigint after v3", AlterTableStmt.class);
        Assertions.assertTrue(addOne.getAlterClauseList().get(0) instanceof AddColumnClause);
        Assertions.assertEquals("c3", ((AddColumnClause) addOne.getAlterClauseList().get(0)).getColumnDef().getName());
    }

    @Test
    public void testCreateView() {
        CreateViewStmt stmt = parse("create or replace view spark_ddl_v1 (a comment 'col a', b) comment 'v' "
                + "as select v1, v2 from t0", CreateViewStmt.class);
        Assertions.assertTrue(stmt.isReplace());
        Assertions.assertFalse(stmt.isSetIfNotExists());
        Assertions.assertEquals(2, stmt.getColWithComments().size());
        Assertions.assertEquals("v", stmt.getComment());
        Assertions.assertTrue(stmt.getQueryStartIndex() >= 0);
        Assertions.assertTrue(stmt.getQueryStopIndex() > stmt.getQueryStartIndex());
        parse("create view if not exists spark_ddl_v2 as select 1", CreateViewStmt.class);
    }

    @Test
    public void testCreateViewAnalyze() {
        // ViewAnalyzer needs the original statement and the query indexes
        CreateViewStmt stmt = (CreateViewStmt) analyzeSuccess("create view spark_ddl_v3 as select v1, v2 from t0");
        Assertions.assertNotNull(stmt.getInlineViewDef());
        Assertions.assertEquals(2, stmt.getColumns().size());
    }

    @Test
    public void testTemporaryViewIsNotSupported() {
        SparkStatementTestUtils.assertRejected("create or replace temporary view spark_ddl_tv as select 1",
                "temporary views");
        SparkStatementTestUtils.assertRejected("create global temp view spark_ddl_tv as select 1", "temporary views");
    }

    @Test
    public void testDatabase() {
        CreateDbStmt create = parse("create database if not exists spark_ddl_db", CreateDbStmt.class);
        Assertions.assertTrue(create.isSetIfNotExists());
        Assertions.assertEquals("spark_ddl_db", create.getFullDbName());
        DropDbStmt drop = parse("drop schema if exists spark_ddl_db", DropDbStmt.class);
        Assertions.assertTrue(drop.isSetIfExists());
        Assertions.assertEquals("spark_ddl_db", drop.getDbName());
        SparkStatementTestUtils.assertRejected("drop database spark_ddl_db cascade", "CASCADE");
    }
}
