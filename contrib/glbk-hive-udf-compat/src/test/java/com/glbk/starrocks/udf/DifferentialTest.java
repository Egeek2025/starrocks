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

import com.glbk.hive.udf.date.gl_date_delta;
import com.glbk.hive.udf.date.gl_date_diff;
import com.glbk.hive.udf.date.gl_date_first;
import com.glbk.hive.udf.date.gl_date_format;
import com.glbk.hive.udf.date.gl_date_last;
import com.glbk.hive.udf.date.gl_how_many_day;
import com.glbk.hive.udf.date.gl_now;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the ORIGINAL Hive UDF classes (compiled against test stubs of Text/UDF) and the StarRocks
 * ports on the same inputs, and requires identical outcomes: the same string, both NULL, or both
 * throwing. Each case runs under several JVM default time zones, since the original code depends on it.
 */
public class DifferentialTest {
    private static final String[] DATES = {
        "20190417", "2019-04-17", "20190417030405", "2019-04-17 03:04:05", "2019-04-17 23:59:59",
        "20200229", "2019-02-28", "2019-12-31", "2019-01-31", "2018-01-01", "2018-02-01", "2017-01-01",
        "20191231235959", "2019-03-10 02:30:00", "2019-11-03 01:30:00", "1970-01-01", "1969-12-31 23:00:00",
        "2019-1-7", "20191332", "abc", "", "2019/04/17", null};
    private static final String[] OFFSETS = {
        "+3", "-1", "3", "0", "day=+3", "day=1,day=-1", "year=+1,month=1,day=1,day=-1", "y=+1,m=1,d=1,d=-1",
        "month=+1", "m=13", "m=0", "hour=+5", "h=13", "h=-25", "n=+90", "minute=59", "s=-1", "second=+86400",
        " day = +3 ", "day=+3,", "", "   ", null, "x=1", "day=abc", "day", "+3,+4"};
    private static final String[] FORMATS = {
        null, "", " ", "yyyyMMdd", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyy-MM-dd HH:mm:ss", "EEE", "yyyy-ww", "bad'"};
    private static final String[] DTYPES = {"year", "y", "quarter", "q", "month", "m", "week", "w", "day", "YEAR", "", null};
    private static final String[] INTERVALS = {
        "year", "y", "quarter", "q", "month", "m", "week", "w", "day", "d", "hour", "h", "minute", "n",
        "second", "s", "xx", "", null};

    private static final List<TimeZone> ZONES = Arrays.asList(
            TimeZone.getTimeZone("Asia/Shanghai"), TimeZone.getTimeZone("UTC"),
            TimeZone.getTimeZone("America/New_York"));

    private static TimeZone savedZone;
    private static PrintStream savedErr;

    @BeforeAll
    static void quietParseErrors() {
        savedZone = TimeZone.getDefault();
        savedErr = System.err;
        // DateUtils.parseByFormat prints a stack trace for every unparsable input.
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
            }
        }));
    }

    @AfterAll
    static void restore() {
        TimeZone.setDefault(savedZone);
        System.setErr(savedErr);
    }

    /** Outcome of one call: a value (possibly null) or the fact that it threw. */
    private static String outcome(java.util.function.Supplier<String> call) {
        try {
            return "V:" + call.get();
        } catch (RuntimeException e) {
            return "THROW";
        }
    }

    private static Text[] texts(String[] args) {
        Text[] r = new Text[args.length];
        for (int i = 0; i < args.length; i++) {
            r[i] = args[i] == null ? null : new Text(args[i]);
        }
        return r;
    }

    private static String str(Text t) {
        return t == null ? null : t.toString();
    }

    private static int compare(String name, List<String[]> cases, Function<String[], String> hive,
                               Function<String[], String> sr) {
        int n = 0;
        for (TimeZone zone : ZONES) {
            TimeZone.setDefault(zone);
            for (String[] args : cases) {
                String expected = outcome(() -> hive.apply(args));
                String actual = outcome(() -> sr.apply(args));
                assertEquals(expected, actual,
                        name + Arrays.toString(args) + " in " + zone.getID());
                n++;
            }
        }
        return n;
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

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void glDateDelta(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, OFFSETS)
                : arity == 3 ? cross(DATES, OFFSETS, FORMATS) : cross(DATES, OFFSETS, FORMATS, FORMATS);
        int n = compare("gl_date_delta", cases,
                a -> str(new gl_date_delta().evaluate(texts(a))), a -> new GlDateDelta().evaluate(a));
        assertTrue(n > 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void glDateDiff(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, DATES)
                : arity == 3 ? cross(DATES, DATES, INTERVALS) : cross(DATES, DATES, INTERVALS, INTERVALS);
        compare("gl_date_diff", cases,
                a -> str(new gl_date_diff().evaluate(texts(a))), a -> new GlDateDiff().evaluate(a));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void glDateFormat(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, FORMATS)
                : cross(DATES, FORMATS, FORMATS);
        compare("gl_date_format", cases,
                a -> str(new gl_date_format().evaluate(texts(a))), a -> new GlDateFormat().evaluate(a));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void glDateFirstLastHowMany(int arity) {
        List<String[]> cases = arity == 1 ? cross(DATES) : arity == 2 ? cross(DATES, DTYPES)
                : cross(DATES, DTYPES, DTYPES);
        compare("gl_date_first", cases,
                a -> str(new gl_date_first().evaluate(texts(a))), a -> new GlDateFirst().evaluate(a));
        compare("gl_date_last", cases,
                a -> str(new gl_date_last().evaluate(texts(a))), a -> new GlDateLast().evaluate(a));
        compare("gl_how_many_day", cases,
                a -> str(new gl_how_many_day().evaluate(texts(a))), a -> new GlHowManyDay().evaluate(a));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void glNow(int arity) {
        // Only date-level formats, so both sides run within the same day; "bad'" checks the error path.
        String[] fmts = {"yyyyMMdd", "yyyy-MM-dd", "yyyy", "bad'", null};
        List<String[]> cases = arity == 0 ? cross() : arity == 1 ? cross(fmts) : cross(fmts, fmts);
        compare("gl_now", cases, a -> str(new gl_now().evaluate(texts(a))), a -> new GlNow().evaluate(a));
        if (arity == 0) {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals(Objects.requireNonNull(str(new gl_now().evaluate())), new GlNowNoArg().evaluate());
        }
    }
}
