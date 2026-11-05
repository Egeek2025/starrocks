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

package com.glbk.starrocks.udf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sanity checks of the sample generator; the output goes to a temp dir, not into samples/. */
class SampleGeneratorTest {
    /** Minimal RFC 4180 parser (CRLF or LF records, quoted fields with doubled quotes). */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                continue;
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        assertFalse(quoted, "unterminated quote");
        return rows;
    }

    @Test
    void generatesConsistentCsv(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("samples.csv");
        SampleGenerator.write(file);
        List<List<String>> rows = parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(Arrays.asList(SampleGenerator.HEADER), rows.get(0));
        List<List<String>> data = rows.subList(1, rows.size());
        assertTrue(data.size() >= 150 && data.size() <= 300, "rows: " + data.size());

        Set<String> functions = new HashSet<>();
        Set<String> categories = new HashSet<>();
        Set<String> sqls = new HashSet<>();
        int now = 0;
        for (List<String> r : data) {
            assertEquals(6, r.size(), r.toString());
            functions.add(r.get(0));
            assertTrue(r.get(1).startsWith("SELECT " + r.get(0) + "("), r.get(1));
            assertTrue(r.get(1).endsWith(")"), r.get(1));
            assertTrue(sqls.add(r.get(1)), "duplicate sample " + r.get(1));
            boolean same = Boolean.parseBoolean(r.get(4));
            assertEquals(r.get(2).equals(r.get(3)), same, r.toString());
            if (same) {
                assertEquals("", r.get(5), r.toString());
            } else {
                assertFalse(r.get(5).isEmpty() || r.get(5).equals("UNEXPLAINED"), r.toString());
                categories.add(r.get(5));
            }
            if (r.get(0).equals("gl_now")) {
                now++;
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(HiveOracle.FUNCTIONS)), functions);
        assertEquals(1, now, "exactly one gl_now row (its value changes daily)");
        Set<String> expectedCategories = new HashSet<>(DiffClassifier.ALL);
        expectedCategories.add(SampleGenerator.SR_NO_ZERO_ARG_SIGNATURE);
        assertEquals(expectedCategories, categories);
        // quoting round trip for a value that contains a comma
        assertTrue(data.stream().anyMatch(r -> r.get(3).equals("Thu, 18 Apr 2019")));
        // spot checks of the corrected values
        assertTrue(data.stream().anyMatch(r -> r.get(1).equals("SELECT gl_date_diff('20170101','20180201','month')") && r.get(3).equals("-13")));
        assertTrue(data.stream().anyMatch(r -> r.get(1).equals("SELECT gl_date_diff('2019-04-01','2019-03-31','quarter')")
                && r.get(2).equals("0") && r.get(3).equals("1") && r.get(5).equals("QUARTER_FIX")));
    }
}
