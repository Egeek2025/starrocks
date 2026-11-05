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

package com.starrocks.connector.parser.trino;

import com.google.common.collect.Lists;
import com.starrocks.connector.trino.TrinoParserUnsupportedException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ast.OriginStatement;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.common.UnsupportedException;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.sql.parser.dialect.DialectDowngradePolicy;
import com.starrocks.sql.parser.dialect.SqlDialect;
import io.trino.sql.parser.StatementSplitter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * The Trino dialect: parses with the Trino parser and translates the Trino AST into StarRocks AST. Only a
 * subset of the statements is supported, the others fall back to the StarRocks parser through
 * {@link DialectDowngradePolicy}.
 */
public class TrinoDialect implements SqlDialect {
    private static final Logger LOG = LogManager.getLogger(TrinoDialect.class);

    public static final String NAME = "trino";

    public static final TrinoDialect INSTANCE = new TrinoDialect();

    private TrinoDialect() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "Trino";
    }

    @Override
    public List<StatementBase> parse(String sql, SessionVariable sessionVariable) {
        List<StatementBase> statements = Lists.newArrayList();
        try {
            StatementSplitter splitter = new StatementSplitter(sql);
            for (int idx = 0; idx < splitter.getCompleteStatements().size(); ++idx) {
                StatementSplitter.Statement statement = splitter.getCompleteStatements().get(idx);
                StatementBase statementBase = TrinoParserUtils.toStatement(statement.statement(),
                        sessionVariable.getSqlMode());
                statementBase.setOrigStmt(new OriginStatement(sql, idx));
                statementBase.setParsedDialect(ParsedDialect.TRINO);
                statements.add(statementBase);
            }
            if (!splitter.getPartialStatement().isEmpty()) {
                StatementBase statement = TrinoParserUtils.toStatement(splitter.getPartialStatement(),
                        sessionVariable.getSqlMode());
                statement.setOrigStmt(new OriginStatement(sql, splitter.getCompleteStatements().size()));
                statement.setParsedDialect(ParsedDialect.TRINO);
                statements.add(statement);
            }
            // Historical behaviour of the Trino dialect: the parser itself switches the session to
            // case-insensitive relation aliases. The Spark dialect does not do this, see Analyzer.analyze.
            if (ConnectContext.get() != null) {
                ConnectContext.get().setRelationAliasCaseInSensitive(true);
            }
        } catch (ParsingException e) {
            // In Trino parser AstBuilder, it could throw ParsingException for unexpected exception,
            // use StarRocks parser to parse now.
            LOG.warn("Trino parse sql [{}] error, cause by {}", sql, e);
            return rollback(sql, sessionVariable, e);
        } catch (io.trino.sql.parser.ParsingException e) {
            // This sql does not use Trino syntax，use StarRocks parser to parse now.
            if (sql.toLowerCase().contains("select")) {
                LOG.warn("Trino parse sql [{}] error, cause by {}", sql, e);
            }
            return rollback(sql, sessionVariable, e);
        } catch (TrinoParserUnsupportedException e) {
            // We only support Trino partial syntax now, and for Trino parser unsupported statement,
            // try to use StarRocks parser to parse
            return rollback(sql, sessionVariable, e);
        } catch (UnsupportedException e) {
            // For unsupported statement, it can not be parsed by trino or StarRocks parser, both parser
            // can not support it now, we just throw the exception here to give user more information
            LOG.warn("Sql [{}] are not supported by trino parser, cause by {}", sql, e);
            throw e;
        }
        if (statements.isEmpty() || statements.stream().anyMatch(Objects::isNull)) {
            return SqlParser.parseWithStarRocksDialect(sql, sessionVariable);
        }
        return statements;
    }

    private List<StatementBase> rollback(String sql, SessionVariable sessionVariable, RuntimeException exception) {
        if (ConnectContext.get() != null) {
            ConnectContext.get().setRelationAliasCaseInSensitive(false);
        }
        return DialectDowngradePolicy.rollback(this, sql, sessionVariable, exception);
    }
}
