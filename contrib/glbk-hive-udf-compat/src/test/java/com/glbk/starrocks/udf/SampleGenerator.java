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

import com.glbk.starrocks.udf.HiveOracle.Outcome;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Generates {@code samples/samples.csv}: representative calls, the result of the ORIGINAL Hive UDF (JVM default
 * Locale.US, time zone Asia/Shanghai), the result of the corrected StarRocks UDF, and the explained difference
 * category. Run from the module directory:
 * <pre>java -cp target/classes:target/test-classes com.glbk.starrocks.udf.SampleGenerator samples/samples.csv</pre>
 * (the main method needs the test classes because the original Hive sources are compiled with the tests).
 */
public final class SampleGenerator {
    /** Zero-argument call of a function that StarRocks only registers as varargs with one fixed argument. */
    static final String SR_NO_ZERO_ARG_SIGNATURE = "SR_NO_ZERO_ARG_SIGNATURE";

    static final String[] HEADER = {"function", "sql", "hive_result", "new_result", "same", "diff_category"};
    private static final String ZONE = "Asia/Shanghai";
    private static final String N = null;

    private SampleGenerator() {
    }

    /** One sample call. */
    private static final class Sample {
        final String fn;
        final String[] args;

        Sample(String fn, String[] args) {
            this.fn = fn;
            this.args = args;
        }
    }

    private static void add(List<Sample> out, String fn, String... args) {
        out.add(new Sample(fn, args));
    }

