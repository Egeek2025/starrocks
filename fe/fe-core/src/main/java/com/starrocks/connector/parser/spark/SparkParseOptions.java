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

/**
 * Switches of the Spark grammar that Spark itself drives from SQL configs. The defaults are the Spark 3.5
 * defaults. They are injected into the generated parser as fields, see {@code SparkSqlBaseParser}.
 */
public class SparkParseOptions {
    // spark.sql.legacy.setopsPrecedence.enabled
    private final boolean legacySetopsPrecedence;
    // spark.sql.legacy.exponentLiteralAsDecimal.enabled
    private final boolean legacyExponentLiteralAsDecimal;
    // spark.sql.ansi.enforceReservedKeywords
    private final boolean sqlStandardKeywordBehavior;
    // spark.sql.ansi.doubleQuotedIdentifiers
    private final boolean doubleQuotedIdentifiers;

    public SparkParseOptions(boolean legacySetopsPrecedence, boolean legacyExponentLiteralAsDecimal,
                             boolean sqlStandardKeywordBehavior, boolean doubleQuotedIdentifiers) {
        this.legacySetopsPrecedence = legacySetopsPrecedence;
        this.legacyExponentLiteralAsDecimal = legacyExponentLiteralAsDecimal;
        this.sqlStandardKeywordBehavior = sqlStandardKeywordBehavior;
        this.doubleQuotedIdentifiers = doubleQuotedIdentifiers;
    }

    public static SparkParseOptions defaults() {
        return new SparkParseOptions(false, false, false, false);
    }

    /**
     * Options for a session. There are no spark_* session variables yet, so this returns the defaults.
     */
    public static SparkParseOptions from(SessionVariable sessionVariable) {
        return defaults();
    }

    public boolean isLegacySetopsPrecedence() {
        return legacySetopsPrecedence;
    }

    public boolean isLegacyExponentLiteralAsDecimal() {
        return legacyExponentLiteralAsDecimal;
    }

    public boolean isSqlStandardKeywordBehavior() {
        return sqlStandardKeywordBehavior;
    }

    public boolean isDoubleQuotedIdentifiers() {
        return doubleQuotedIdentifiers;
    }
}
