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

import com.starrocks.connector.parser.trino.TrinoDialect;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.dialect.SqlDialectRegistry;
import com.starrocks.sql.parser.dialect.StarRocksDialect;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Parser level tests that need no cluster.
 */
public class SparkParserTest {
    @Test
    public void testRegistry() {
        Assertions.assertSame(SparkDialect.INSTANCE, SqlDialectRegistry.get("spark"));
        Assertions.assertSame(SparkDialect.INSTANCE, SqlDialectRegistry.get("SPARK"));
        Assertions.assertSame(TrinoDialect.INSTANCE, SqlDialectRegistry.get("Trino"));
        Assertions.assertSame(StarRocksDialect.INSTANCE, SqlDialectRegistry.get("StarRocks"));
        Assertions.assertSame(StarRocksDialect.INSTANCE, SqlDialectRegistry.get("unknown"));
        Assertions.assertSame(StarRocksDialect.INSTANCE, SqlDialectRegistry.get(null));
    }

    @Test
    public void testDispatchHasNoConflictingRules() {
        // register() throws when two builders handle the same rule
        Assertions.assertNotNull(new SparkAstBuilder());
    }

    @Test
    public void testSplitStatements() {
        SparkParser parser = new SparkParser(SparkParseOptions.defaults());
        List<SparkSqlBaseParser.SingleStatementContext> statements =
                parser.parseStatements("select 1; select ';' ; /* c; */ select 2 -- x;\n;");
        Assertions.assertEquals(3, statements.size());
        Assertions.assertTrue(parser.parseStatements("  -- only a comment\n").isEmpty());
        Assertions.assertTrue(parser.parseStatements(";;").isEmpty());
    }

    @Test
    public void testSyntaxErrorPosition() {
        SparkParser parser = new SparkParser(SparkParseOptions.defaults());
        ParsingException e = Assertions.assertThrows(ParsingException.class,
                () -> parser.parseStatements("select 1\nfrom"));
        Assertions.assertTrue(e.getMessage().contains("line 2"), e.getMessage());
        Assertions.assertThrows(ParsingException.class, () -> parser.parseStatements("select /* open"));
    }

    @Test
    public void testParseOptions() {
        // Spark 3.5 defaults
        SparkParseOptions options = SparkParseOptions.from(new SessionVariable());
        Assertions.assertFalse(options.isLegacySetopsPrecedence());
        Assertions.assertFalse(options.isLegacyExponentLiteralAsDecimal());
        Assertions.assertFalse(options.isSqlStandardKeywordBehavior());
        Assertions.assertFalse(options.isDoubleQuotedIdentifiers());

        // INTERSECT binds tighter than UNION unless the legacy flag is on
        SparkParser standard = new SparkParser(options);
        String sql = "select 1 union select 2 intersect select 3";
        SparkSqlBaseParser.SingleStatementContext tree = standard.parseStatements(sql).get(0);
        SparkParser legacy = new SparkParser(new SparkParseOptions(true, false, false, false));
        Assertions.assertNotEquals(tree.toStringTree(), legacy.parseStatements(sql).get(0).toStringTree());
    }

    @Test
    public void testDoubleQuotedStringIsLiteralByDefault() {
        SparkDialect dialect = SparkDialect.INSTANCE;
        SessionVariable sessionVariable = new SessionVariable();
        sessionVariable.setSqlDialect("spark");
        StatementBase stmt = dialect.parse("select \"abc\"", sessionVariable).get(0);
        Assertions.assertTrue(stmt instanceof QueryStatement);
        Assertions.assertEquals(ParsedDialect.SPARK, stmt.getParsedDialect());
    }

    @Test
    public void testUnescapeSqlString() {
        Assertions.assertEquals("a\tb\nc", SparkParserUtils.unescapeSqlString("a\\tb\\nc"));
        Assertions.assertEquals("it's", SparkParserUtils.unescapeSqlString("it\\'s"));
        Assertions.assertEquals("é", SparkParserUtils.unescapeSqlString("\\u00e9"));
        Assertions.assertEquals("A", SparkParserUtils.unescapeSqlString("\\101"));
        Assertions.assertEquals("100\\%", SparkParserUtils.unescapeSqlString("100\\%"));
        Assertions.assertEquals("\\", SparkParserUtils.unescapeSqlString("\\\\"));
    }

    @Test
    public void testSelectIsTranslatedWithoutCluster() {
        SessionVariable sessionVariable = new SessionVariable();
        sessionVariable.setSqlDialect("spark");
        List<StatementBase> statements = SparkDialect.INSTANCE.parse(
                "with c as (select a from t) select c.a, count(*) over (partition by b order by a) "
                        + "from c left semi join u on c.a = u.a where c.a in (1, 2) group by rollup(a, b) "
                        + "order by 1 desc nulls last limit 3 offset 1", sessionVariable);
        Assertions.assertEquals(1, statements.size());
        Assertions.assertTrue(statements.get(0) instanceof QueryStatement);
    }
}
