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

import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.PartitionRef;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.ValuesRelation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * INSERT [OVERWRITE] [TABLE] t [PARTITION (...)] [(cols)] query of the Spark dialect.
 */
public class SparkStatementInsertTest extends SparkTestBase {
    @BeforeAll
    public static void beforeClass() throws Exception {
        SparkTestBase.beforeClass();
    }

    private static InsertStmt parseInsert(String sql) {
        StatementBase stmt = parseOne(sql);
        Assertions.assertTrue(stmt instanceof InsertStmt, sql);
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect(), sql);
        return (InsertStmt) stmt;
    }

    @Test
    public void testInsertIntoSelect() {
        InsertStmt insert = parseInsert("insert into t0 select * from t1");
        Assertions.assertFalse(insert.isOverwrite());
        Assertions.assertEquals("t0", insert.getTableRef().getTableName());
        Assertions.assertNull(insert.getTargetColumnNames());
        Assertions.assertNull(insert.getTargetPartitionNames());
    }

    @Test
    public void testInsertTableKeywordAndOverwrite() {
        InsertStmt insert = parseInsert("INSERT OVERWRITE TABLE test.t0 SELECT v4, v5, v6 FROM t1");
        Assertions.assertTrue(insert.isOverwrite());
        Assertions.assertEquals("test", insert.getTableRef().getDbName());
        Assertions.assertEquals("t0", insert.getTableRef().getTableName());
        Assertions.assertFalse(parseInsert("insert into table t0 select * from t1").isOverwrite());
    }

    @Test
    public void testInsertValues() {
        InsertStmt insert = parseInsert("insert into t0 values (1, 2, 3), (4, 5, 6)");
        Assertions.assertTrue(insert.getQueryStatement().getQueryRelation() instanceof ValuesRelation);
        ValuesRelation values = (ValuesRelation) insert.getQueryStatement().getQueryRelation();
        Assertions.assertEquals(2, values.getRows().size());
    }

    @Test
    public void testInsertColumnList() {
        // target columns are matched in lower case
        InsertStmt insert = parseInsert("insert into t0 (V1, v2) select v4, v5 from t1");
        Assertions.assertEquals(List.of("v1", "v2"), insert.getTargetColumnNames());
        InsertStmt byName = parseInsert("insert into t0 by name select v4 as v1 from t1");
        Assertions.assertTrue(byName.isColumnMatchByName());
    }

    @Test
    public void testStaticPartition() {
        InsertStmt insert = parseInsert("insert overwrite table t0 partition (p = 'x', q = 1) select v4 from t1");
        PartitionRef partition = insert.getTargetPartitionNames();
        Assertions.assertNotNull(partition);
        Assertions.assertTrue(partition.isKeyPartitionNames());
        Assertions.assertEquals(List.of("p", "q"), partition.getPartitionColNames());
        Assertions.assertEquals(2, partition.getPartitionColValues().size());
        Assertions.assertTrue(insert.isStaticKeyPartitionInsert());
    }

    @Test
    public void testStaticPartitionWithColumnList() {
        // Spark leaves the static partition columns out of the list, StarRocks wants them in it
        InsertStmt insert = parseInsert("insert into t0 partition (p = 'x') (v1, v2) select v4, v5 from t1");
        Assertions.assertEquals(List.of("v1", "v2", "p"), insert.getTargetColumnNames());
    }

    @Test
    public void testDynamicPartitionIsNoPartitionClause() {
        InsertStmt insert = parseInsert("insert into t0 partition (p, q) select v4, v5, v6, 1, 2 from t1");
        Assertions.assertNull(insert.getTargetPartitionNames());
    }

    @Test
    public void testMixedPartitionIsRejected() {
        SparkStatementTestUtils.assertRejected("insert into t0 partition (p = 'x', q) select * from t1",
                "static and dynamic");
    }

    @Test
    public void testExplainInsert() throws Exception {
        InsertStmt insert = parseInsert("explain insert into t0 select * from t1");
        Assertions.assertTrue(insert.isExplain());
        assertPlanContains("explain insert into t0 select * from t1", "OLAP TABLE SINK");
    }

    @Test
    public void testInsertAnalyzeAndPlan() throws Exception {
        analyzeSuccess("insert into t0 select * from t1");
        analyzeSuccess("insert overwrite table t0 select v4, v5, v6 from t1");
        analyzeSuccess("insert into t0 (v1, v2, v3) values (1, 2, 3)");
        assertPlanContains("insert into t0 select * from t1", "OLAP TABLE SINK");
        assertPlanContains("insert into t0 values (1, 2, 3)", "OLAP TABLE SINK");
    }
}
