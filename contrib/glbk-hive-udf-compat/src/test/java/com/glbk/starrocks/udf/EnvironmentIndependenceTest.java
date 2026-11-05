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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Results must not depend on the JVM time zone or default locale, and the UDFs must be quiet and thread-safe. */
class EnvironmentIndependenceTest {
    private static final String[] ZONES = {"UTC", "Asia/Shanghai", "America/New_York", "Pacific/Kiritimati",
        "Europe/London"};
    private static final String[] LOCALES = {"en-US", "zh-CN", "de-DE", "fr-FR", "ar-SA", "th-TH", "ja-JP-u-ca-japanese"};

    private TimeZone savedZone;
    private Locale savedLocale;

    @BeforeEach
    void save() {
        savedZone = TimeZone.getDefault();
        savedLocale = Locale.getDefault();
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(savedZone);
        Locale.setDefault(savedLocale);
    }

    private static String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String d : new String[] {"20190417", "2019-04-17 07:00:00", "2019-03-10 02:30:00", "2019-11-03 01:30:00",
            "2018-12-30", "2019-01-01", "19691231235959", "20200229"}) {
            sb.append(new GlDateDelta().evaluate(d, "h=+30,m=+1")).append('|');
            sb.append(new GlDateDelta().evaluate(d, "h=13,d=+1", "EEE MMM d yyyy hh a")).append('|');
            sb.append(new GlDateFormat().evaluate(d, "EEEE, MMMM d, yyyy G ww")).append('|');
            sb.append(new GlDateDiff().evaluate(d, "2019-04-16 09:00:00", "day")).append('|');
            for (String u : new String[] {"y", "q", "m", "w", "d", "h", "n", "s"}) {
                sb.append(new GlDateDiff().evaluate(d, "2017-01-01", u)).append(',');
                sb.append(new GlDateDiff().evaluate("2021-01-02 23:59:59", d, u)).append(',');
            }
            for (String u : new String[] {"y", "q", "m", "w"}) {
                sb.append(new GlDateFirst().evaluate(d, u)).append(',');
                sb.append(new GlDateLast().evaluate(d, u)).append(',');
                sb.append(new GlHowManyDay().evaluate(d, u)).append(',');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void sameResultsInEveryZoneAndLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.US);
        String expected = snapshot();
        assertTrue(expected.contains("20190414"), expected);
        for (String zone : ZONES) {
            for (String loc : LOCALES) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Locale.setDefault(Locale.forLanguageTag(loc));
                assertEquals(expected, snapshot(), zone + " " + loc);
            }
        }
    }

    @Test
    void weekFirstAndLastUnderChineseLocale() {
        for (String loc : LOCALES) {
            Locale.setDefault(Locale.forLanguageTag(loc));
            assertEquals("20190414", new GlDateFirst().evaluate("20190417", "week"), loc);
            assertEquals("20190420", new GlDateLast().evaluate("20190417", "week"), loc);
            assertEquals("4", new GlHowManyDay().evaluate("20190417", "week"), loc);
            assertEquals("0", new GlDateDiff().evaluate("20190414", "20190420", "w"), loc);
        }
    }

    @Test
    void nowFollowsTheJvmDefaultZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")); // UTC+14
        String east = new GlNow().evaluate("yyyyMMddHH");
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago")); // UTC-11
        String west = new GlNow().evaluate("yyyyMMddHH");
        assertTrue(!east.equals(west), east + " " + west);
        Locale.setDefault(Locale.forLanguageTag("th-TH"));
        assertTrue(new GlNowNoArg().evaluate().startsWith("20"), "Gregorian digits and year even under th_TH");
        assertTrue(new GlNow().evaluate("EEE").matches("[A-Z][a-z]{2}"));
    }

    @Test
    void neverWritesToStdoutOrStderr() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true);
        System.setOut(sink);
        System.setErr(sink);
        try {
            for (String bad : new String[] {null, "", "abc", "2019-1-7", "20191332", "2019/04/17"}) {
                new GlDateDelta().evaluate(bad, "+1");
                new GlDateDiff().evaluate(bad, "20190417", "day");
                new GlDateFormat().evaluate(bad, "yyyy");
                new GlHowManyDay().evaluate(bad, "year");
                new GlDateFirst().evaluate(bad, "year");
                new GlDateLast().evaluate(bad, "year");
            }
            try {
                new GlDateDelta().evaluate("20190417", "x=1");
            } catch (IllegalArgumentException expected) {
                // fine
            }
            new GlNow().evaluate();
            new GlNowNoArg().evaluate();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals("", captured.toString());
    }

    @Test
    void threadSafeUnderConcurrency() throws Exception {
        final GlDateDelta delta = new GlDateDelta(); // one shared instance on purpose
        final GlDateFormat format = new GlDateFormat();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int id = t;
                futures.add(pool.submit((Callable<String>) () -> {
                    for (int i = 0; i < 20000; i++) {
                        String pattern = (id + i) % 2 == 0 ? "yyyy/MM/dd EEE" : "dd.MM.yy HH:mm";
                        String expected = (id + i) % 2 == 0 ? "2019/04/18 Thu" : "18.04.19 00:00";
                        String got = delta.evaluate("20190417", "d=+1", pattern);
                        if (!expected.equals(got)) {
                            return "delta " + got + " != " + expected;
                        }
                        if (!"2019-04-17".equals(format.evaluate("2019-04-17 12:00:00", "yyyy-MM-dd"))) {
                            return "format";
                        }
                    }
                    return "ok";
                }));
            }
            for (Future<String> f : futures) {
                assertEquals("ok", f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
