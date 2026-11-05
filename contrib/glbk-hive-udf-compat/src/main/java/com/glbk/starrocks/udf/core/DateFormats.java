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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Output formatting with {@link java.text.SimpleDateFormat} patterns (the pattern language the original
 * Hive UDFs accept), always with {@link Locale#US} and a Gregorian, Sunday-first calendar so the result
 * does not depend on the JVM default locale. A {@link Pattern} is created per call and never shared,
 * because {@code SimpleDateFormat} is not thread-safe.
 */
final class DateFormats {
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.US);
    private static final DateTimeFormatter YYYY_MM_DD = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US);
    private static final DateTimeFormatter YYYYMMDDHHMMSS =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss", Locale.US);
    private static final DateTimeFormatter YYYY_MM_DD_HH_MM_SS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.US);

    private DateFormats() {
    }

    /** A validated output pattern; use it for one evaluation only. */
    static final class Pattern {
        private final DateTimeFormatter fast;
        private final java.text.SimpleDateFormat sdf;
        private final String fn;
        private final String pattern;

        private Pattern(String fn, String pattern, DateTimeFormatter fast, java.text.SimpleDateFormat sdf) {
            this.fn = fn;
            this.pattern = pattern;
            this.fast = fast;
            this.sdf = sdf;
        }

        /** Formats the wall-clock fields of {@code t}; no time zone conversion happens. */
        String format(LocalDateTime t) {
            if (fast != null) {
                return fast.format(t);
            }
            return render(new Date(t.toEpochSecond(ZoneOffset.UTC) * 1000L));
        }

        /** Formats the current instant in the JVM default time zone (the pattern was compiled for it). */
        String formatNow() {
            return render(new Date());
        }

        private String render(Date date) {
            try {
                return sdf.format(date);
            } catch (IllegalArgumentException e) {
                throw bad(fn, pattern, e);
            }
        }
    }

    /** Compiles a pattern whose wall-clock fields are rendered (internally via a UTC calendar). */
    static Pattern wallClock(String fn, String pattern) {
        return compile(fn, pattern, TimeZone.getTimeZone("UTC"), true);
    }

    /** Compiles a pattern that renders the current instant in the JVM default zone. */
    static Pattern now(String fn, String pattern) {
        return compile(fn, pattern, TimeZone.getDefault(), false);
    }

    private static Pattern compile(String fn, String pattern, TimeZone tz, boolean allowFast) {
        if (pattern == null) {
            throw new IllegalArgumentException(fn + ": format must not be null");
        }
        DateTimeFormatter fast = allowFast ? fastFormatter(pattern) : null;
        if (fast != null) {
            return new Pattern(fn, pattern, fast, null);
        }
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(pattern, Locale.US);
            GregorianCalendar cal = new GregorianCalendar(tz, Locale.US);
            cal.setGregorianChange(new Date(Long.MIN_VALUE)); // proleptic Gregorian, like java.time
            cal.setFirstDayOfWeek(Calendar.SUNDAY);
            cal.setMinimalDaysInFirstWeek(1);
            sdf.setCalendar(cal);
            return new Pattern(fn, pattern, null, sdf);
        } catch (IllegalArgumentException e) {
            throw bad(fn, pattern, e);
        }
    }

    private static DateTimeFormatter fastFormatter(String pattern) {
        switch (pattern) {
            case "yyyyMMdd":
                return YYYYMMDD;
            case "yyyy-MM-dd":
                return YYYY_MM_DD;
            case "yyyyMMddHHmmss":
                return YYYYMMDDHHMMSS;
            case "yyyy-MM-dd HH:mm:ss":
                return YYYY_MM_DD_HH_MM_SS;
            default:
                return null;
        }
    }

    private static IllegalArgumentException bad(String fn, String pattern, IllegalArgumentException cause) {
        return new IllegalArgumentException(
                fn + ": invalid format pattern '" + pattern + "': " + cause.getMessage(), cause);
    }
}
