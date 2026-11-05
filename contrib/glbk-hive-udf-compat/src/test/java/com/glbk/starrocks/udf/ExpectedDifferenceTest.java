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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.glbk.starrocks.udf.HiveOracle.Outcome;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Differential test against the ORIGINAL Hive UDF classes. The outcomes (value / NULL / exception) of the
 * original and the corrected implementation must be identical, except where the difference is explained by
 * an allowed category computed from the inputs (see {@link DiffClassifier}). Runs under Locale.US and the
 * time zones Asia/Shanghai, UTC and America/New_York (the original depends on both).
 */
class ExpectedDifferenceTest {
    private static final String[] DATES = {
        "20190417", "2019-04-17", "20190417030405", "2019-04-17 03:04:05", "2019-04-17 23:59:59",
        "20200229", "2019-02-28", "2019-12-31", "2019-01-31", "2018-01-01", "2018-02-01", "2017-01-01",
        "20191231235959", "2019-03-10 02:30:00", "2019-11-03 01:30:00", "1970-01-01", "1969-12-31 23:00:00",
        "2019-1-7", "20191332", "abc", "", "2019/04/17", null,
        // year boundaries for week logic, quarter edges
        "2018-12-29", "2018-12-30", "2019-01-01", "2019-01-05", "2019-01-06", "2020-12-31", "2021-01-02",
        "2021-01-03", "2019-03-31", "2019-04-01", "2019-06-30", "2019-09-30 12:00:00", "2019-10-01",
        "2019-03-20 03:00:00", "20190230", "1969-12-31 23:30:00", "1969-12-31 23:59:59",
        "99991231"};
    private static final String[] OFFSETS = {
        "+3", "-1", "3", "0", "day=+3", "day=1,day=-1", "year=+1,month=1,day=1,day=-1", "y=+1,m=1,d=1,d=-1",
        "month=+1", "m=13", "m=0", "hour=+5", "h=13", "h=-25", "n=+90", "minute=59", "s=-1", "second=+86400",
        " day = +3 ", "day=+3,", "", "   ", null, "x=1", "day=abc", "day", "+3,+4",
        "d=31", "m=2", "y=2019", "m=2,d=1", "h=5", "h=0", "d=1=2", "day=+3,,day=1", "h= 5", "n=60", "y=0", "d=32"};
    private static final String[] FORMATS = {
        null, "", " ", "yyyyMMdd", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyy-MM-dd HH:mm:ss", "EEE", "yyyy-ww", "bad'"};
    private static final String[] DTYPES = {"year", "y", "quarter", "q", "month", "m", "week", "w", "day", "YEAR", "", null};
    private static final String[] INTERVALS = {
        "year", "y", "quarter", "q", "month", "m", "week", "w", "day", "d", "hour", "h", "minute", "n",
        "second", "s", "xx", "", null, "DAY"};
    private static final String[] ZONES = {"Asia/Shanghai", "UTC", "America/New_York"};

    private static final Map<String, Integer> COUNTS = new TreeMap<>();
    private static TimeZone savedZone;
    private static Locale savedLocale;
    private static PrintStream savedErr;

    @BeforeAll
    static void setUp() {
        savedZone = TimeZone.getDefault();
        savedLocale = Locale.getDefault();
        savedErr = HiveOracle.silenceStderr();
        Locale.setDefault(Locale.US);
    }

    @AfterAll
    static void tearDownAndReport() throws Exception {
        TimeZone.setDefault(savedZone);
        Locale.setDefault(savedLocale);
        System.setErr(savedErr);
        StringBuilder sb = new StringBuilder("Expected-difference counts (all functions, all zones):\n");
        for (Map.Entry<String, Integer> e : COUNTS.entrySet()) {
            sb.append(String.format("  %-20s %d%n", e.getKey(), e.getValue()));
        }
        System.out.print(sb);
        Path out = Paths.get("target", "expected-difference-counts.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        for (String cat : DiffClassifier.ALL) {
            assertTrue(COUNTS.getOrDefault(cat, 0) > 0, "category never exercised: " + cat + "\n" + sb);
        }
        assertTrue(COUNTS.getOrDefault("IDENTICAL", 0) > 0);
    }

    private static List<String[]> cross(String[]... dims) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[0]);
        for (String[] dim : dims) {
            List<String[]> next = new ArrayList<>();
            for (String[] prefix : out) {
                for (String v : dim) {
                    String[] a = Arrays.copyOf(prefix, prefix.length + 1);
                    a[prefix.length] = v;
                    next.add(a);
                }
            }
            out = next;
        }
        return out;
    }

    private static synchronized void count(String key) {
        COUNTS.merge(key, 1, Integer::sum);
    }

    private static void compare(String fn, List<String[]> cases) {
        List<String> unexplained = new ArrayList<>();
        for (String zone : ZONES) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            for (String[] args : cases) {
                Outcome hive = HiveOracle.hive(fn, args);
                Outcome fixed = HiveOracle.fixed(fn, args);
                if (hive.equals(fixed)) {
                    count("IDENTICAL");
                    continue;
                }
                String category = DiffClassifier.classify(fn, args, zone, hive, fixed);
                if (category == null) {
                    if (unexplained.size() < 25) {
                        unexplained.add(fn + Arrays.toString(args) + " in " + zone + ": hive=" + hive + " new=" + fixed);
                    }
                    count("UNEXPLAINED");
                } else {
                    count(category);
                }
            }
        }
        assertTrue(unexplained.isEmpty(), "unexplained differences:\n" + String.join("\n", unexplained));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void glDateDelta(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, OFFSETS)
                : arity == 3 ? cross(DATES, OFFSETS, FORMATS) : cross(DATES, OFFSETS, FORMATS, FORMATS);
        compare("gl_date_delta", cases);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void glDateDiff(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, DATES)
                : arity == 3 ? cross(DATES, DATES, INTERVALS) : cross(DATES, DATES, INTERVALS, INTERVALS);
        compare("gl_date_diff", cases);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void glDateFormat(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, FORMATS)
                : cross(DATES, FORMATS, FORMATS);
        compare("gl_date_format", cases);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void glDateFirstLastHowManyDay(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, DTYPES)
                : cross(DATES, DTYPES, DTYPES);
        compare("gl_date_first", cases);
        compare("gl_date_last", cases);
        compare("gl_how_many_day", cases);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void glNow(int arity) {
        // date-level patterns only, so both sides run within the same day; "bad'" and null check the error path
        String[] fmts = {"yyyyMMdd", "yyyy-MM-dd", "yyyy", "bad'", null};
        List<String[]> cases = arity == 0 ? cross() : arity == 1 ? cross(fmts) : cross(fmts, fmts);
        compare("gl_now", cases);
    }
}
