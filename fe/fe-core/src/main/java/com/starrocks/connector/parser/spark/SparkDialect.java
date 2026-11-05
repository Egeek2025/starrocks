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

import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ast.OriginStatement;
import com.starrocks.sql.ast.ParseNode;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.dialect.DialectDowngradePolicy;
import com.starrocks.sql.parser.dialect.SqlDialect;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The Spark SQL dialect ({@code set sql_dialect = 'spark'}). Statements are parsed by the Spark grammar and
 * translated to StarRocks AST by {@link SparkAstBuilder}. A statement that cannot be handled falls back to the
 * StarRocks parser through {@link DialectDowngradePolicy}; such a statement keeps
 * {@link ParsedDialect#STARROCKS}. The session is not modified while parsing.
 */
public class SparkDialect implements SqlDialect {
    private static final Logger LOG = LogManager.getLogger(SparkDialect.class);

    public static final String NAME = "spark";

    public static final SparkDialect INSTANCE = new SparkDialect();

    private SparkDialect() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "Spark";
    }

    @Override
    public List<StatementBase> parse(String sql, SessionVariable sessionVariable) {
        try {
            List<SparkSqlBaseParser.SingleStatementContext> contexts =
                    new SparkParser(sessionVariable).parseStatements(sql);
            if (contexts.isEmpty()) {
                // nothing but whitespace or comments, let the StarRocks parser decide
                throw new SparkUnsupportedException("No statement found");
            }
            SparkAstBuilder builder = new SparkAstBuilder(sessionVariable);
            List<StatementBase> statements = new ArrayList<>(contexts.size());
            for (int idx = 0; idx < contexts.size(); ++idx) {
                ParseNode node = builder.visit(contexts.get(idx));
                if (!(node instanceof StatementBase)) {
                    throw SparkAstBuilder.unsupported(contexts.get(idx));
                }
                StatementBase statement = (StatementBase) node;
                statement.setOrigStmt(new OriginStatement(sql, idx));
                statement.setParsedDialect(ParsedDialect.SPARK);
                statements.add(statement);
            }
            return statements;
        } catch (SparkUnsupportedException | ParsingException e) {
            LOG.debug("Spark parse sql [{}] error, cause by {}", sql, e.getMessage());
            return DialectDowngradePolicy.rollback(this, sql, sessionVariable, e);
        }
    }
}
