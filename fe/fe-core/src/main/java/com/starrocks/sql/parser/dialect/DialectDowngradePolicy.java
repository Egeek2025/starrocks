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

package com.starrocks.sql.parser.dialect;

import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.common.UnsupportedException;
import com.starrocks.sql.parser.SqlParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import static com.starrocks.sql.common.UnsupportedException.unsupportedException;

/**
 * What a non-StarRocks dialect does when it cannot parse a statement: with {@code enable_dialect_downgrade}
 * the statement is handed to the StarRocks parser, and only if that fails as well an error mentioning both
 * parsers is raised. Without it the dialect's own error is rethrown.
 */
public final class DialectDowngradePolicy {
    private static final Logger LOG = LogManager.getLogger(DialectDowngradePolicy.class);

    private DialectDowngradePolicy() {
    }

    public static List<StatementBase> rollback(SqlDialect dialect, String sql, SessionVariable sessionVariable,
                                               RuntimeException dialectException) {
        if (sessionVariable.isEnableDialectDowngrade()) {
            return tryParseWithStarRocksDialect(dialect, sql, sessionVariable, dialectException);
        }
        throw dialectException;
    }

    private static List<StatementBase> tryParseWithStarRocksDialect(SqlDialect dialect, String sql,
                                                                    SessionVariable sessionVariable,
                                                                    Exception dialectException) {
        try {
            return SqlParser.parseWithStarRocksDialect(sql, sessionVariable);
        } catch (Exception starRocksException) {
            LOG.warn("StarRocks parse sql [{}] error, cause by {}", sql, starRocksException);
            String message = String.format("%s parser parse sql error: [%s], " +
                    "and StarRocks parser also can not parse: [%s]",
                    dialect.displayName(), dialectException, starRocksException);
            if (dialectException instanceof UnsupportedException) {
                throw unsupportedException(message);
            } else {
                throw new StarRocksPlannerException(ErrorType.USER_ERROR, message);
            }
        }
    }
}
