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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** Tests of the shared parsing and formatting helpers. */
class GlDatesCoreTest {
    @Test
    void parseAcceptsOnlyTheFourShapes() {
        assertEquals(LocalDateTime.of(2019, 4, 17, 0, 0), GlDates.parse("20190417"));
        assertEquals(LocalDateTime.of(2019, 4, 17, 0, 0), GlDates.parse("2019-04-17"));
        assertEquals(LocalDateTime.of(2019, 4, 17, 3, 4, 5), GlDates.parse("20190417030405"));
        assertEquals(LocalDateTime.of(2019, 4, 17, 3, 4, 5), GlDates.parse("2019-04-17 03:04:05"));
        assertEquals(LocalDateTime.of(2019, 4, 17, 3, 4, 5), GlDates.parse(" 2019-04-17   03:04:05 "));
        assertNull(GlDates.parse(null));
        for (String bad : new String[] {"", "2019-1-7", "201917", "2019041703", "20190417030", "abc", "2019.04.17",
            "20190417T030405", "20190230", "20190229", "20191301", "20190001", "20190100", "20190132",
            "20190417240000", "20190417006000", "20190417000060", "00000101", "00001231", "1234567890123456"}) {
            assertNull(GlDates.parse(bad), bad);
        }
        assertEquals(LocalDateTime.of(2020, 2, 29, 23, 59, 59), GlDates.parse("20200229235959"));
        assertEquals(LocalDateTime.of(1, 1, 1, 0, 0), GlDates.parse("00010101"));
        assertEquals(LocalDateTime.of(9999, 12, 31, 23, 59, 59), GlDates.parse("99991231235959"));
    }

    @Test
    void parseAgreesWithStrictDateTimeFormatter() {
        DateTimeFormatter strict = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
        Random rnd = new Random(42);
        for (int i = 0; i < 100000; i++) {
            String s = String.format("%04d%02d%02d%02d%02d%02d", 1 + rnd.nextInt(9999), rnd.nextInt(14),
                    rnd.nextInt(33), rnd.nextInt(26), rnd.nextInt(62), rnd.nextInt(62));
            LocalDateTime expected;
            try {
                expected = LocalDateTime.parse(s, strict);
            } catch (RuntimeException e) {
                expected = null;
            }
            assertEquals(expected, GlDates.parse(s), s);
        }
    }

    @Test
    void fastFormatsAgreeWithSimpleDateFormat() throws Exception {
        String[] patterns = {"yyyyMMdd", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyy-MM-dd HH:mm:ss"};
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            LocalDate d = LocalDate.of(1 + rnd.nextInt(9999), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
            LocalDateTime t = d.atTime(rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60));
            for (String p : patterns) {
                SimpleDateFormat sdf = new SimpleDateFormat(p, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                ((java.util.GregorianCalendar) sdf.getCalendar()).setGregorianChange(new Date(Long.MIN_VALUE));
                String expected = sdf.format(new Date(t.toEpochSecond(ZoneOffset.UTC) * 1000L));
                assertEquals(expected, DateFormats.wallClock("t", p).format(t), t + " " + p);
            }
        }
    }

    @Test
    void wallClockFormattingIgnoresDefaultZone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            LocalDateTime t = LocalDateTime.of(2019, 3, 10, 2, 30, 0); // does not exist in America/New_York
            for (String zone : new String[] {"UTC", "Asia/Shanghai", "America/New_York", "Pacific/Kiritimati"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals("2019-03-10 02:30:00 AM", DateFormats.wallClock("t", "yyyy-MM-dd hh:mm:ss a").format(t), zone);
            }
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void prolepticGregorianBefore1582() {
        assertEquals("1500-03-01", DateFormats.wallClock("t", "yyyy-MM-dd ").format(LocalDateTime.of(1500, 3, 1, 0, 0)).trim());
        assertEquals(LocalDate.of(1500, 3, 1).getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US),
                DateFormats.wallClock("t", "EEE").format(LocalDateTime.of(1500, 3, 1, 0, 0)));
    }

    @Test
    void invalidPatternsThrowWithFunctionNameAndPattern() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DateFormats.wallClock("gl_x", "bad'"));
        assertEquals(true, e.getMessage().startsWith("gl_x: invalid format pattern 'bad''"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DateFormats.wallClock("gl_x", null));
        assertThrows(IllegalArgumentException.class, () -> DateFormats.now("gl_x", "Q"));
    }
}
