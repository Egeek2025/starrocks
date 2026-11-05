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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Guards the internal SQL of StarRocks (MV definitions, view definitions, generated statements) against the
 * dialect of the user session: it must be parsed as StarRocks SQL. Only code that parses the SQL of a user may call
 * {@code SqlParser.parse} with a session variable, because that honors {@code sql_dialect}.
 */
public class SparkStatementDialectGuardTest {
    // files that parse SQL written by a user, or choose the dialect on purpose
    private static final Set<String> USER_SQL = Set.of(
            "ConnectProcessor.java", "ExecuteSqlAction.java", "ArrowFlightSqlConnectProcessor.java",
            "ArrowFlightSqlServiceImpl.java", "ExecuteExceptionHandler.java", "QueryDumper.java",
            "TranslateExecutor.java",
            // the task definition is the text of its creator, which dialect is a product decision (P0-05 #11)
            "SqlTaskRunProcessor.java",
            // the view definition is parsed in the dialect of the type of the view, see ConnectorView
            "ConnectorView.java", "HiveView.java",
            // parse with a default session variable
            "FilePipeSource.java", "DesensitizedSQLBuilder.java");

    private static final Pattern PARSE_CALL = Pattern.compile("SqlParser\\s*\\.\\s*parse\\s*\\(([^;]{0,200})");

    @Test
    public void testNoInternalSqlIsParsedWithTheDialectOfTheSession() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"), "src", "main", "java");
        Assumptions.assumeTrue(Files.isDirectory(root), "sources not found: " + root);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (USER_SQL.contains(file.getFileName().toString())) {
                    continue;
                }
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher matcher = PARSE_CALL.matcher(content);
                while (matcher.find()) {
                    if (matcher.group(1).contains("ariable")) {
                        violations.add(root.relativize(file).toString());
                        break;
                    }
                }
            }
        }
        Assertions.assertTrue(violations.isEmpty(),
                "SqlParser.parse with a session variable honors sql_dialect, use parseWithStarRocksDialect or "
                        + "parseOneWithStarRocksDialect for internal SQL: " + violations);
    }
}
