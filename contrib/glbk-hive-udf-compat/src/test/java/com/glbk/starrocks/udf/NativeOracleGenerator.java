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

import com.glbk.starrocks.udf.core.GlDates;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates {@code native/oracle.tsv}, the expected results of the Java reference implementation
 * ({@link GlDates}) over large systematic grids. The native C++ port (be/src/exprs/glbk_date_core.cpp) must
 * reproduce every line; see native/README.md.
 *
 * <p>Line format (tab separated): {@code function, arity, arg1..argN, expected}. An argument is {@code \N}
 * for SQL NULL, otherwise the text with {@code \\ \t \n \r} and {@code \xHH} (other control chars) escaped;
 * non-ASCII text is raw UTF-8. {@code expected} is {@code N} (NULL), {@code E} (IllegalArgumentException) or
 * {@code V} followed by the escaped value.
 *
 * <pre>java -cp target/classes:target/test-classes com.glbk.starrocks.udf.NativeOracleGenerator native/oracle.tsv [--quick]</pre>
 * ({@code --quick} uses sparse grids, about 50k lines, for smoke runs.)
 */
public final class NativeOracleGenerator {
    private static final String N = null;

    private final Writer out;
    private final boolean quick;
    private long lines;
    private long unexpected;
    private final StringBuilder sb = new StringBuilder(256);

