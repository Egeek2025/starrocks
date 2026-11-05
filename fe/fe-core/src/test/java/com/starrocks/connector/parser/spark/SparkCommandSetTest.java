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

import com.starrocks.common.DdlException;
import com.starrocks.qe.QueryWarning;
import com.starrocks.qe.SessionVariable;
import com.starrocks.qe.SetExecutor;
import com.starrocks.qe.SparkConfMapper;
import com.starrocks.qe.SqlModeHelper;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.ShowVariablesStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.expression.StringLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SET / RESET of the Spark dialect and the mapping of Spark configurations.
 */
public class SparkCommandSetTest extends SparkTestBase {
    private long sqlMode;
    private String timeZone;
    private int queryTimeout;

    @BeforeAll
    public static void beforeClass() throws Exception {
        SparkTestBase.beforeClass();
    }

    @BeforeEach
    public void saveSession() {
        SessionVariable sv = connectContext.getSessionVariable();
        sqlMode = sv.getSqlMode();
        timeZone = sv.getTimeZone();
        queryTimeout = sv.getQueryTimeoutS();
        connectContext.clearWarnings();
    }

    @AfterEach
    public void restoreSession() {
        SessionVariable sv = connectContext.getSessionVariable();
        sv.setSqlMode(sqlMode);
        sv.setTimeZone(timeZone);
        sv.setQueryTimeoutS(queryTimeout);
        sv.setSparkStrictConf(false);
        sv.setSparkAnsiEnabled(false);
        sv.setSparkLegacySizeOfNull(true);
        sv.setSparkPartitionOverwriteMode("static");
        connectContext.clearWarnings();
    }

