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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Rule tests for gl_date_format, gl_now, gl_how_many_day, gl_date_first and gl_date_last. */
class GlDateFunctionsTest {
    private static String format(String... a) {
        return new GlDateFormat().evaluate(a);
    }

    private static String howMany(String... a) {
        return new GlHowManyDay().evaluate(a);
    }

    private static String first(String... a) {
        return new GlDateFirst().evaluate(a);
    }

    private static String last(String... a) {
        return new GlDateLast().evaluate(a);
    }

    private static void rejects(String fn, String expectedInMessage, Runnable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call::run);
        assertTrue(e.getMessage().contains(fn), e.getMessage());
        assertTrue(e.getMessage().contains(expectedInMessage), e.getMessage());
    }

    // ---------------------------------------------------------------- gl_date_format

    @Test
    void formatDefaultsToYyyyMMdd() {
        assertEquals("20190417", format("2019-04-17 03:04:05"));
        assertEquals("20190417", format("20190417"));
        assertEquals("20190417", format("20190417030405"));
    }

    @Test
    void formatPatterns() {
        assertEquals("2019-04-17", format("20190417", "yyyy-MM-dd"));
        assertEquals("2019-04-17 03:04:05", format("20190417030405", "yyyy-MM-dd HH:mm:ss"));
        assertEquals("20190417030405", format("2019-04-17 03:04:05", "yyyyMMddHHmmss"));
        assertEquals("2019-04-17 00:00:00", format("20190417", "yyyy-MM-dd HH:mm:ss"));
        assertEquals("Wed", format("20190417", "EEE"));
        assertEquals("Wednesday April", format("20190417", "EEEE MMMM"));
        assertEquals("03:04 PM", format("20190417150405", "hh:mm a"));
        assertEquals("2019-16", format("20190417", "yyyy-ww")); // Sunday-first weeks, minimal days 1
        assertEquals("2019-01", format("20191229", "yyyy-ww")); // Sunday 2019-12-29 opens week 1 of week-year 2020
        assertEquals("2020-01", format("20191229", "YYYY-ww"));
        assertEquals("0001-01-01", format("00010101", "yyyy-MM-dd"));
        assertEquals("9999-12-31 23:59:59", format("99991231235959", "yyyy-MM-dd HH:mm:ss"));
        assertEquals("", format("20190417", ""));
        assertEquals("19", format("20190417", "yy"));
        assertEquals("3", format("20190417", "u")); // Monday = 1, so Wednesday = 3
    }

    @Test
    void formatMatchesSimpleDateFormatOnWallClock() {
        // 'yyyy' via the fast path and via SimpleDateFormat must agree, including for years < 1000
        assertEquals("0999-01-02", format("09990102", "yyyy-MM-dd"));
        assertEquals("09990102", format("09990102", "yyyyMMdd"));
        assertEquals("0999-01-02 ", format("09990102", "yyyy-MM-dd "));
    }

    @Test
    void formatInvalidDateIsNull() {
        assertNull(format(null));
        assertNull(format(null, "yyyy"));
        assertNull(format(null, null)); // null data first, like the original
        assertNull(format("20191332"));
        assertNull(format("20191332", "yyyy-MM-dd"));
        assertNull(format("abc", "yyyy-MM-dd"));
        assertNull(format("", "yyyy-MM-dd"));
        assertNull(format("2019-1-7"));
    }

    @Test
    void formatArityAndParameters() {
        assertNull(format());
        assertNull(format("20190417", "yyyy", "x"));
        rejects("gl_date_format", "format", () -> format("20190417", null));
        rejects("gl_date_format", "bad'", () -> format("20190417", "bad'"));
        rejects("gl_date_format", "invalid format pattern", () -> format("20190417", "yyyy-MM-dd bbb"));
        rejects("gl_date_format", "bad'", () -> format("abc", "bad'")); // parameters before data
    }

    // ---------------------------------------------------------------- gl_now

    @Test
    void nowFormats() {
        String today = LocalDate.now().toString();
        assertEquals(8, new GlNow().evaluate().length());
        assertEquals(8, new GlNowNoArg().evaluate().length());
        assertEquals(today.substring(0, 4), new GlNow().evaluate("yyyy"));
        assertEquals(today.substring(0, 4), new GlNow().evaluate("yyyy-MM-dd").substring(0, 4));
        assertEquals(19, new GlNow().evaluate("yyyy-MM-dd HH:mm:ss").length());
        assertEquals("", new GlNow().evaluate(""));
    }

    @Test
    void nowArityAndParameters() {
        assertNull(new GlNow().evaluate("yyyy", "x"));
        assertNull(new GlNow().evaluate("yyyy", "x", "y"));
        rejects("gl_now", "format", () -> new GlNow().evaluate((String) null));
        rejects("gl_now", "bad'", () -> new GlNow().evaluate("bad'"));
    }

    // ---------------------------------------------------------------- gl_how_many_day

    @Test
    void howManyDay() {
        assertEquals("107", howMany("20190417", "year"));
        assertEquals("107", howMany("2019-04-17 23:59:59", "y"));
        assertEquals("17", howMany("20190417", "quarter"));
        assertEquals("1", howMany("20190401", "q"));
        assertEquals("90", howMany("20190331", "q"));
        assertEquals("91", howMany("20200331", "quarter"));
        assertEquals("92", howMany("20191231", "quarter"));
        assertEquals("17", howMany("20190417", "month"));
        assertEquals("31", howMany("20190531", "m"));
        assertEquals("4", howMany("20190417", "week")); // Wednesday, Sunday = 1
        assertEquals("1", howMany("20190414", "w"));
        assertEquals("7", howMany("20190420", "w"));
        assertEquals("366", howMany("20201231", "year"));
        assertEquals("1", howMany("20190101", "year"));
    }

    @Test
    void howManyDayQuarterIsCalendarDaysEvenWithTimeOfDay() {
        // the original subtracted epoch-day numbers, which is off by one in time zones with DST or large offsets
        for (String t : new String[] {"000000", "010000", "070000", "120000", "235959"}) {
            assertEquals("78", howMany("201903" + "19" + t, "quarter"));
            assertEquals("1", howMany("20190101" + t, "quarter"));
            assertEquals("1", howMany("20190401" + t, "quarter"));
        }
    }

    @Test
    void howManyDayErrors() {
        assertNull(howMany());
        assertNull(howMany("20190417"));
        assertNull(howMany("20190417", "year", "x"));
        assertNull(howMany(null, "year"));
        assertNull(howMany(null, "bad")); // null data first
        assertNull(howMany("20191332", "year"));
        assertNull(howMany("abc", "month"));
        rejects("gl_how_many_day", "dtype", () -> howMany("20190417", null));
        rejects("gl_how_many_day", "dtype", () -> howMany("20190417", ""));
        rejects("gl_how_many_day", "'day'", () -> howMany("20190417", "day"));
        rejects("gl_how_many_day", "'YEAR'", () -> howMany("20190417", "YEAR"));
        rejects("gl_how_many_day", "'xx'", () -> howMany("abc", "xx"));
    }

    // ---------------------------------------------------------------- gl_date_first / gl_date_last

    @Test
    void firstDay() {
        assertEquals("20190101", first("20190417", "year"));
        assertEquals("20190101", first("2019-04-17 12:00:00", "y"));
        assertEquals("20190401", first("20190417", "quarter"));
        assertEquals("20190701", first("20190930", "q"));
        assertEquals("20190401", first("20190417", "month"));
        assertEquals("20190414", first("20190417", "week"));
        assertEquals("20190414", first("20190414", "w")); // a Sunday is its own week start
        assertEquals("20190414", first("20190420", "w"));
        assertEquals("20181230", first("20190101", "w")); // across the year boundary
        assertEquals("00010101", first("00010305", "y"));
    }

    @Test
    void lastDay() {
        assertEquals("20191231", last("20190417", "year"));
        assertEquals("20190630", last("20190417", "quarter"));
        assertEquals("20191231", last("20191231", "q"));
        assertEquals("20190430", last("20190417", "month"));
        assertEquals("20200229", last("20200215", "m"));
        assertEquals("20190228", last("20190215", "month"));
        assertEquals("20190420", last("20190417", "week"));
        assertEquals("20190420", last("20190420", "w")); // a Saturday ends its week
        assertEquals("20190420", last("20190414", "w"));
        assertEquals("20190105", last("20190101", "w"));
        assertEquals("20190105", last("20181230", "w"));
        assertEquals("99991231", last("99991231", "y"));
        assertNull(last("99991231", "week")); // that week ends in year 10000
    }

    @Test
    void firstLastErrors() {
        for (java.util.function.Function<String[], String> f
                : java.util.Arrays.<java.util.function.Function<String[], String>>asList(
                        GlDateFunctionsTest::first, GlDateFunctionsTest::last)) {
            assertNull(f.apply(new String[0]));
            assertNull(f.apply(new String[] {"20190417"}));
            assertNull(f.apply(new String[] {"20190417", "year", "x"}));
            assertNull(f.apply(new String[] {null, "year"}));
            assertNull(f.apply(new String[] {null, null}));
            assertNull(f.apply(new String[] {"20191332", "month"}));
            assertNull(f.apply(new String[] {"", "month"}));
        }
        rejects("gl_date_first", "dtype", () -> first("20190417", null));
        rejects("gl_date_last", "dtype", () -> last("20190417", " "));
        rejects("gl_date_first", "'day'", () -> first("20190417", "day"));
        rejects("gl_date_last", "'Month'", () -> last("20190417", "Month"));
        rejects("gl_date_last", "'hour'", () -> last("abc", "hour"));
    }
}