    private NativeOracleGenerator(Writer out, boolean quick) {
        this.out = out;
        this.quick = quick;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: NativeOracleGenerator <oracle.tsv> [--quick]");
            System.exit(2);
        }
        boolean quick = args.length > 1 && args[1].equals("--quick");
        long t0 = System.nanoTime();
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(Paths.get(args[0])), StandardCharsets.UTF_8), 1 << 20)) {
            NativeOracleGenerator g = new NativeOracleGenerator(w, quick);
            g.generate();
            System.err.println("NativeOracleGenerator: " + g.lines + " lines, " + g.unexpected
                    + " unexpected exceptions, " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
    }

    // ------------------------------------------------------------------ output

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                b.append("\\\\");
            } else if (c == '\t') {
                b.append("\\t");
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c < 0x20 || c == 0x7F) {
                b.append("\\x").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String call(String fn, String[] a) {
        switch (fn) {
            case "gl_date_delta":
                return GlDates.dateDelta(a);
            case "gl_date_diff":
                return GlDates.dateDiff(a);
            case "gl_date_format":
                return GlDates.dateFormat(a);
            case "gl_how_many_day":
                return GlDates.howManyDay(a);
            case "gl_date_first":
                return GlDates.dateFirst(a);
            case "gl_date_last":
                return GlDates.dateLast(a);
            default:
                throw new IllegalStateException(fn);
        }
    }

    private void emit(String fn, String... args) throws IOException {
        String expected;
        try {
            String r = call(fn, args.clone());
            expected = r == null ? "N" : "V" + esc(r);
        } catch (IllegalArgumentException e) {
            expected = "E";
        } catch (RuntimeException e) {
            unexpected++;
            if (unexpected <= 20) {
                System.err.println("unexpected " + e + " for " + fn + " " + java.util.Arrays.toString(args));
            }
            expected = "E";
        }
        sb.setLength(0);
        sb.append(fn).append('\t').append(args.length);
        for (String a : args) {
            sb.append('\t');
            if (a == null) {
                sb.append("\\N");
            } else {
                sb.append(esc(a));
            }
        }
        sb.append('\t').append(expected).append('\n');
        out.append(sb);
        lines++;
    }

    // ------------------------------------------------------------------ input grids

    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }

    private static String p2(int v) {
        return v < 10 ? "0" + v : Integer.toString(v);
    }

    private static String d8(int y, int m, int d) {
        String ys = Integer.toString(y);
        return rep("0", 4 - ys.length()) + ys + p2(m) + p2(d);
    }

    private static String dash(String digits) {
        String r = digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
        if (digits.length() == 14) {
            r += " " + digits.substring(8, 10) + ":" + digits.substring(10, 12) + ":" + digits.substring(12, 14);
        }
        return r;
    }

    private static <T> List<T> pick(List<T> l, int n) {
        if (l.size() <= n) {
            return l;
        }
        List<T> r = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            r.add(l.get((int) ((long) i * l.size() / n)));
        }
        return r;
    }

    private static final String[] TIMES = {"000000", "235959", "120000", "030405", "115959", "120001", "235900",
        "010101", "000001", "230000", "123456", "060708"};

    private static List<String> validEightDigit() {
        int[] years = {1, 4, 100, 400, 1000, 1900, 1999, 2000, 2001, 2019, 2020, 2023, 2024, 2100, 9996, 9999};
        List<String> r = new ArrayList<>();
        for (int y : years) {
            boolean leap = LocalDate.of(y, 1, 1).isLeapYear();
            int[][] md = {{1, 1}, {1, 2}, {1, 31}, {2, 28}, {2, 29}, {3, 1}, {3, 31}, {4, 30}, {6, 30}, {7, 4},
                {10, 31}, {12, 30}, {12, 31}};
            for (int[] x : md) {
                if (x[0] == 2 && x[1] == 29 && !leap) {
                    continue;
                }
                r.add(d8(y, x[0], x[1]));
            }
        }
        return r;
    }

    private static final String[] INVALID_DATES = {"", " ", "abc", "20190230", "20190229", "20191301", "20190001",
        "20190100", "20190132", "20190431", "00000101", "00000000", "99999999", "20190417240000", "20190417236000",
        "20190417235960", "2019041", "201904171", "2019041712", "20190417123", "201904171234", "2019041712345",
        "201904171234567", "2019041712345678", "2019/04/17", "2019.04.17", "2019-4-17", "2019-04-1", "+2019041",
        "-20190417", "20190417 ", " 20190417", "\t20190417", "20190417\n", "20190417 ", "２０１９"
        + "０４１７", "٢٠١٩٠٤١٧", "2019O417", "2019041l",
        "2019-04-17T03:04:05", "20190417T030405", "2019-04-17 03:04:05.123", "2019-04-17 3:4:5",
        "2019-04-17 24:00:00", "2019-04-17 23:60:00", "2019-04-17 23:59:60", "0001-01-01 00:00:00x", "x20190417",
        "20190417x", "2019 04 17 03 04 05 06", "é", "年", "null", "NULL", "0", "00000000", "00010100",
        "00010001", "10000101", "99991232", "2019-02-29", "1900-02-29", "2100-02-29", "20000229000000x"};

    private static List<String> weirdShapes() {
        List<String> r = new ArrayList<>();
        String[] shapes = {"2019-04-17", "2019-04-17 03:04:05", "2019:04:17", " 2019 04 17 ", "2019-04-17-03-04-05",
            "20190417 030405", "2019-04-17 030405", "2019 04 17 03:04:05", "  2019--04::17  ", "2-0-1-9-0-4-1-7",
            "- - : 20190417 : - -", "20190417030405", "2019-04-17 03:04:05 ", "2019:04:17 03-04-05", "20 19 04 17",
            "0001-01-01", "9999-12-31 23:59:59", "0001-01-01 00:00:00", "9999-12-31", "   ", "-", ":", " - : "};
        for (String s : shapes) {
            r.add(s);
        }
        return r;
    }

    /** Representative inputs: valid 8/14 digit, separators, invalid, NULL (as the last element). */
    private List<String> smallDates() {
        List<String> core8 = validEightDigit();
        List<String> s = new ArrayList<>(core8);
        for (int i = 0; i < core8.size(); i++) {
            s.add(core8.get(i) + TIMES[i % TIMES.length]);
        }
        List<String> base = new ArrayList<>(s);
        for (int i = 0; i < base.size(); i += 3) {
            s.add(dash(base.get(i)));
        }
        s.addAll(weirdShapes());
        for (String x : INVALID_DATES) {
            s.add(x);
        }
        s.add(N);
        return s;
    }

    private static final String[] OFFSETS = {
        // add / bare
        "+1", "-1", "+0", "-0", "+7", "-7", "+30", "+31", "+365", "-365", "+366", "+3650", "-3650", "+36500",
        "+2147483647", "-2147483648", "+2147483648", "-2147483649", "+99999999999", "+000000000000000000003",
        "-0003", "+01", "1", "5", "15", "28", "29", "30", "31", "0", "32", "01", "001", "-5", "007", " 5", "5 ",
        "  +3  ", "+ 3", "+-3", "++3", "3 3", "1e3", "+", "-", "x", "٣", "+٣", " +3",
        // keyed adds
        "day=+3", "d=+3", "day=-3", "DAY=1", "Day=1", "days=1", "day=+3,", "day=+3,,", "day=-1,month=+1",
        "y=+1", "year=+1", "y=-1", "y=+10000", "y=-10000", "y=-2018", "y=-2019", "y=+7980", "y=+7981",
        "y=+999999999", "y=-999999999", "y=+1000000000", "y=+998000000,y=+1999999", "y=+999999998,y=-999990000",
        "m=+1", "m=-1", "m=+12", "m=-12", "m=+13", "m=+1200", "m=-1200", "m=+2147483647", "m=-2147483648",
        "m=+119880", "m=+119881", "d=+2147483647", "d=-2147483648", "d=+365243219162", "h=+1", "h=-1", "h=+24",
        "h=-24", "h=+25", "h=+2147483647", "h=-2147483648", "n=+1", "n=-1", "n=+1440", "n=-1440", "n=+2147483647",
        "n=-2147483648", "s=+1", "s=-1", "s=+86400", "s=-86400", "s=+2147483647", "s=-2147483648",
        "hour=+30", "minute=+90", "second=+3600", "h=+1,n=+1,s=+1", "h=-1,n=-1,s=-1", "y=+1,m=+1,d=+1",
        "y=-1,m=-1,d=-1", "d=+1,d=+1,d=-1", "y=+1,y=-1", "y=+4,m=+1,d=+30,h=+25",
        // sets
        "y=2000", "y=1", "y=9999", "y=0", "y=10000", "y=2019", "y=2020", "y=1900", "m=1", "m=2", "m=4", "m=12",
        "m=0", "m=13", "d=1", "d=15", "d=28", "d=29", "d=30", "d=31", "d=0", "d=32", "h=0", "h=1", "h=12", "h=13",
        "h=23", "h=24", "n=0", "n=30", "n=59", "n=60", "s=0", "s=30", "s=59", "s=60", "hour=5", "minute=5",
        "second=5", "year=2000", "month=2", "h=05", "h=+5,h=5", "h=5,h=+5", "y=0001", "y=09999", "m=00000000002",
        // combinations (set items resolve together at the next add or the end)
        "y=2019,m=2,d=29", "y=2020,m=2,d=29", "m=2,d=29", "m=2,d=30", "m=2,d=1", "m=4,d=31", "d=31,m=4",
        "d=31,m=+0", "d=31,m=+1", "d=31,m=+1,d=1", "m=2,y=+1,d=1", "y=2020,m=2,d=29,y=+1", "d=1,d=-1",
        "m=1,d=1,d=-1", "y=+1,m=1,d=1,d=-1", "m=3,d=1", "m=3,d=31", "m=2,d=28,d=+1", "d=29,m=2,y=2021",
        "d=29,m=2,y=2021,y=+0", "d=29,m=2,y=2020,y=+0", "y=9999,m=12,d=31,d=+1", "y=1,m=1,d=1,d=-1",
        "y=1,m=1,d=1,y=-1", "y=9999,m=12,d=31,y=+1,y=-1", "y=9999,y=+1,y=-1", "y=1,y=-1,y=+1", "d=+1,y=9999",
        "h=24,d=+1", "h=5,d=+1,h=6", "n=59,n=+1", "s=59,s=+1", "h=23,n=59,s=59,s=+1", "y=2000,y=+5,m=2,d=29",
        "y=2000,m=2,d=29,m=+12", "y=2000,m=2,d=29,m=+48", "y=2000,m=2,d=29,y=+4", "y=2000,m=2,d=29,y=+1",
        "m=+1,m=+1,m=+1,m=+1", "m=+1,d=31", "m=+1,d=31,m=+1", "d=+1,m=2,d=30",
        // whitespace, empties, malformed
        " day = +3 ", "\tday=+3", "day=\t+3", "day=+3\n", "day=+3,", "day=+3,,", "day=+3, ", ",", ",,", " , ",
        ",day=+1", "day=+1,,day=+1", "day=+1, ", " ,day=+1", "day=", "=", "=1", "d==1", "d=1=2", "day", "x=1",
        "Y=1", "quarter=1", "week=1", "w=1", "q=1", "D=1", "M=1", "H=1", "N=1", "S=1", "+3,+4", "1,2", "d=+3;m=+1",
        "d = + 3", "d=1.5", "d=1e1", "d=0x1", "d=+ 3", "d=--3", "d= -3", " d= 3 ", "d=٣", "=+1", " =+1",
        "day=+1,=", "day=abc", "d=", "d= ", "é=1", "day=1 ", "h=+3,", "n=+3,,,", ",,,n=+3",
        // blank
        "", " ", "\t", "　", " ", "​", "\u0085", "\u001C", "᠎", " ", " ", " ",
        " ", " ", " ", "\n", " \t\r\n", N};

    private static final String[] DTYPES = {"year", "y", "quarter", "q", "month", "m", "week", "w", "day", "d",
        "hour", "h", "minute", "n", "second", "s", "Year", "YEAR", "Y", "Q", "M", "W", "D", "H", "N", "S", "min",
        "minutes", "mi", "ms", "days", "yy", "wk", "0", "q ", " day", "day ", "\tw", "w\t", "", " ", "\t", "　",
        " ", "é", N};

    private static final String[] OFFSETS_SMALL = {"+1", "-1", "d=31", "m=+1", "y=+1,m=1,d=1,d=-1", "h=+30", "h=13",
        "n=+90,s=-5", "d=+365", "y=2000,m=2,d=29", "m=2,d=30", "y=+9999", N, " "};

    private static final String[] FMT_STANDARD = {"yyyyMMdd", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyy-MM-dd HH:mm:ss",
        "yyyyMMdd ", " yyyyMMdd", "yyyy-MM-dd  HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd", "yyyy.MM.dd",
        "yyMMdd", "yyyyMM", "yyyy", "MM", "dd", "HH:mm:ss", "hh:mm:ss a", "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssXXX", "EEE, d MMM yyyy HH:mm:ss Z",
        "EEEE, MMMM d, yyyy", "yyyyMMddHHmmssSSS", "G yyyy", "'week' w 'of' Y", "yyyyMMdd'z'", "YYYY-ww-u"};

    private static List<String> formats() {
        List<String> f = new ArrayList<>();
        for (String s : FMT_STANDARD) {
            f.add(s);
        }
        String letters = "GyMdkHmsSEDFwWahKzZYuXL";
        for (int i = 0; i < letters.length(); i++) {
            for (int c = 1; c <= 6; c++) {
                f.add(rep(String.valueOf(letters.charAt(i)), c));
            }
        }
        for (int c : new int[] {7, 9, 10, 12, 20, 255, 256, 308, 309, 310, 400}) {
            f.add(rep("y", c));
            f.add(rep("S", c));
        }
        f.add(rep("M", 400));
        f.add(rep("d", 1000));
        String invalid = "ABCNOPQRTUVbcefgijlnopqrtvx";
        for (int i = 0; i < invalid.length(); i++) {
            f.add(String.valueOf(invalid.charAt(i)));
            f.add("yyyy" + invalid.charAt(i));
        }
        String[] misc = {"", " ", "  ", "'abc'", "'abc", "abc'", "''", "'''", "''''", "'a''b'", "'a'''b'", "a'b",
            "'", "yy'", "'yyyy'yyyy", "yyyy''MM", "y''y", "'y'y'y'", "'y'", "''y''", "'''y'''", "y'''", "'''y",
            "'yyyy-MM-dd'", "yyyy'年'MM'月'dd'日'", "é", "é yyyy", "yyyy 😀",
            "'😀'", "yyyyéMM", "'é'yy", "\t", "yyyy\tMM", "\n", "yyyy\\MM", "'\\'",
            "!\"#$%&()*+,-./:;<=>?@[\\]^_`{|}~", "0123456789", "yyyy0MM", "y-y", "yy-yy-yyyy", "ddD", "dDd", "yyyyD",
            "MMMMMMMMMM", "MMMMLLLL", "EEEEEEEE", "aaaaaaaa", "zzzzzzzz", "ZZZZZZZZ", "XXXX", "XXXXX", "XXX'a'XXXX",
            "X", "XX", "XXX", "yyyyXXXX", "XXXXyyyy", "XXX''XXXX", "XXXXb", "bXXXX", "HHmmssSSS", "kkKKhhHH",
            "uuEEE", "yyyyyyyyyyyy", "GGGGGGG", "'G'G", "G'G'G", "hhhhhh", "aa aaa aaaa", "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss z", "yyyy-MM-dd HH:mm:ss zzzz", "yyyy-MM-dd'T'HH:mm:ssZ", "YYYY", "YYYYYY", "YY",
            "yyyy-'W'ww-u", "Q", "yyyyQ", "q", "'unterminated", "'a' 'b", "yyyy 'a b' dd", "yyyy' 'MM", "' '''",
            "yyyy-MM-dd'", "'yyyy-MM-dd", "w'w'w", "ww''ww", "DDD'D'DDD"};
        for (String s : misc) {
            f.add(s);
        }
        return f;
    }

    private static final String[] WEEK_FORMATS = {"YYYY-ww-W-F-u-EEEE-D", "YY ww", "w", "W", "Y", "D", "F", "u"};

    // ------------------------------------------------------------------ generation

    private void generate() throws IOException {
        List<String> dates = smallDates();
        List<String> fmts = formats();

        samples();
        arityGrid(dates);
        deltaGrid(dates);
        diffGrid(dates);
        formatGrid(dates, fmts);
        dtypeGrid(dates);
        denseGrids();
        randomGrids(quick ? 5_000 : 250_000);
    }

    /** Arities 0..4 (and 5, 6) of every function over a few arguments. */
    private void arityGrid(List<String> dates) throws IOException {
        String[] fns = {"gl_date_delta", "gl_date_diff", "gl_date_format", "gl_how_many_day", "gl_date_first",
            "gl_date_last"};
        String[] sample = {"20190417", "2019-04-17 03:04:05", "abc", N, "20190417030405", ""};
        String[] second = {"+1", "20190416", "yyyy", "y", "day", "w", N, "", "bad'", "m=2,d=30"};
        for (String fn : fns) {
            emit(fn);
            for (String d : sample) {
                emit(fn, d);
                for (String s : second) {
                    emit(fn, d, s);
                    for (String t : new String[] {"yyyyMMdd", "day", "h", N, "", "quarter", "+1", "bad'", " "}) {
                        emit(fn, d, s, t);
                        emit(fn, d, s, t, "x");
                        emit(fn, d, s, t, "x", "y");
                        emit(fn, d, s, t, "x", "y", "z");
                    }
                }
            }
        }
        for (String d : pick(dates, quick ? 20 : 400)) {
            emit("gl_date_delta", d);
            emit("gl_date_diff", d);
            emit("gl_date_format", d);
            emit("gl_date_diff", d, d);
            emit("gl_date_delta", d, "+1", "yyyyMMdd", "extra");
        }
    }

    private void deltaGrid(List<String> dates) throws IOException {
        List<String> ds = quick ? pick(dates, 40) : dates;
        List<String> core = quick ? pick(dates, 10) : pick(dates, 150);
        List<String> fmts = formats();
        for (String off : OFFSETS) {
            for (String d : ds) {
                emit("gl_date_delta", d, off);
            }
        }
        for (String f : fmts) {
            for (String d : pick(core, quick ? 3 : 30)) {
                for (String off : OFFSETS_SMALL) {
                    emit("gl_date_delta", d, off, f);
                }
            }
        }
        // NULL / blank / empty formats
        for (String f : new String[] {N, "", " ", "  ", "\t", "　", " ", "bad'", "b", "yyyyMMdd"}) {
            for (String d : core) {
                for (String off : OFFSETS_SMALL) {
                    emit("gl_date_delta", d, off, f);
                }
            }
        }
    }

    private void diffGrid(List<String> dates) throws IOException {
        List<String> all = dates;
        List<String> a = pick(all, quick ? 25 : 130);
        for (String x : a) {
            for (String y : a) {
                emit("gl_date_diff", x, y);
            }
        }
        List<String> b = pick(all, quick ? 12 : 60);
        for (String unit : DTYPES) {
            for (String x : b) {
                for (String y : b) {
                    emit("gl_date_diff", x, y, unit);
                }
            }
        }
        // second-resolution neighbourhoods
        String[] near = {"20190417235959", "20190418000000", "20190417000000", "20190417120000", "20190417115959",
            "20190416235959", "20190418000001", "20190101000000", "20181231235959", "00010101000000",
            "99991231235959", "20200229120000", "20200301120000", "2019-04-17 23:59:59", "2019-04-18"};
        for (String unit : new String[] {"y", "q", "m", "w", "d", "h", "n", "s"}) {
            for (String x : near) {
                for (String y : near) {
                    emit("gl_date_diff", x, y, unit);
                }
            }
        }
    }

    private void formatGrid(List<String> dates, List<String> fmts) throws IOException {
        List<String> ds = pick(dates, quick ? 20 : 160);
        for (String f : fmts) {
            for (String d : ds) {
                emit("gl_date_format", d, f);
            }
        }
        for (String d : dates) {
            emit("gl_date_format", d);
        }
        for (String d : ds) {
            emit("gl_date_format", d, N);
        }
        // every hour of the day, several dates, hour-sensitive letters
        for (String f : new String[] {"HH kk KK hh a h k K H", "yyyy-MM-dd hh:mm:ss a", "a"}) {
            for (String day : new String[] {"20190417", "00010101", "99991231"}) {
                for (int h = 0; h < 24; h++) {
                    emit("gl_date_format", day + p2(h) + "3045", f);
                    emit("gl_date_format", day + p2(h) + "0000", f);
                }
            }
        }
    }

    private void dtypeGrid(List<String> dates) throws IOException {
        for (String fn : new String[] {"gl_how_many_day", "gl_date_first", "gl_date_last"}) {
            for (String t : DTYPES) {
                for (String d : dates) {
                    emit(fn, d, t);
                }
            }
        }
        // boundaries of the supported range, all unit spellings
        String[] units = {"year", "y", "quarter", "q", "month", "m", "week", "w"};
        for (String fn : new String[] {"gl_how_many_day", "gl_date_first", "gl_date_last"}) {
            for (String u : units) {
                for (int d = 1; d <= 10; d++) {
                    emit(fn, d8(1, 1, d), u);
                    emit(fn, d8(9999, 12, 22 + d - 1 > 31 ? 31 : 22 + d - 1), u);
                    emit(fn, d8(1, 12, 22 + d - 1 > 31 ? 31 : 22 + d - 1), u);
                    emit(fn, d8(9999, 1, d), u);
                }
            }
        }
    }

    private void denseGrids() throws IOException {
        // every day of selected years x week/day-of-year formats
        int[] years = quick ? new int[] {1, 2000, 2019, 9999}
                : new int[] {1, 2, 3, 4, 5, 6, 7, 100, 400, 1900, 1999, 2000, 2019, 2020, 2021, 2022, 2023, 2024, 2025,
                    9996, 9997, 9998, 9999};
        for (int y : years) {
            LocalDate d = LocalDate.of(y, 1, 1);
            while (d.getYear() == y) {
                String s = d8(y, d.getMonthValue(), d.getDayOfMonth());
                for (String f : WEEK_FORMATS) {
                    emit("gl_date_format", s, f);
                }
                d = d.plusDays(1);
            }
        }
        // sparse stride over the whole range
        int strideFmt = quick ? 997 : 13;
        int strideDt = quick ? 997 : 101;
        LocalDate d = LocalDate.of(1, 1, 1);
        LocalDate end = LocalDate.of(9999, 12, 31);
        String wide = "YYYY-ww-W-F-u-E-D-yy-MMM-dd";
        long i = 0;
        String[] shorts = {"y", "q", "m", "w"};
        while (!d.isAfter(end)) {
            String s = d8(d.getYear(), d.getMonthValue(), d.getDayOfMonth());
            if (i % strideFmt == 0) {
                emit("gl_date_format", s, wide);
            }
            if (i % strideDt == 0) {
                for (String u : shorts) {
                    emit("gl_how_many_day", s, u);
                    emit("gl_date_first", s, u);
                    emit("gl_date_last", s, u);
                }
            }
            d = d.plusDays(1);
            i++;
        }
        // end of range and start of range, every day, all functions
        for (int k = 0; k < 40; k++) {
            LocalDate a = LocalDate.of(9999, 12, 31).minusDays(k);
            LocalDate b = LocalDate.of(1, 1, 1).plusDays(k);
            for (LocalDate x : new LocalDate[] {a, b}) {
                String s = d8(x.getYear(), x.getMonthValue(), x.getDayOfMonth());
                for (String off : new String[] {"+1", "-1", "d=+7", "d=-7", "m=+1", "m=-1", "y=+1", "y=-1", "h=+24",
                    "h=-24", "s=-1", "s=+86400", "y=+4"}) {
                    emit("gl_date_delta", s, off);
                    emit("gl_date_delta", s + "000000", off);
                    emit("gl_date_delta", s + "235959", off);
                }
                emit("gl_date_diff", s, "00010101", "w");
                emit("gl_date_diff", s, "99991231", "w");
                emit("gl_date_diff", s + "235959", "00010101000000", "s");
            }
        }
    }

    // ------------------------------------------------------------------ random grids

    private static String randomDate(SplittableRandom r) {
        int y = 1 + r.nextInt(9999);
        if (r.nextInt(8) == 0) {
            y = r.nextBoolean() ? 1 + r.nextInt(30) : 9970 + r.nextInt(30);
        }
        int m = 1 + r.nextInt(12);
        int len = LocalDate.of(y, m, 1).lengthOfMonth();
        int d = 1 + r.nextInt(len);
        String s = d8(y, m, d);
        if (r.nextBoolean()) {
            s += p2(r.nextInt(24)) + p2(r.nextInt(60)) + p2(r.nextInt(60));
        }
        switch (r.nextInt(6)) {
            case 0:
                return dash(s);
            case 1: {
                StringBuilder b = new StringBuilder();
                String seps = " -:";
                for (int i = 0; i < s.length(); i++) {
                    if (r.nextInt(4) == 0) {
                        b.append(seps.charAt(r.nextInt(3)));
                    }
                    b.append(s.charAt(i));
                }
                return b.toString();
            }
            default:
                return s;
        }
    }

    private static final String[] UNIT_NAMES = {"y", "year", "m", "month", "d", "day", "h", "hour", "n", "minute",
        "s", "second"};

    private static String randomOffsets(SplittableRandom r) {
        int items = 1 + r.nextInt(4);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items; i++) {
            if (i > 0) {
                b.append(r.nextInt(10) == 0 ? " , " : ",");
            }
            int u = r.nextInt(6);
            String name = UNIT_NAMES[u * 2 + r.nextInt(2)];
            boolean add = r.nextBoolean();
            long v;
            if (add) {
                int mag = r.nextInt(4);
                long[] lim = {40, 1000, 100000, 3000000000L};
                v = r.nextLong(lim[mag] * 2 + 1) - lim[mag];
                if (v > Integer.MAX_VALUE) {
                    v = Integer.MAX_VALUE;
                }
                b.append(name).append('=').append(v >= 0 ? "+" : "").append(v);
            } else {
                int[][] range = {{1, 9999}, {1, 12}, {1, 31}, {0, 23}, {0, 59}, {0, 59}};
                int lo = range[u][0];
                int hi = range[u][1];
                int v2 = r.nextInt(25) == 0 ? hi + 1 + r.nextInt(3) : lo + r.nextInt(hi - lo + 1);
                b.append(name).append('=').append(v2);
            }
        }
        if (r.nextInt(30) == 0) {
            return Integer.toString(r.nextInt(40) - 5);
        }
        return b.toString();
    }

    private static final String VALID_LETTERS = "GyMdkHmsSEDFwWahKzZYuXL";

    private static String randomPattern(SplittableRandom r) {
        int parts = 1 + r.nextInt(6);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts; i++) {
            int k = r.nextInt(20);
            if (k < 14) {
                char c = VALID_LETTERS.charAt(r.nextInt(VALID_LETTERS.length()));
                int cnt = 1 + r.nextInt(6);
                if (c == 'X' && cnt >= 4 && r.nextInt(5) != 0) {
                    cnt = 1 + r.nextInt(3);
                }
                b.append(rep(String.valueOf(c), cnt));
            } else if (k < 17) {
                b.append("-:/. ,".charAt(r.nextInt(6)));
            } else if (k < 19) {
                b.append('\'').append(r.nextBoolean() ? "T" : "ab").append(r.nextInt(8) == 0 ? "" : "'");
            } else {
                b.append((char) ('A' + r.nextInt(26)));
            }
        }
        return b.toString();
    }

    private void randomGrids(int n) throws IOException {
        SplittableRandom r = new SplittableRandom(20260101L);
        for (int i = 0; i < n; i++) {
            String d = randomDate(r);
            emit("gl_date_delta", d, randomOffsets(r));
            emit("gl_date_delta", randomDate(r), randomOffsets(r), randomPattern(r));
            emit("gl_date_diff", d, randomDate(r), UNIT_LONG[r.nextInt(UNIT_LONG.length)]);
            emit("gl_date_format", d, randomPattern(r));
        }
        // patterns: random pattern x random date, pattern-major so the Java per-thread cache is effective
        for (int i = 0; i < n / 20; i++) {
            String p = randomPattern(r);
            for (int k = 0; k < 8; k++) {
                emit("gl_date_format", randomDate(r), p);
            }
        }
    }

    private static final String[] UNIT_LONG = {"year", "y", "quarter", "q", "month", "m", "week", "w", "day", "d",
        "hour", "h", "minute", "n", "second", "s"};

    // ------------------------------------------------------------------ samples.csv

    /** Every row of samples/samples.csv (except gl_now), parsed back from its SQL text. */
    private void samples() throws IOException {
        java.nio.file.Path p = Paths.get("samples", "samples.csv");
        if (!Files.exists(p)) {
            System.err.println("samples/samples.csv not found (run from the module directory); skipping");
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String all;
            StringBuilder b = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) {
                b.append(buf, 0, n);
            }
            all = b.toString();
            List<List<String>> rows = csv(all);
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.size() < 2 || row.get(0).equals("gl_now")) {
                    continue;
                }
                String sql = row.get(1);
                String prefix = "SELECT " + row.get(0) + "(";
                if (!sql.startsWith(prefix) || !sql.endsWith(")")) {
                    throw new IllegalStateException("cannot parse sample sql: " + sql);
                }
                List<String> args = sqlArgs(sql.substring(prefix.length(), sql.length() - 1));
                emit(row.get(0), args.toArray(new String[0]));
            }
        }
    }

    private static List<List<String>> csv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        q = false;
                    }
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                q = true;
            } else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\r') {
                continue;
            } else if (c == '\n') {
                row.add(f.toString());
                f.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Parses {@code 'a','b''c',NULL} into [a, b'c, null]; the empty string gives no arguments. */
    private static List<String> sqlArgs(String s) {
        List<String> r = new ArrayList<>();
        int i = 0;
        int n = s.length();
        if (s.trim().isEmpty()) {
            return r;
        }
        while (i < n) {
            while (i < n && s.charAt(i) == ' ') {
                i++;
            }
            if (s.startsWith("NULL", i)) {
                r.add(null);
                i += 4;
            } else if (s.charAt(i) == '\'') {
                StringBuilder b = new StringBuilder();
                i++;
                while (true) {
                    char c = s.charAt(i);
                    if (c == '\'') {
                        if (i + 1 < n && s.charAt(i + 1) == '\'') {
                            b.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    b.append(c);
                    i++;
                }
                r.add(b.toString());
            } else {
                throw new IllegalStateException("cannot parse sql args: " + s);
            }
            while (i < n && s.charAt(i) == ' ') {
                i++;
            }
            if (i < n) {
                if (s.charAt(i) != ',') {
                    throw new IllegalStateException("cannot parse sql args: " + s);
                }
                i++;
            }
        }
        return r;
    }

}
