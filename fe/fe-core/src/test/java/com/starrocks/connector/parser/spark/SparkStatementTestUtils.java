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

import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.SqlParser;
import org.junit.jupiter.api.Assertions;

/**
 * Helpers of the tests of the statements of the Spark dialect.
 */
final class SparkStatementTestUtils {
    private SparkStatementTestUtils() {
    }

    /**
     * The Spark dialect rejects the statement, and with the downgrade the StarRocks parser can not parse it either.
     * The error mentions why the Spark dialect did not translate it.
     */
    static void assertRejected(String sql, String expectedMessage) {
        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> SqlParser.parse(sql, SparkTestBase.connectContext.getSessionVariable()), sql);
        Assertions.assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
    }

    /**
     * Parses the statement, which must not be handled by the Spark dialect but by the StarRocks parser.
     */
    static StatementBase parseDowngraded(String sql) {
        StatementBase stmt = SparkTestBase.parseOne(sql);
        Assertions.assertEquals(com.starrocks.sql.ast.ParsedDialect.STARROCKS, stmt.getParsedDialect(), sql);
        return stmt;
    }
}
