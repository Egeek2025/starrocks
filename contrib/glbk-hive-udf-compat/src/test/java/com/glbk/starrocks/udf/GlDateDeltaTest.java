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

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rule tests for gl_date_delta (corrected semantics). */
class GlDateDeltaTest {
    private static String delta(String... args) {
        return new GlDateDelta().evaluate(args);
    }

    private static void rejects(String expectedInMessage, String... args) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> delta(args));
        assertTrue(e.getMessage().contains("gl_date_delta"), e.getMessage());
        assertTrue(e.getMessage().contains(expectedInMessage), e.getMessage());
    }

    @Test
    void arity() {
        assertEquals("anything", delta("anything"));
        assertNull(delta((String) null));
        assertNull(delta());
        assertNull(delta("20190417", "+1", "yyyy", "extra"));
    }

    @Test
    void nullDateIsNull() {
        assertNull(delta(null, "+1"));
        assertNull(delta(null, "+1", "yyyyMMdd"));
        assertNull(delta(null, "x=bad"));
    }

    @Test
    void blankOffsetsMeansNoChangeAndKeepsStyle() {
        assertEquals("20190417", delta("20190417", null));
        assertEquals("2019-04-17", delta("2019-04-17", ""));
        assertEquals("20190417030405", delta("20190417030405", "   "));
        assertEquals("2019-04-17 03:04:05", delta("2019-04-17 03:04:05", null));
        assertEquals("20190417", delta("2019-04-17", null, "yyyyMMdd"));
    }

    @Test
    void defaultOutputStyleFollowsInput() {
        assertEquals("20190418", delta("20190417", "+1"));
        assertEquals("2019-04-18", delta("2019-04-17", "+1"));
        assertEquals("2019-04-18", delta("2019 04 17", "+1")); // any of ' ', '-', ':' selects the dashed style
        assertEquals("20190418030405", delta("20190417030405", "+1"));
        assertEquals("2019-04-18 03:04:05", delta("2019-04-17 03:04:05", "+1"));
        assertEquals("20190418030405", delta("20190417030405", "+1", " "));
        assertEquals("2019-04-18 03:04:05", delta("2019-04-17 03:04:05", "+1", " "));
    }

    @Test
    void bareNumberIsDay() {
        assertEquals("20190418", delta("20190417", "+1"));
        assertEquals("20190416", delta("20190417", "-1"));
        assertEquals("20190405", delta("20190417", "5")); // unsigned = set day
        assertEquals("20190420", delta("20190417", " +3 "));
    }

    @Test
    void readmeExamples() {
        assertEquals("2018-12-31", delta("2018-09-06", "y=+1,m=1,d=1,d=-1"));
        assertEquals("20180105030405", delta("20180102030405", "day=+3"));
        assertEquals("2018-01-05 03:04:05", delta("2018-01-02 03:04:05", "day=+3"));
        assertEquals("20180105", delta("20180102", "day=+3"));
        assertEquals("2018-01-05", delta("2018-01-02", "day=+3"));
        assertEquals("20180105", delta("2018-01-02", "day=+3", "yyyyMMdd"));
        assertEquals("2019-04-17 13:00:00", delta("2019-04-17 15:00:00", "h=13"));
        assertEquals("20190430", delta("20190417", "month=+1,day=1,day=-1"));
    }

    @Test
    void addUnits() {
        assertEquals("20210417", delta("20190417", "year=+2"));
        assertEquals("20170417", delta("20190417", "y=-2"));
        assertEquals("20190717", delta("20190417", "month=+3"));
        assertEquals("20181217", delta("20190417", "m=-4"));
        assertEquals("2019-04-17 08:04:05", delta("2019-04-17 03:04:05", "hour=+5"));
        assertEquals("2019-04-17 04:34:05", delta("2019-04-17 03:04:05", "n=+90"));
        assertEquals("2019-04-17 03:04:04", delta("2019-04-17 03:04:05", "s=-1"));
        assertEquals("2019-04-18 00:00:00", delta("2019-04-17 00:00:00", "second=+86400"));
        assertEquals("2019-04-17 15:00:00", delta("2019-04-17 03:04:05", "h=+12,n=-4,s=-5"));
    }

    @Test
    void addClampsToEndOfMonth() {
        assertEquals("20190228", delta("20190131", "m=+1"));
        assertEquals("20200229", delta("20200131", "m=+1"));
        assertEquals("20210228", delta("20200229", "y=+1"));
        assertEquals("20190430", delta("20190331", "month=+1"));
    }

    @Test
    void setUnitsWithinRange() {
        assertEquals("00010417", delta("20190417", "y=1"));
        assertEquals("99990417", delta("20190417", "y=9999"));
        assertEquals("20190117", delta("20190417", "m=1"));
        assertEquals("20191217", delta("20190417", "m=12"));
        assertEquals("20190401", delta("20190417", "d=1"));
        assertEquals("20190331", delta("20190301", "d=31"));
        assertEquals("2019-04-17 00:04:05", delta("2019-04-17 03:04:05", "h=0"));
        assertEquals("2019-04-17 23:04:05", delta("2019-04-17 03:04:05", "h=23"));
        assertEquals("2019-04-17 03:00:05", delta("2019-04-17 03:04:05", "n=0"));
        assertEquals("2019-04-17 03:59:05", delta("2019-04-17 03:04:05", "minute=59"));
        assertEquals("2019-04-17 03:04:00", delta("2019-04-17 03:04:05", "s=0"));
        assertEquals("2019-04-17 03:04:59", delta("2019-04-17 03:04:05", "second=59"));
    }

    @Test
    void hourSetIsTwentyFourHour() {
        // the original used the 12-hour Calendar.HOUR field: h=13 gave 01:00 the next day
        assertEquals("2019-04-17 13:00:00", delta("2019-04-17 15:00:00", "h=13"));
        assertEquals("2019-04-17 05:00:00", delta("2019-04-17 15:00:00", "h=5"));
        assertEquals("2019-04-17 17:00:00", delta("2019-04-17 03:00:00", "h=17"));
        assertEquals("2019-04-17 23:59:59", delta("2019-04-17 00:00:00", "h=23,n=59,s=59"));
    }

    @Test
    void setValueOutOfRangeIsParameterError() {
        for (String bad : new String[] {"y=0", "y=10000", "m=0", "m=13", "d=0", "d=32", "h=24", "n=60", "s=60",
            "year=0", "month=13", "day=32", "hour=24", "minute=60", "second=60", "d=99999"}) {
            rejects("out of range", "20190417030405", bad);
        }
        rejects("out of range", "20190417", "0"); // bare 0 = day=0
        rejects("out of range", "20190417", "32");
    }

    @Test
    void setValueNotValidForTheDateIsNull() {
        assertNull(delta("20190417", "d=31")); // April has 30 days
        assertNull(delta("20190201", "d=30"));
        assertNull(delta("20200229", "y=2019")); // Feb 29 in a non-leap year
        assertNull(delta("20190131", "m=2")); // Feb 31
        assertNull(delta("20190131", "m=4"));
        assertEquals("20240229", delta("20200229", "y=2024"));
        assertEquals("20200229", delta("20200301", "d=29,m=2")); // order inside a set group does not matter
        // sets of year/month/day are resolved together, like java.util.Calendar did
        assertEquals("20190201", delta("20190131", "m=2,d=1"));
        assertEquals("20190201", delta("20190131", "d=1,m=2"));
        assertNull(delta("20190131", "m=2,d=29"));
        // ... but an add-item in between resolves the pending sets first
        assertNull(delta("20190131", "m=2,d=+1"));
    }

    @Test
    void resultOutsideYearRangeIsNull() {
        assertNull(delta("99991231", "d=+1"));
        assertNull(delta("00010101", "d=-1"));
        assertNull(delta("20190417", "y=+2000000000"));
        assertNull(delta("20190417", "y=+8000"));
    }

    @Test
    void unknownUnitIsParameterError() {
        rejects("'x'", "20190417", "x=1");
        rejects("'Y'", "20190417", "Y=1"); // case-sensitive
        rejects("'YEAR'", "20190417", "YEAR=+1");
        rejects("'quarter'", "20190417", "quarter=1");
        rejects("'week'", "20190417", "week=+1");
        rejects("'w'", "20190417", "w=+1");
        rejects("offset unit", "20190417", "=1");
        rejects("offset unit", "20190417", "days=1");
    }

    @Test
    void malformedOffsetsAreParameterErrors() {
        rejects("empty item", "20190417", "day=1,,month=1");
        rejects("empty item", "20190417", ",day=1");
        rejects("unit=value", "20190417", "day=1,month");
        rejects("unit=value", "20190417", "+3,+4");
        rejects("not an integer", "20190417", "day=abc");
        rejects("not an integer", "20190417", "day=");
        rejects("not an integer", "20190417", "day=+");
        rejects("not an integer", "20190417", "day=1.5");
        rejects("not an integer", "20190417", "day=1=2");
        rejects("not an integer", "20190417", "day=+-1");
        rejects("not an integer", "20190417", "day=99999999999");
        rejects("not an integer", "20190417", "day=٣"); // non-ASCII digit
    }

    @Test
    void trailingCommaAndWhitespaceAreAllowed() {
        assertEquals("20190420", delta("20190417", "day=+3,"));
        assertEquals("20190420", delta("20190417", " day = +3 "));
        assertEquals("20190420", delta("20190417", "d =+3 ,"));
        assertEquals("20190417", delta("20190417", ","));
    }

    @Test
    void parameterErrorsWinOverInvalidDates() {
        assertThrows(IllegalArgumentException.class, () -> delta("abc", "x=1"));
        assertThrows(IllegalArgumentException.class, () -> delta("abc", "+1", "bad'"));
        assertNull(delta("abc", "+1"));
    }

    @Test
    void invalidDateIsNull() {
        for (String bad : new String[] {"", " ", "abc", "2019-1-7", "20191332", "20190230", "20190431", "20190229",
            "2019/04/17", "2019041", "201904170", "2019041703040", "201904170304056", "20190417240000",
            "20190417236000", "20190417235960", "00000101", "2019-04-17T03:04:05", "２０１９０４１７",
            "2019-04-17 03:04:05.123"}) {
            assertNull(delta(bad, "+1"), "date=" + bad);
            assertNull(delta(bad, "+1", "yyyyMMdd"), "date=" + bad);
        }
    }

    @Test
    void separatorsAreIgnoredAnywhere() {
        assertEquals("20190418", delta(" 2019-04-17 ", "+1", "yyyyMMdd"));
        assertEquals("20190418", delta("2019:04:17", "+1", "yyyyMMdd"));
        assertEquals("20190418", delta("2-0-1-9-0-4-1-7", "+1", "yyyyMMdd"));
    }

    @Test
    void formatArgument() {
        assertEquals("20190418", delta("2019-04-17", "+1", "yyyyMMdd"));
        assertEquals("2019-04-18 00:00", delta("20190417", "+1", "yyyy-MM-dd HH:mm"));
        assertEquals("Thu, 18 Apr 2019", delta("20190417", "+1", "EEE, d MMM yyyy"));
        assertEquals("2019-04-18", delta("20190417030405", "+1", "yyyy-MM-dd"));
        assertEquals("19/04/18", delta("20190417", "+1", "yy/MM/dd"));
        assertEquals("20190418", delta("20190417", "+1", ""));
    }

    @Test
    void nullFormatIsParameterError() {
        rejects("format", "20190417", "+1", null);
    }

    @Test
    void invalidPatternIsParameterError() {
        rejects("bad'", "20190417", "+1", "bad'");
        rejects("invalid format pattern", "20190417", "+1", "yyyy-MM-dd bbb");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_US", "zh_CN", "de_DE", "fr_FR", "ar_SA", "th_TH"})
    void defaultLocaleDoesNotMatter(String tag) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(tag.replace('_', '-')));
            assertEquals("Thu, 18 Apr 2019 | 2019-04-18", delta("20190417", "+1", "EEE, d MMM yyyy")
                    + " | " + delta("20190417", "+1", "yyyy-MM-dd"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