    private SparkSetStmt parseSet(String sql) {
        StatementBase stmt = parseOne(sql);
        Assertions.assertTrue(stmt instanceof SparkSetStmt, sql + " is " + stmt.getClass());
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect(), sql);
        return (SparkSetStmt) stmt;
    }

    /** Parses, analyzes and executes a SET statement the way StmtExecutor does. */
    private void executeSet(String sql) throws DdlException {
        StatementBase stmt = analyzeSuccess(sql);
        Assertions.assertTrue(stmt instanceof SetStmt, sql);
        new SetExecutor(connectContext, (SetStmt) stmt).execute();
    }

    private static List<String> variableNames(SetStmt stmt) {
        return stmt.getSetListItems().stream().map(v -> ((SystemVariable) v).getVariable())
                .collect(Collectors.toList());
    }

    private static List<String> warnings(com.starrocks.qe.ConnectContext ctx) {
        return ctx.getWarnings().stream().map(QueryWarning::getMessage).collect(Collectors.toList());
    }

    @Test
    public void testMappedConfigurations() {
        Assertions.assertEquals(List.of(SessionVariable.TIME_ZONE),
                variableNames(parseSet("set spark.sql.session.timeZone=Asia/Shanghai")));
        Assertions.assertEquals(List.of(SessionVariable.SPARK_ANSI_ENABLED),
                variableNames(parseSet("set spark.sql.ansi.enabled = true")));
        Assertions.assertEquals(List.of(SessionVariable.SPARK_CASE_SENSITIVE),
                variableNames(parseSet("set spark.sql.caseSensitive=false")));
        Assertions.assertEquals(List.of(SessionVariable.SPARK_LEGACY_SIZE_OF_NULL),
                variableNames(parseSet("set spark.sql.legacy.sizeOfNull=false")));
        Assertions.assertEquals(List.of(SessionVariable.SPARK_PARTITION_OVERWRITE_MODE),
                variableNames(parseSet("set spark.sql.sources.partitionOverwriteMode=DYNAMIC")));
        Assertions.assertEquals(List.of(SessionVariable.BROADCAST_ROW_LIMIT),
                variableNames(parseSet("set spark.sql.autoBroadcastJoinThreshold=-1")));
        // the keys are case insensitive, quoted values lose their quotes
        SparkSetStmt tz = parseSet("SET SPARK.SQL.SESSION.TIMEZONE = 'UTC'");
        Assertions.assertEquals(List.of(SessionVariable.TIME_ZONE), variableNames(tz));
        Assertions.assertEquals("UTC", ((StringLiteral) ((SystemVariable) tz.getSetListItems().get(0))
                .getUnResolvedExpression()).getStringValue());
    }

    @Test
    public void testAbsorbedConfigurationsHaveNoVariable() {
        for (String conf : new String[] {"spark.executor.memory=4g", "spark.sql.adaptive.enabled=true",
                "spark.sql.shuffle.partitions=200", "spark.sql.files.maxPartitionBytes=128MB",
                "hive.exec.dynamic.partition.mode=nonstrict", "mapreduce.job.queuename=etl"}) {
            SparkSetStmt stmt = parseSet("set " + conf);
            Assertions.assertTrue(stmt.getSetListItems().isEmpty(), conf);
            Assertions.assertEquals(1, stmt.getNotices().size(), conf);
            Assertions.assertFalse(stmt.getNotices().get(0).isUnknownKey(), conf);
        }
        SparkSetStmt unknown = parseSet("set spark.some.unknown.key=1");
        Assertions.assertTrue(unknown.getSetListItems().isEmpty());
        Assertions.assertTrue(unknown.getNotices().get(0).isUnknownKey());
    }

    @Test
    public void testStarRocksVariablesPassThrough() {
        Assertions.assertEquals(List.of("query_timeout"), variableNames(parseSet("set query_timeout = 100")));
        Assertions.assertEquals(List.of("sql_dialect"), variableNames(parseSet("set sql_dialect = 'StarRocks'")));
        Assertions.assertEquals(List.of("enable_dialect_downgrade"),
                variableNames(parseSet("set enable_dialect_downgrade=false")));
    }

    @Test
    public void testStarRocksSyntaxFallsBack() {
        SparkStatementTestUtils.parseDowngraded("set global query_timeout = 100");
        SparkStatementTestUtils.parseDowngraded("set @a = 1");
        SparkStatementTestUtils.parseDowngraded("set query_timeout = 60 * 10");
    }

    @Test
    public void testRejectedConfigurations() {
        SparkStatementTestUtils.assertRejected("set spark.sql.caseSensitive=true", "caseSensitive");
        SparkStatementTestUtils.assertRejected("set spark.sql.ansi.enabled=maybe", "true or false");
        SparkStatementTestUtils.assertRejected("set spark.sql.sources.partitionOverwriteMode=foo", "STATIC or DYNAMIC");
    }

    @Test
    public void testSetWithoutValueAndWithoutKey() {
        Assertions.assertTrue(parseSet0("set") instanceof ShowVariablesStmt);
        Assertions.assertTrue(parseSet0("set -v") instanceof ShowVariablesStmt);
        ShowVariablesStmt show = (ShowVariablesStmt) parseSet0("set spark.sql.session.timeZone");
        Assertions.assertEquals(SessionVariable.TIME_ZONE, show.getPattern());
    }

    private static StatementBase parseSet0(String sql) {
        StatementBase stmt = parseOne(sql);
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect(), sql);
        return stmt;
    }

    @Test
    public void testSetTimeZone() throws Exception {
        executeSet("set time zone 'Asia/Tokyo'");
        Assertions.assertEquals("Asia/Tokyo", connectContext.getSessionVariable().getTimeZone());
        executeSet("set spark.sql.session.timeZone=UTC");
        Assertions.assertEquals("UTC", connectContext.getSessionVariable().getTimeZone());
        SparkSetStmt local = parseSet("set time zone local");
        Assertions.assertEquals(List.of(SessionVariable.TIME_ZONE), variableNames(local));
    }

    @Test
    public void testExecuteMappedAndPassThroughVariables() throws Exception {
        executeSet("set spark.sql.legacy.sizeOfNull=false");
        Assertions.assertFalse(connectContext.getSessionVariable().isSparkLegacySizeOfNull());
        executeSet("set spark.sql.sources.partitionOverwriteMode=dynamic");
        Assertions.assertEquals("dynamic", connectContext.getSessionVariable().getSparkPartitionOverwriteMode());
        executeSet("set query_timeout = 123");
        Assertions.assertEquals(123, connectContext.getSessionVariable().getQueryTimeoutS());
        executeSet("reset spark.sql.legacy.sizeOfNull");
        Assertions.assertTrue(connectContext.getSessionVariable().isSparkLegacySizeOfNull());
    }

    @Test
    public void testAnsiModeSetsSqlModeBits() throws Exception {
        long bits = SqlModeHelper.MODE_ERROR_IF_OVERFLOW | SqlModeHelper.MODE_ERROR_FOR_DIVISION_BY_ZERO;
        executeSet("set spark.sql.ansi.enabled=true");
        Assertions.assertTrue(connectContext.getSessionVariable().isSparkAnsiEnabled());
        Assertions.assertEquals(bits, connectContext.getSessionVariable().getSqlMode() & bits);
        executeSet("set spark.sql.ansi.enabled=false");
        Assertions.assertFalse(connectContext.getSessionVariable().isSparkAnsiEnabled());
        Assertions.assertEquals(0, connectContext.getSessionVariable().getSqlMode() & bits);
    }

    @Test
    public void testAbsorbedConfigurationLeavesWarning() throws Exception {
        executeSet("set spark.executor.memory=4g");
        List<String> warnings = connectContext.getWarnings().stream().map(QueryWarning::getMessage)
                .collect(Collectors.toList());
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertTrue(warnings.get(0).contains("spark.executor.memory"), warnings.get(0));
        // the warnings of unrelated SET statements stay readable
        executeSet("set spark.sql.adaptive.enabled=true");
        Assertions.assertEquals(2, connectContext.getWarnings().size());
    }

    @Test
    public void testStrictConfigurationRejectsUnknownKeys() throws Exception {
        executeSet("set spark.some.unknown.key=1");
        Assertions.assertEquals(1, connectContext.getWarnings().size());
        executeSet("set spark_strict_conf = true");
        DdlException e = Assertions.assertThrows(DdlException.class, () -> executeSet("set spark.some.unknown.key=1"));
        Assertions.assertTrue(e.getMessage().contains("spark.some.unknown.key"), e.getMessage());
        // a key that is known to be harmless is still accepted
        executeSet("set spark.executor.memory=4g");
    }

    @Test
    public void testReset() {
        SparkSetStmt reset = parseSet("reset spark.sql.session.timeZone");
        Assertions.assertEquals(List.of(SessionVariable.TIME_ZONE), variableNames(reset));
        Assertions.assertNull(((SystemVariable) reset.getSetListItems().get(0)).getUnResolvedExpression());
        Assertions.assertEquals(Boolean.FALSE, parseSet("reset spark.sql.ansi.enabled").getAnsiMode());
        Assertions.assertTrue(parseSet("reset spark.executor.memory").getSetListItems().isEmpty());
        SparkStatementTestUtils.assertRejected("reset", "RESET");
    }

    @Test
    public void testConfMapperKeys() {
        Assertions.assertTrue(SparkConfMapper.isConfKey("spark.sql.x"));
        Assertions.assertFalse(SparkConfMapper.isConfKey("query_timeout"));
        Assertions.assertEquals("query_timeout", SparkConfMapper.variableOf("query_timeout"));
        Assertions.assertEquals(SessionVariable.TIME_ZONE, SparkConfMapper.variableOf("spark.sql.session.timeZone"));
        Assertions.assertNull(SparkConfMapper.variableOf("spark.executor.memory"));
        Assertions.assertEquals(Boolean.TRUE, SparkConfMapper.set("spark.sql.ansi.enabled", "TRUE").getAnsiMode());
        Assertions.assertEquals(1, SparkConfMapper.set("spark.sql.autoBroadcastJoinThreshold", "10485760")
                .getNotices().size());
        Assertions.assertTrue(warnings(connectContext).isEmpty());
    }
}
