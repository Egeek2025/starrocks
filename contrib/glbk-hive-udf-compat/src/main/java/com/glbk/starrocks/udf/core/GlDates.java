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

package com.glbk.starrocks.udf.core;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared logic of the gl_* date functions (corrected semantics, see the module README).
 *
 * <p>Error policy: invalid DATA (bad date strings, set-values that do not exist for the date) gives
 * {@code null}; wrong PARAMETERS (unknown unit, malformed offsets, bad output pattern, null dtype ...)
 * throw {@link IllegalArgumentException}. All calendar arithmetic is on {@link LocalDateTime} (wall clock),
 * so results do not depend on the JVM time zone or default locale (except {@code gl_now}).
 * Everything here is stateless and thread-safe.
 */
public final class GlDates {
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.US);

    private enum Unit {
        YEAR("year", "y"), QUARTER("quarter", "q"), MONTH("month", "m"), WEEK("week", "w"),
        DAY("day", "d"), HOUR("hour", "h"), MINUTE("minute", "n"), SECOND("second", "s");

        final String longName;
        final String shortName;

        Unit(String longName, String shortName) {
            this.longName = longName;
            this.shortName = shortName;
        }
    }

    private static final Unit[] DIFF_UNITS = Unit.values();
    private static final Unit[] DTYPE_UNITS = {Unit.YEAR, Unit.QUARTER, Unit.MONTH, Unit.WEEK};
    private static final Unit[] OFFSET_UNITS =
            {Unit.YEAR, Unit.MONTH, Unit.DAY, Unit.HOUR, Unit.MINUTE, Unit.SECOND};

    private GlDates() {
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses the accepted input shapes: after removing every ' ', '-' and ':' the rest must be exactly 8
     * (yyyyMMdd) or 14 (yyyyMMddHHmmss) ASCII digits forming a real calendar date/time (year 1..9999).
     *
     * @return the wall-clock date-time, or {@code null} when {@code s} is null or invalid
     */
    public static LocalDateTime parse(String s) {
        if (s == null) {
            return null;
        }
        int n = s.length();
        char[] d = new char[14];
        int len = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '-' || c == ':') {
                continue;
            }
            if (c < '0' || c > '9' || len == 14) {
                return null;
            }
            d[len++] = c;
        }
        if (len != 8 && len != 14) {
            return null;
        }
        try {
            int year = num(d, 0, 4);
            if (year < 1) {
                return null;
            }
            LocalDate date = LocalDate.of(year, num(d, 4, 2), num(d, 6, 2));
            if (len == 8) {
                return date.atStartOfDay();
            }
            return date.atTime(num(d, 8, 2), num(d, 10, 2), num(d, 12, 2));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static int num(char[] d, int from, int count) {
        int v = 0;
        for (int i = from; i < from + count; i++) {
            v = v * 10 + (d[i] - '0');
        }
        return v;
    }

    /** True when the original text contains any of ' ', '-', ':' (selects the dashed default output style). */
    private static boolean hasSeparator(String s) {
        return s.indexOf(' ') >= 0 || s.indexOf('-') >= 0 || s.indexOf(':') >= 0;
    }

    private static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Unit unit(String fn, String what, String name, Unit[] allowed) {
        if (isBlank(name)) {
            throw new IllegalArgumentException(fn + ": " + what + " must not be null or blank");
        }
        for (Unit u : allowed) {
            if (u.longName.equals(name) || u.shortName.equals(name)) {
                return u;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Unit u : allowed) {
            sb.append(sb.length() == 0 ? "" : ", ").append(u.longName).append('|').append(u.shortName);
        }
        throw new IllegalArgumentException(
                fn + ": unknown " + what + " '" + name + "' (case-sensitive, expected one of " + sb + ")");
    }

    // ------------------------------------------------------------------ gl_date_delta

    private static final class Op {
        final Unit unit;
        final boolean add;
        final int value;

        Op(Unit unit, boolean add, int value) {
            this.unit = unit;
            this.add = add;
            this.value = value;
        }
    }

    public static String dateDelta(String... args) {
        final String fn = "gl_date_delta";
        if (args == null || args.length == 0 || args.length > 3) {
            return null;
        }
        if (args.length == 1) {
            return args[0];
        }
        String dateStr = args[0];
        if (dateStr == null) {
            return null;
        }
        List<Op> ops = parseOffsets(fn, args[1]);
        String pattern = null;
        if (args.length == 3) {
            if (args[2] == null) {
                throw new IllegalArgumentException(fn + ": format must not be null");
            }
            if (!isBlank(args[2])) {
                pattern = args[2];
            }
        }
        DateFormats.Pattern fmt = DateFormats.wallClock(fn, pattern != null ? pattern : defaultStyle(dateStr));
        LocalDateTime t = parse(dateStr);
        if (t == null) {
            return null;
        }
        t = applyOps(t, ops);
        return t == null ? null : fmt.format(t);
    }

    /** Output style used when no format is given: same shape as the input. */
    private static String defaultStyle(String dateStr) {
        boolean dashed = hasSeparator(dateStr);
        int digits = 0;
        for (int i = 0; i < dateStr.length(); i++) {
            char c = dateStr.charAt(i);
            if (c != ' ' && c != '-' && c != ':') {
                digits++;
            }
        }
        if (digits == 8) {
            return dashed ? "yyyy-MM-dd" : "yyyyMMdd";
        }
        return dashed ? "yyyy-MM-dd HH:mm:ss" : "yyyyMMddHHmmss";
    }

    private static List<Op> parseOffsets(String fn, String offsets) {
        List<Op> ops = new ArrayList<>();
        if (isBlank(offsets)) {
            return ops;
        }
        String text = (offsets.indexOf(',') >= 0 || offsets.indexOf('=') >= 0) ? offsets : "day=" + offsets;
        for (String raw : text.split(",")) { // like the original: trailing empty items are dropped
            String item = raw.trim();
            if (item.isEmpty()) {
                throw new IllegalArgumentException(fn + ": empty item in offsets '" + offsets + "'");
            }
            int eq = item.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        fn + ": offset item '" + item + "' must look like unit=value (offsets '" + offsets + "')");
            }
            Unit u = unit(fn, "offset unit", item.substring(0, eq).trim(), OFFSET_UNITS);
            String value = item.substring(eq + 1).trim();
            ops.add(parseOp(fn, u, value, item));
        }
        return ops;
    }

    private static Op parseOp(String fn, Unit u, String value, String item) {
        boolean signed = !value.isEmpty() && (value.charAt(0) == '+' || value.charAt(0) == '-');
        int digitsFrom = signed ? 1 : 0;
        boolean ok = value.length() > digitsFrom;
        for (int i = digitsFrom; ok && i < value.length(); i++) {
            ok = value.charAt(i) >= '0' && value.charAt(i) <= '9';
        }
        int v = 0;
        if (ok) {
            try {
                v = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                ok = false;
            }
        }
        if (!ok) {
            throw new IllegalArgumentException(
                    fn + ": offset value '" + value + "' in '" + item + "' is not an integer");
        }
        if (!signed) {
            int min;
            int max;
            switch (u) {
                case YEAR:
                    min = 1;
                    max = 9999;
                    break;
                case MONTH:
                    min = 1;
                    max = 12;
                    break;
                case DAY:
                    min = 1;
                    max = 31;
                    break;
                case HOUR:
                    min = 0;
                    max = 23;
                    break;
                default: // minute, second
                    min = 0;
                    max = 59;
                    break;
            }
            if (v < min || v > max) {
                throw new IllegalArgumentException(fn + ": value " + v + " of '" + item + "' is out of range "
                        + min + ".." + max + " for " + u.longName
                        + " (use +N/-N to add or subtract)");
            }
        }
        return new Op(u, signed, v);
    }

    /**
     * Applies the items left to right. Set-items of year/month/day are collected and resolved together at
     * the next add-item or at the end (like java.util.Calendar does), so {@code m=2,d=1} works on the 31st;
     * a combination that is not a real date (day 31 in April, Feb 29 in a non-leap year) gives null.
     */
    private static LocalDateTime applyOps(LocalDateTime start, List<Op> ops) {
        LocalDateTime t = start;
        Integer py = null;
        Integer pm = null;
        Integer pd = null;
        try {
            for (Op op : ops) {
                if (!op.add) {
                    switch (op.unit) {
                        case YEAR:
                            py = op.value;
                            continue;
                        case MONTH:
                            pm = op.value;
                            continue;
                        case DAY:
                            pd = op.value;
                            continue;
                        case HOUR:
                            t = t.withHour(op.value);
                            continue;
                        case MINUTE:
                            t = t.withMinute(op.value);
                            continue;
                        default:
                            t = t.withSecond(op.value);
                            continue;
                    }
                }
                t = resolve(t, py, pm, pd);
                py = null;
                pm = null;
                pd = null;
                switch (op.unit) {
                    case YEAR:
                        t = t.plusYears(op.value);
                        break;
                    case MONTH:
                        t = t.plusMonths(op.value);
                        break;
                    case DAY:
                        t = t.plusDays(op.value);
                        break;
                    case HOUR:
                        t = t.plusHours(op.value);
                        break;
                    case MINUTE:
                        t = t.plusMinutes(op.value);
                        break;
                    default:
                        t = t.plusSeconds(op.value);
                        break;
                }
            }
            t = resolve(t, py, pm, pd);
        } catch (DateTimeException | ArithmeticException e) {
            return null;
        }
        return t.getYear() < 1 || t.getYear() > 9999 ? null : t;
    }

    private static LocalDateTime resolve(LocalDateTime t, Integer y, Integer m, Integer d) {
        if (y == null && m == null && d == null) {
            return t;
        }
        LocalDate date = LocalDate.of(y != null ? y : t.getYear(), m != null ? m : t.getMonthValue(),
                d != null ? d : t.getDayOfMonth());
        return date.atTime(t.toLocalTime());
    }

    // ------------------------------------------------------------------ gl_date_diff

    public static String dateDiff(String... args) {
        final String fn = "gl_date_diff";
        if (args == null || args.length < 2 || args.length > 3) {
            return null;
        }
        if (args[0] == null || args[1] == null) {
            return null;
        }
        Unit u = args.length == 3 ? unit(fn, "interval", args[2], DIFF_UNITS) : Unit.DAY;
        LocalDateTime a = parse(args[0]);
        LocalDateTime b = parse(args[1]);
        if (a == null || b == null) {
            return null;
        }
        return Long.toString(diff(a, b, u));
    }

    private static long diff(LocalDateTime a, LocalDateTime b, Unit u) {
        switch (u) {
            case YEAR:
                return a.getYear() - b.getYear();
            case QUARTER:
                return (a.getYear() * 4L + (a.getMonthValue() - 1) / 3)
                        - (b.getYear() * 4L + (b.getMonthValue() - 1) / 3);
            case MONTH:
                return (a.getYear() * 12L + a.getMonthValue()) - (b.getYear() * 12L + b.getMonthValue());
            case WEEK:
                return ChronoUnit.DAYS.between(weekFirst(b.toLocalDate()), weekFirst(a.toLocalDate())) / 7;
            case DAY:
                return ChronoUnit.DAYS.between(b.toLocalDate(), a.toLocalDate());
            case HOUR:
                return ChronoUnit.HOURS.between(b.truncatedTo(ChronoUnit.HOURS), a.truncatedTo(ChronoUnit.HOURS));
            case MINUTE:
                return ChronoUnit.MINUTES.between(
                        b.truncatedTo(ChronoUnit.MINUTES), a.truncatedTo(ChronoUnit.MINUTES));
            default:
                return ChronoUnit.SECONDS.between(
                        b.truncatedTo(ChronoUnit.SECONDS), a.truncatedTo(ChronoUnit.SECONDS));
        }
    }

    // ------------------------------------------------------------------ gl_date_format / gl_now

    public static String dateFormat(String... args) {
        final String fn = "gl_date_format";
        if (args == null || args.length < 1 || args.length > 2) {
            return null;
        }
        if (args[0] == null) {
            return null;
        }
        DateFormats.Pattern fmt = DateFormats.wallClock(fn, args.length == 2 ? args[1] : "yyyyMMdd");
        LocalDateTime t = parse(args[0]);
        return t == null ? null : fmt.format(t);
    }

    public static String now(String... args) {
        final String fn = "gl_now";
        if (args != null && args.length > 1) {
            return null;
        }
        String pattern = args == null || args.length == 0 ? "yyyyMMdd" : args[0];
        return DateFormats.now(fn, pattern).formatNow();
    }

    // ------------------------------------------------------------------ dtype functions

    public static String howManyDay(String... args) {
        final String fn = "gl_how_many_day";
        if (args == null || args.length != 2 || args[0] == null) {
            return null;
        }
        Unit u = unit(fn, "dtype", args[1], DTYPE_UNITS);
        LocalDateTime t = parse(args[0]);
        if (t == null) {
            return null;
        }
        LocalDate d = t.toLocalDate();
        switch (u) {
            case YEAR:
                return Integer.toString(d.getDayOfYear());
            case QUARTER:
                return Long.toString(ChronoUnit.DAYS.between(quarterFirst(d), d) + 1);
            case MONTH:
                return Integer.toString(d.getDayOfMonth());
            default:
                return Integer.toString(d.getDayOfWeek().getValue() % 7 + 1);
        }
    }

    public static String dateFirst(String... args) {
        return firstOrLast("gl_date_first", false, args);
    }

    public static String dateLast(String... args) {
        return firstOrLast("gl_date_last", true, args);
    }

    private static String firstOrLast(String fn, boolean last, String[] args) {
        if (args == null || args.length != 2 || args[0] == null) {
            return null;
        }
        Unit u = unit(fn, "dtype", args[1], DTYPE_UNITS);
        LocalDateTime t = parse(args[0]);
        if (t == null) {
            return null;
        }
        LocalDate d = t.toLocalDate();
        LocalDate r;
        switch (u) {
            case YEAR:
                r = last ? LocalDate.of(d.getYear(), 12, 31) : LocalDate.of(d.getYear(), 1, 1);
                break;
            case QUARTER:
                r = last ? quarterFirst(d).plusMonths(3).minusDays(1) : quarterFirst(d);
                break;
            case MONTH:
                r = last ? d.withDayOfMonth(d.lengthOfMonth()) : d.withDayOfMonth(1);
                break;
            default:
                r = last ? weekFirst(d).plusDays(6) : weekFirst(d);
                break;
        }
        if (r.getYear() < 1 || r.getYear() > 9999) {
            return null; // e.g. the week of 9999-12-31 ends in year 10000
        }
        return YYYYMMDD.format(r);
    }

    private static LocalDate quarterFirst(LocalDate d) {
        return LocalDate.of(d.getYear(), (d.getMonthValue() - 1) / 3 * 3 + 1, 1);
    }

    /** The Sunday on or before {@code d}. */
    private static LocalDate weekFirst(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() % 7);
    }
}
