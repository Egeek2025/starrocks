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

import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Pair;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.StatementPlanner;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.optimizer.dump.QueryDumpInfo;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;

/**
 * Base class of the Spark dialect tests: a minimal cluster with a few tables and sql_dialect = 'spark'.
 */
public class SparkTestBase {
    public static ConnectContext connectContext;
    public static StarRocksAssert starRocksAssert;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        Config.enable_virtual_columns = false;
        FeConstants.enablePruneEmptyOutputScan = false;
        connectContext = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.withDatabase("test").useDatabase("test");
        starRocksAssert.withTable("CREATE TABLE `t0` (\n" +
                "  `v1` bigint NULL COMMENT \"\",\n" +
                "  `v2` bigint NULL COMMENT \"\",\n" +
                "  `v3` bigint NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`v1`, `v2`, v3)\n" +
                "DISTRIBUTED BY HASH(`v1`) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"in_memory\" = \"false\"\n" +
                ");");
        starRocksAssert.withTable("CREATE TABLE `t1` (\n" +
                "  `v4` bigint NULL COMMENT \"\",\n" +
                "  `v5` bigint NULL COMMENT \"\",\n" +
                "  `v6` bigint NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`v4`, `v5`, v6)\n" +
                "DISTRIBUTED BY HASH(`v4`) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"in_memory\" = \"false\"\n" +
                ");");
        starRocksAssert.withTable("CREATE TABLE `tall` (\n" +
                "  `ta` varchar(20) NULL COMMENT \"\",\n" +
                "  `tb` smallint(6) NULL COMMENT \"\",\n" +
                "  `tc` int(11) NULL COMMENT \"\",\n" +
                "  `td` bigint(20) NULL COMMENT \"\",\n" +
                "  `te` float NULL COMMENT \"\",\n" +
                "  `tf` double NULL COMMENT \"\",\n" +
                "  `tg` bigint(20) NULL COMMENT \"\",\n" +
                "  `th` datetime NULL COMMENT \"\",\n" +
                "  `ti` date NULL COMMENT \"\",\n" +
                "  `tj` decimal(9, 3) NULL COMMENT \"\",\n" +
                "  `tk` varbinary NULL COMMENT \"\"\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`ta`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`ta`) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"in_memory\" = \"false\"\n" +
                ");");
        starRocksAssert.withTable("create table test_array(c0 INT, " +
                "c1 array<varchar(65533)>, " +
                "c2 array<int>) " +
                " duplicate key(c0) distributed by hash(c0) buckets 1 " +
                "properties('replication_num'='1');");
        starRocksAssert.withTable("create table test_map(c0 int, " +
                "c1 map<int,int>, " +
                "c2 array<map<int,int>>, " +
                "c3 map<varchar(65533), date>) " +
                "engine=olap distributed by hash(c0) buckets 10 " +
                "properties('replication_num'='1');");

        connectContext.getSessionVariable().setSqlDialect("spark");
        connectContext.getSessionVariable().setEnableDialectDowngrade(true);
        connectContext.getSessionVariable().setCboPushDownGroupingSet(false);
        connectContext.getSessionVariable().setCboPushDownAggregateMode(-1);
        connectContext.getSessionVariable().setEnableGlobalLateMaterialization(false);
    }

    public static StatementBase parseOne(String sql) {
        return SqlParser.parse(sql, connectContext.getSessionVariable()).get(0);
    }

    public static StatementBase analyzeSuccess(String sql) {
        StatementBase statement = parseOne(sql);
        Analyzer.analyze(statement, connectContext);
        return statement;
    }

    public static void analyzeFail(String sql, String expectedMessage) {
        try {
            StatementBase statement = parseOne(sql);
            Analyzer.analyze(statement, connectContext);
            Assertions.fail("Miss an exception for: " + sql);
        } catch (ParsingException | StarRocksPlannerException e) {
            Assertions.assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
        }
    }

    public static String getFragmentPlan(String sql) throws Exception {
        return getPlanAndFragment(sql).second.getExplainString(TExplainLevel.NORMAL);
    }

    public static Pair<StatementBase, ExecPlan> getPlanAndFragment(String sql) throws Exception {
        connectContext.setDumpInfo(new QueryDumpInfo(connectContext));
        List<StatementBase> statements = SqlParser.parse(sql, connectContext.getSessionVariable());
        connectContext.getDumpInfo().setOriginStmt(sql);
        StatementBase statement = statements.get(0);
        return new Pair<>(statement, StatementPlanner.plan(statement, connectContext));
    }

    protected void assertPlanContains(String sql, String... expected) throws Exception {
        String plan = getFragmentPlan(sql);
        for (String item : expected) {
            Assertions.assertTrue(StringUtils.containsIgnoreCase(plan, item),
                    "expected is: " + item + " but plan is \n" + plan);
        }
    }

    protected void assertPlanNotContains(String sql, String... unexpected) throws Exception {
        String plan = getFragmentPlan(sql);
        for (String item : unexpected) {
            Assertions.assertFalse(StringUtils.containsIgnoreCase(plan, item),
                    "unexpected is: " + item + " but plan is \n" + plan);
        }
    }
}