    private static List<Sample> samples() {
        List<Sample> s = new ArrayList<>();
        String f = "gl_date_delta";
        // arity, null, default output styles
        add(s, f, "20190417");
        add(s, f, "2019-1-7");
        add(s, f, "20190417", "+1", "yyyyMMdd", "extra");
        add(s, f, N, "+1");
        add(s, f, "20190417", "+1");
        add(s, f, "2019-04-17", "+1");
        add(s, f, "20190417030405", "+1");
        add(s, f, "2019-04-17 03:04:05", "+1");
        add(s, f, "20190417", N);
        add(s, f, "2019-04-17 03:04:05", "   ");
        // bare numbers are day offsets (unsigned = set the day)
        add(s, f, "20190417", "-1");
        add(s, f, "20190417", "5");
        add(s, f, "20190417", "0");
        add(s, f, "20190417", "32");
        // README examples
        add(s, f, "2018-09-06", "y=+1,m=1,d=1,d=-1");
        add(s, f, "20180102030405", "day=+3");
        add(s, f, "2018-01-02", "day=+3", "yyyyMMdd");
        add(s, f, "20190417", "month=+1,day=1,day=-1");
        add(s, f, "2019-04-17 15:00:00", "h=13");
        // add units and end-of-month clamping
        add(s, f, "20190417", "year=+2");
        add(s, f, "20190417", "m=-4");
        add(s, f, "2019-04-17 03:04:05", "hour=+30");
        add(s, f, "2019-04-17 03:04:05", "n=+90");
        add(s, f, "2019-04-17 03:04:05", "second=+86400");
        add(s, f, "20190131", "m=+1");
        add(s, f, "20200229", "y=+1");
        // set units
        add(s, f, "20190417", "y=2020");
        add(s, f, "20190417", "m=2");
        add(s, f, "20190417", "d=1");
        add(s, f, "2019-04-17 03:04:05", "h=0");
        add(s, f, "2019-04-17 03:04:05", "h=23");
        add(s, f, "2019-04-17 03:04:05", "n=30,s=15");
        add(s, f, "2019-04-17 15:00:00", "h=5");
        add(s, f, "2019-04-17 03:00:00", "h=17");
        // set value out of range / not valid for the date
        add(s, f, "20190417", "m=13");
        add(s, f, "20190417", "m=0");
        add(s, f, "20190417", "d=32");
        add(s, f, "2019-04-17 03:04:05", "h=24");
        add(s, f, "2019-04-17 03:04:05", "n=60");
        add(s, f, "20190417", "d=31");
        add(s, f, "20200229", "y=2019");
        add(s, f, "20190131", "m=2");
        add(s, f, "20190131", "m=2,d=1");
        add(s, f, "99991231", "+1");
        // parameter errors
        add(s, f, "20190417", "x=1");
        add(s, f, "20190417", "Y=1");
        add(s, f, "20190417", "quarter=1");
        add(s, f, "20190417", "day=1,,month=1");
        add(s, f, "20190417", "day=abc");
        add(s, f, "20190417", "day");
        add(s, f, "20190417", "+3,+4");
        add(s, f, "20190417", "day=1=2");
        add(s, f, "20190417", " day = +3 ");
        add(s, f, "20190417", "day=+3,");
        // invalid dates
        for (String bad : new String[] {"2019-1-7", "20191332", "abc", "", "2019/04/17", "20190230"}) {
            add(s, f, bad, "+1");
        }
        // output formats
        add(s, f, "20190417", "+1", "yyyy-MM-dd");
        add(s, f, "20190417", "+1", "EEE, d MMM yyyy");
        add(s, f, "20190417", "+1", "yyyy-MM-dd HH:mm");
        add(s, f, "20190417", "+1", "");
        add(s, f, "2019-04-17 03:04:05", "+1", " ");
        add(s, f, "20190417", "+1", N);
        add(s, f, "20190417", "+1", "bad'");

        f = "gl_date_diff";
        add(s, f);
        add(s, f, "20190417");
        add(s, f, "20190417", "20190416", "day", "x");
        add(s, f, "20180101", "20180201");
        add(s, f, "2018-02-01", "2018-01-01");
        add(s, f, N, "20190417");
        add(s, f, "20190417", N, "day");
        add(s, f, "20170101", "20180201", "month");
        add(s, f, "20190101", "20181231", "year");
        add(s, f, "20190101", "20181231", "y");
        add(s, f, "20190201", "20190131", "m");
        add(s, f, "2019-04-01", "2019-03-31", "quarter");
        add(s, f, "2019-05-01", "2019-03-31", "quarter");
        add(s, f, "2019-06-30", "2019-04-01", "q");
        add(s, f, "2020-01-01", "2019-12-31", "q");
        add(s, f, "2019-12-31", "2019-01-01", "q");
        add(s, f, "20190417", "20190414", "week");
        add(s, f, "20190421", "20190420", "w");
        add(s, f, "20190101", "20181229", "w");
        add(s, f, "20190101", "20181230", "w");
        add(s, f, "20191231", "20190101", "w");
        add(s, f, "20200615", "20190615", "w");
        add(s, f, "20190415", "20181231", "week");
        add(s, f, "20200101", "20190101", "w");
        add(s, f, "2021-01-03", "2021-01-02", "week");
        add(s, f, "2019-04-17 07:00:00", "2019-04-16 09:00:00", "day");
        add(s, f, "2019-04-17 00:00:00", "2019-04-16 23:59:59", "d");
        add(s, f, "2019-04-17 23:59:59", "2019-04-17 00:00:00", "d");
        add(s, f, "20200101", "20190101", "d");
        add(s, f, "2019-04-17 03:00:00", "2019-04-17 02:59:59", "hour");
        add(s, f, "2019-04-18 03:10:00", "2019-04-17 02:50:00", "h");
        add(s, f, "2019-04-17 03:05:00", "2019-04-17 03:04:59", "minute");
        add(s, f, "2019-04-17 03:05:59", "2019-04-17 03:05:00", "n");
        add(s, f, "2019-04-17 03:05:01", "2019-04-17 03:05:00", "second");
        add(s, f, "20190418", "20190417", "s");
        add(s, f, "1969-12-31 23:30:00", "1969-12-31 22:00:00", "h");
        add(s, f, "1969-12-31 23:59:59", "1969-12-31 00:00:00", "d");
        add(s, f, "1988-04-17 04:00:00", "1988-04-17 01:00:00", "h");
        add(s, f, "20190417", "20190416", "xx");
        add(s, f, "20190417", "20190416", "DAY");
        add(s, f, "20190417", "20190416", "");
        add(s, f, "20190417", "20190416", N);
        add(s, f, "abc", "20190416", "day");
        add(s, f, "20190417", "20191332", "month");
        add(s, f, "2019-1-7", "20190416");

        f = "gl_date_format";
        add(s, f);
        add(s, f, "20190417", "yyyy", "x");
        add(s, f, N);
        add(s, f, "2019-04-17 03:04:05");
        add(s, f, "20190417", "yyyy-MM-dd");
        add(s, f, "20190417030405", "yyyy-MM-dd HH:mm:ss");
        add(s, f, "2019-04-17 03:04:05", "yyyyMMddHHmmss");
        add(s, f, "20190417", "EEEE, MMMM d, yyyy");
        add(s, f, "20190417", "yyyy-ww");
        add(s, f, "20191229", "yyyy-ww");
        add(s, f, "20190417150405", "hh:mm a");
        add(s, f, "20190417", "");
        add(s, f, "20191332", "yyyy-MM-dd");
        add(s, f, "20191332");
        add(s, f, "abc");
        add(s, f, "", "yyyy");
        add(s, f, "2019-1-7");
        add(s, f, "2019/04/17");
        add(s, f, "1988-04-17 02:30:00", "yyyy-MM-dd HH:mm:ss");
        add(s, f, "20190417", N);
        add(s, f, "20190417", "bad'");
        add(s, f, "20190417", "yyyy-MM-dd bbb");

        f = "gl_now";
        add(s, f, "bad'");

        f = "gl_how_many_day";
        add(s, f, "20190417");
        add(s, f, N, "year");
        for (String dt : new String[] {"year", "y", "quarter", "q", "month", "m", "week", "w"}) {
            add(s, f, "2019-04-17", dt);
        }
        add(s, f, "20190101", "year");
        add(s, f, "20201231", "y");
        add(s, f, "20190401", "quarter");
        add(s, f, "20190331", "quarter");
        add(s, f, "20191231", "q");
        add(s, f, "2019-03-19 23:59:59", "quarter");
        add(s, f, "1969-12-31 23:00:00", "quarter");
        add(s, f, "20190531", "month");
        add(s, f, "20190414", "week");
        add(s, f, "20190420", "w");
        add(s, f, "20191332", "year");
        add(s, f, "abc", "month");
        add(s, f, "20190417", "day");
        add(s, f, "20190417", "YEAR");
        add(s, f, "20190417", "");
        add(s, f, "20190417", N);
        add(s, f, "20190417", "year", "x");

        for (String fn : new String[] {"gl_date_first", "gl_date_last"}) {
            add(s, fn, "20190417");
            add(s, fn, N, "year");
            for (String dt : new String[] {"year", "y", "quarter", "q", "month", "m", "week", "w"}) {
                add(s, fn, "2019-04-17 12:00:00", dt);
            }
            add(s, fn, "20190930", "quarter");
            add(s, fn, "20200215", "month");
            add(s, fn, "20190215", "m");
            add(s, fn, "20190414", "week");
            add(s, fn, "20190420", "week");
            add(s, fn, "20190101", "week");
            add(s, fn, "20181230", "w");
            add(s, fn, "99991231", "week");
            add(s, fn, "20191332", "month");
            add(s, fn, "abc", "year");
            add(s, fn, "", "month");
            add(s, fn, "20190417", "day");
            add(s, fn, "20190417", "Month");
            add(s, fn, "20190417", "");
            add(s, fn, "20190417", N);
            add(s, fn, "20190417", "year", "x");
        }
        return s;
    }

