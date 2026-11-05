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

import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.ShowTableStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.UseDbStmt;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.parser.ParsingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SparkDowngradeTest extends SparkTestBase {
    @BeforeAll
    public static void beforeClass() throws Exception {
        SparkTestBase.beforeClass();
    }

    @AfterEach
    public void restore() {
        connectContext.getSessionVariable().setSqlDialect("spark");
        connectContext.getSessionVariable().setEnableDialectDowngrade(true);
        connectContext.setRelationAliasCaseInSensitive(false);
    }

    @Test
    public void testUnsupportedStatementsFallBackToStarRocksParser() {
        StatementBase stmt = parseOne("show tables");
        Assertions.assertTrue(stmt instanceof ShowTableStmt);
        Assertions.assertEquals(ParsedDialect.STARROCKS, stmt.getParsedDialect());

        stmt = parseOne("use test");
        Assertions.assertTrue(stmt instanceof UseDbStmt);
        Assertions.assertEquals(ParsedDialect.STARROCKS, stmt.getParsedDialect());

        stmt = parseOne("set enable_profile = true");
        Assertions.assertTrue(stmt instanceof SetStmt);
        Assertions.assertEquals(ParsedDialect.STARROCKS, stmt.getParsedDialect());

        stmt = parseOne("show variables like 'sql_dialect'");
        Assertions.assertEquals(ParsedDialect.STARROCKS, stmt.getParsedDialect());
    }

    @Test
    public void testDmlFallsBackToStarRocksParser() {
        // INSERT is not translated yet, the StarRocks parser handles it
        StatementBase stmt = parseOne("insert into t0 values (1, 2, 3)");
        Assertions.assertEquals(ParsedDialect.STARROCKS, stmt.getParsedDialect());
        stmt = parseOne("select v1 from t0 limit 1");
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect());
    }

    @Test
    public void testFallbackDoesNotTouchSession() {
        parseOne("show databases");
        Assertions.assertFalse(connectContext.isRelationAliasCaseInsensitive());
    }

    @Test
    public void testDowngradeDisabledReportsSparkError() {
        connectContext.getSessionVariable().setEnableDialectDowngrade(false);
        try {
            parseOne("show tables");
            Assertions.fail("show tables is not supported by the Spark dialect yet");
        } catch (StarRocksPlannerException e) {
            Assertions.assertTrue(e instanceof SparkUnsupportedException, e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("not supported yet"), e.getMessage());
        }

        try {
            parseOne("select from from");
            Assertions.fail("syntax error expected");
        } catch (ParsingException e) {
            Assertions.assertTrue(e.getMessage().contains("Syntax error at or near"), e.getMessage());
        }
    }

    @Test
    public void testSyntaxErrorMentionsBothParsers() {
        connectContext.getSessionVariable().setEnableDialectDowngrade(true);
        try {
            parseOne("select from from from");
            Assertions.fail("syntax error expected");
        } catch (StarRocksPlannerException e) {
            Assertions.assertTrue(e.getMessage().contains("Spark parser parse sql error"), e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("StarRocks parser also can not parse"), e.getMessage());
        }
    }

    @Test
    public void testSyntaxErrorHasLineAndColumn() {
        connectContext.getSessionVariable().setEnableDialectDowngrade(false);
        try {
            parseOne("select v1\nfrom t0\nwhere v1 = = 2");
            Assertions.fail("syntax error expected");
        } catch (ParsingException e) {
            Assertions.assertTrue(e.getMessage().contains("line 3"), e.getMessage());
        }
    }

    @Test
    public void testDialectNameIsCaseInsensitive() {
        connectContext.getSessionVariable().setSqlDialect("SPARK");
        Assertions.assertEquals(ParsedDialect.SPARK, parseOne("select v1 from t0").getParsedDialect());
        connectContext.getSessionVariable().setSqlDialect("Spark");
        Assertions.assertEquals(ParsedDialect.SPARK, parseOne("select v1 from t0").getParsedDialect());
    }

    @Test
    public void testStarRocksDialectIsUntouched() {
        connectContext.getSessionVariable().setSqlDialect("StarRocks");
        Assertions.assertEquals(ParsedDialect.STARROCKS, parseOne("select v1 from t0").getParsedDialect());
        connectContext.getSessionVariable().setSqlDialect("no_such_dialect");
        Assertions.assertEquals(ParsedDialect.STARROCKS, parseOne("select v1 from t0").getParsedDialect());
    }
}
