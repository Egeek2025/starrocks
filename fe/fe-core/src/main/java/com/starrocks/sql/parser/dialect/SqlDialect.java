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

import java.util.List;

/**
 * A SQL dialect that can be selected through the {@code sql_dialect} session variable.
 *
 * <p>An implementation parses the statements of a SQL text into StarRocks AST. When it cannot handle the text
 * (syntax error or a statement it does not translate) it should hand over to
 * {@link DialectDowngradePolicy#rollback} which falls back to the StarRocks parser if
 * {@code enable_dialect_downgrade} is on, or rethrows the dialect's own error otherwise.
 */
public interface SqlDialect {
    /**
     * @return the canonical lower case name, i.e. the value of {@code sql_dialect}, e.g. "spark".
     */
    String name();

    /**
     * @return the name used in user facing messages, e.g. "Spark".
     */
    String displayName();

    /**
     * Parses {@code sql} into statements. Must not mutate session state such as {@code ConnectContext}.
     */
    List<StatementBase> parse(String sql, SessionVariable sessionVariable);
}