    private static String sqlLiteral(String v) {
        return v == null ? "NULL" : "'" + v.replace("'", "''") + "'";
    }

    private static String sql(Sample s) {
        StringBuilder sb = new StringBuilder("SELECT ").append(s.fn).append('(');
        for (int i = 0; i < s.args.length; i++) {
            sb.append(i == 0 ? "" : ",").append(sqlLiteral(s.args[i]));
        }
        return sb.append(')').toString();
    }

    /** Computes all rows (without header); the environment is pinned to Locale.US and Asia/Shanghai. */
    static List<String[]> generate() {
        TimeZone savedZone = TimeZone.getDefault();
        Locale savedLocale = Locale.getDefault();
        PrintStream savedErr = HiveOracle.silenceStderr();
        try {
            Locale.setDefault(Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
            List<String[]> rows = new ArrayList<>();
            for (Sample s : samples()) {
                Outcome hive = HiveOracle.hive(s.fn, s.args);
                Outcome fixed = HiveOracle.fixed(s.fn, s.args);
                if (s.args.length == 0 && !"gl_now".equals(s.fn)) {
                    // Hive returns NULL, but StarRocks cannot even resolve the call: a varargs UDF needs at
                    // least one fixed argument and only gl_now() has a separate zero-argument registration.
                    rows.add(new String[] {s.fn, sql(s), hive.marker(), "ERROR", "false", SR_NO_ZERO_ARG_SIGNATURE});
                    continue;
                }
                boolean same = hive.equals(fixed);
                String category = same ? "" : DiffClassifier.classify(s.fn, s.args, ZONE, hive, fixed);
                rows.add(new String[] {s.fn, sql(s), hive.marker(), fixed.marker(), Boolean.toString(same),
                    category == null ? "UNEXPLAINED" : category});
            }
            return rows;
        } finally {
            Locale.setDefault(savedLocale);
            TimeZone.setDefault(savedZone);
            System.setErr(savedErr);
        }
    }

    /** RFC 4180 field quoting. */
    static String csvField(String v) {
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) {
            return v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    static String toCsv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        List<String[]> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(rows);
        for (String[] row : all) {
            for (int i = 0; i < row.length; i++) {
                sb.append(i == 0 ? "" : ",").append(csvField(row[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static void write(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, toCsv(generate()).getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws IOException {
        Path out = Paths.get(args.length > 0 ? args[0] : "samples/samples.csv");
        write(out);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}
