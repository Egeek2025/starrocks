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

/** Rule tests for gl_date_diff (corrected semantics). */
class GlDateDiffTest {
    private static String diff(String... args) {
        return new GlDateDiff().evaluate(args);
    }

    private static void rejects(String expectedInMessage, String... args) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> diff(args));
        assertTrue(e.getMessage().contains("gl_date_diff"), e.getMessage());
        assertTrue(e.getMessage().contains(expectedInMessage), e.getMessage());
    }

    @Test
    void arity() {
        assertNull(diff());
        assertNull(diff("20190417"));
        assertNull(diff("20190417", "20190416", "day", "x"));
    }

    @Test
    void defaultIntervalIsDay() {
        assertEquals("-31", diff("20180101", "20180201"));
        assertEquals("31", diff("2018-02-01", "2018-01-01"));
        assertEquals("2", diff("20200301", "20200228"));
        assertEquals("0", diff("20190417", "20190417"));
    }

    @Test
    void nullAndInvalidDatesAreNull() {
        assertNull(diff(null, "20190417"));
        assertNull(diff("20190417", null));
        assertNull(diff(null, null, "day"));
        assertNull(diff(null, "20190417", "bad-interval")); // null data wins, like the original
        assertNull(diff("20190417", null, null));
        for (String bad : new String[] {"", "abc", "2019-1-7", "20191332", "20190230", "2019/04/17", "20190417240000"}) {
            assertNull(diff(bad, "20190417"), bad);
            assertNull(diff("20190417", bad, "month"), bad);
            assertNull(diff(bad, bad, "second"), bad);
        }
    }

    @Test
    void invalidIntervalIsParameterError() {
        rejects("interval", "20190417", "20190416", null);
        rejects("interval", "20190417", "20190416", "");
        rejects("interval", "20190417", "20190416", "  ");
        rejects("'xx'", "20190417", "20190416", "xx");
        rejects("'DAY'", "20190417", "20190416", "DAY"); // case-sensitive
        rejects("'days'", "20190417", "20190416", "days");
        rejects("'ms'", "20190417", "20190416", "ms");
        rejects("'xx'", "abc", "20190416", "xx"); // parameters are checked before the dates are parsed
    }

    @Test
    void year() {
        assertEquals("1", diff("20190101", "20181231", "year"));
        assertEquals("-1", diff("20181231", "20190101", "y"));
        assertEquals("0", diff("20191231", "20190101", "y"));
        assertEquals("100", diff("21190101", "20190101", "year"));
    }

    @Test
    void quarter() {
        assertEquals("1", diff("2019-04-01", "2019-03-31", "quarter")); // original (month/4 bug) gave 0
        assertEquals("1", diff("2019-05-01", "2019-03-31", "quarter"));
        assertEquals("1", diff("2019-05-01", "2019-03-31", "q"));
        assertEquals("0", diff("2019-06-30", "2019-04-01", "q"));
        assertEquals("1", diff("2020-01-01", "2019-12-31", "q"));
        assertEquals("3", diff("2019-12-31", "2019-01-01", "q"));
        assertEquals("-1", diff("2019-03-31", "2019-04-01", "q"));
        assertEquals("4", diff("2020-01-01", "2019-01-01", "q"));
        assertEquals("0", diff("2019-09-30", "2019-07-01", "quarter"));
        assertEquals("1", diff("2019-10-01", "2019-09-30", "quarter"));
    }

    @Test
    void month() {
        assertEquals("-13", diff("20170101", "20180201", "month")); // the original README says -12, it is -13
        assertEquals("1", diff("20190201", "20190131", "m"));
        assertEquals("0", diff("20190131", "20190101", "m"));
        assertEquals("12", diff("20200101", "20190101", "month"));
        assertEquals("1", diff("20190101", "20181231", "m"));
        assertEquals("-1", diff("20181231", "20190101", "m"));
    }

    @Test
    void week() {
        // weeks run Sunday..Saturday
        assertEquals("0", diff("20190414", "20190420", "w"));
        assertEquals("0", diff("20190417", "20190414", "week"));
        assertEquals("1", diff("20190421", "20190420", "w"));
        assertEquals("-1", diff("20190420", "20190421", "w"));
        // year boundaries (the original used YEAR*52 + WEEK_OF_YEAR)
        assertEquals("1", diff("20190101", "20181229", "w"));
        assertEquals("0", diff("20190101", "20181230", "w"));
        assertEquals("0", diff("20181229", "20181223", "w")); // Saturday and the Sunday that opens its week
        assertEquals("1", diff("20181230", "20181229", "w")); // Sunday vs the Saturday before
        assertEquals("52", diff("20200101", "20190101", "w"));
        assertEquals("54", diff("20210110", "20200101", "w"));
        assertEquals("1", diff("20210103", "20210102", "week"));
        assertEquals("0", diff("20210102", "20201227", "week"));
        assertEquals("1", diff("20210103", "20201231", "week"));
    }

    @Test
    void weekMatchesIndependentFormula() {
        // week index of a day = floor((epochDay + 4) / 7); 1970-01-01 was a Thursday and Sundays start weeks
        LocalDate base = LocalDate.of(2015, 12, 20);
        for (int i = 0; i < 400; i += 3) {
            for (int j = 0; j < 400; j += 7) {
                LocalDate a = base.plusDays(i);
                LocalDate b = base.plusDays(j);
                long expected = Math.floorDiv(a.toEpochDay() + 4, 7) - Math.floorDiv(b.toEpochDay() + 4, 7);
                assertEquals(Long.toString(expected), diff(a.toString(), b.toString(), "w"), a + " - " + b);
            }
        }
    }

    @Test
    void dayUsesCalendarDays() {
        assertEquals("1", diff("2019-04-17 07:00:00", "2019-04-16 09:00:00", "day")); // original: 0 in UTC+8
        assertEquals("1", diff("2019-04-17 00:00:00", "2019-04-16 23:59:59", "d"));
        assertEquals("0", diff("2019-04-17 23:59:59", "2019-04-17 00:00:00", "d"));
        assertEquals("-1", diff("2019-04-16 23:00:00", "2019-04-17 01:00:00", "d"));
        assertEquals("365", diff("20200101", "20190101", "d"));
        assertEquals("366", diff("20210101", "20200101", "day"));
        assertEquals("1", diff("19700101", "19691231", "d"));
    }

    @Test
    void hourMinuteSecondTruncateBothSides() {
        assertEquals("1", diff("2019-04-17 03:00:00", "2019-04-17 02:59:59", "hour"));
        assertEquals("0", diff("2019-04-17 03:59:59", "2019-04-17 03:00:00", "h"));
        assertEquals("25", diff("2019-04-18 03:10:00", "2019-04-17 02:50:00", "h"));
        assertEquals("1", diff("2019-04-17 03:05:00", "2019-04-17 03:04:59", "minute"));
        assertEquals("0", diff("2019-04-17 03:05:59", "2019-04-17 03:05:00", "n"));
        assertEquals("1500", diff("2019-04-18 03:05:00", "2019-04-17 02:05:30", "n"));
        assertEquals("1", diff("2019-04-17 03:05:01", "2019-04-17 03:05:00", "second"));
        assertEquals("-86400", diff("2019-04-16 00:00:00", "2019-04-17 00:00:00", "s"));
        assertEquals("315537897599", diff("99991231235959", "00010101000000", "s"));
        assertEquals("86400", diff("20190418", "20190417", "s")); // date-only means 00:00:00
    }

    @Test
    void dateOnlyAndDateTimeMix() {
        assertEquals("1", diff("20190417", "2019-04-16 23:59:59", "day"));
        assertEquals("0", diff("20190417", "2019-04-17 00:59:59", "h"));
    }
}
