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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Output formatting with {@link java.text.SimpleDateFormat} patterns (the pattern language the original
 * Hive UDFs accept), always with {@link Locale#US} and a Gregorian, Sunday-first calendar so the result
 * does not depend on the JVM default locale. The four default shapes are formatted by hand; other patterns
 * are compiled once per thread and cached, because {@code SimpleDateFormat} is not thread-safe.
 */
final class DateFormats {
    /** Cached compiled SimpleDateFormat patterns, per thread (SimpleDateFormat is not thread-safe). */
    private static final int MAX_CACHED_PATTERNS = 64;
    private static final ThreadLocal<Map<String, Pattern>> CACHE = ThreadLocal.withInitial(HashMap::new);

    /** The four shapes the original UDFs use by default, formatted by hand without any formatter object. */
    enum Fast {
        YYYYMMDD, YYYY_MM_DD, YYYYMMDDHHMMSS, YYYY_MM_DD_HH_MM_SS;

        static Fast of(String pattern) {
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

        String format(LocalDateTime t) {
            boolean dashed = this == YYYY_MM_DD || this == YYYY_MM_DD_HH_MM_SS;
            boolean time = this == YYYYMMDDHHMMSS || this == YYYY_MM_DD_HH_MM_SS;
            char[] b = new char[(dashed ? 10 : 8) + (time ? (dashed ? 9 : 6) : 0)];
            int i = put(b, 0, t.getYear(), 4);
            if (dashed) {
                b[i++] = '-';
            }
            i = put(b, i, t.getMonthValue(), 2);
            if (dashed) {
                b[i++] = '-';
            }
            i = put(b, i, t.getDayOfMonth(), 2);
            if (time) {
                if (dashed) {
                    b[i++] = ' ';
                }
                i = put(b, i, t.getHour(), 2);
                if (dashed) {
                    b[i++] = ':';
                }
                i = put(b, i, t.getMinute(), 2);
                if (dashed) {
                    b[i++] = ':';
                }
                put(b, i, t.getSecond(), 2);
            }
            return new String(b);
        }

        /** Writes {@code v} (0 <= v < 10^width, years are limited to 1..9999) zero-padded to {@code width}. */
        private static int put(char[] b, int at, int v, int width) {
            for (int k = at + width - 1; k >= at; k--) {
                b[k] = (char) ('0' + v % 10);
                v /= 10;
            }
            return at + width;
        }
    }

    /** yyyyMMdd of a date in year 1..9999. */
    static String yyyyMMdd(LocalDate d) {
        return Fast.YYYYMMDD.format(d.atStartOfDay());
    }

    private DateFormats() {
    }

    /** A validated output pattern; the instance may be reused only by the thread that obtained it. */
    static final class Pattern {
        private final Fast fast;
        private final java.text.SimpleDateFormat sdf;
        private final String fn;
        private final String pattern;

        private Pattern(String fn, String pattern, Fast fast, java.text.SimpleDateFormat sdf) {
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
        Fast fast = allowFast ? Fast.of(pattern) : null;
        if (fast != null) {
            return new Pattern(fn, pattern, fast, null);
        }
        // Cache per thread; the key includes the function name (used in error messages) and the zone,
        // because gl_now renders in the JVM default zone and that can change.
        String key = fn + '\u0000' + tz.getID() + '\u0000' + pattern;
        Map<String, Pattern> cache = CACHE.get();
        Pattern cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Pattern compiled;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(pattern, Locale.US);
            GregorianCalendar cal = new GregorianCalendar(tz, Locale.US);
            cal.setGregorianChange(new Date(Long.MIN_VALUE)); // proleptic Gregorian, like java.time
            cal.setFirstDayOfWeek(Calendar.SUNDAY);
            cal.setMinimalDaysInFirstWeek(1);
            sdf.setCalendar(cal);
            compiled = new Pattern(fn, pattern, null, sdf);
        } catch (IllegalArgumentException e) {
            throw bad(fn, pattern, e); // invalid patterns are never cached
        }
        if (cache.size() >= MAX_CACHED_PATTERNS) {
            cache.clear();
        }
        cache.put(key, compiled);
        return compiled;
    }

    private static IllegalArgumentException bad(String fn, String pattern, IllegalArgumentException cause) {
        return new IllegalArgumentException(
                fn + ": invalid format pattern '" + pattern + "': " + cause.getMessage(), cause);
    }
}
