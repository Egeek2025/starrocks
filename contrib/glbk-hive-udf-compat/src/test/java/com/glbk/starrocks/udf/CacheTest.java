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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offsets cache and the per-thread pattern cache must never change results. */
public class CacheTest {

    @Test
    void badOffsetsAreNotCached() {
        GlDateDelta f = new GlDateDelta();
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalArgumentException.class, () -> f.evaluate("20190417", "m=13"));
            assertThrows(IllegalArgumentException.class, () -> f.evaluate("20190417", "x=1"));
        }
        assertEquals("20190420", f.evaluate("20190417", "day=+3"));
    }

    @Test
    void sameOffsetsDifferentDates() {
        GlDateDelta f = new GlDateDelta();
        assertEquals("20190131", f.evaluate("20190101", "m=1,d=31"));
        assertEquals(null, f.evaluate("20190401", "m=4,d=31")); // April 31 does not exist
        assertEquals("20190301", f.evaluate("20190131", "m=3,d=1"));
        assertEquals("2019-03-01", f.evaluate("2019-01-31", "m=3,d=1"));
    }

    @Test
    void manyDistinctOffsetsAndPatternsStillCorrect() {
        GlDateDelta delta = new GlDateDelta();
        GlDateFormat format = new GlDateFormat();
        for (int i = 1; i <= 1000; i++) { // well above both cache limits, so the caches are cleared repeatedly
            String expected = java.time.LocalDate.of(2019, 1, 1).plusDays(i).toString().replace("-", "");
            assertEquals(expected, delta.evaluate("20190101", "day=+" + i));
            String pattern = "yyyy/MM/dd '" + i + "'";
            assertEquals("2019/04/17 " + i, format.evaluate("20190417", pattern));
        }
    }

    @Test
    void customPatternsAreThreadSafe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int seed = t;
                results.add(pool.submit(() -> {
                    GlDateFormat f = new GlDateFormat();
                    GlDateDelta d = new GlDateDelta();
                    for (int i = 0; i < 20000; i++) {
                        int day = 1 + (i + seed) % 28;
                        String in = String.format("2019-04-%02d 10:20:30", day);
                        String want = String.format("19/04/%02d 10.20.30 EEE", day);
                        String got = f.evaluate(in, "yy/MM/dd HH.mm.ss 'EEE'");
                        if (!want.equals(got)) {
                            return false;
                        }
                        String want2 = String.format("201904%02d", day);
                        if (!want2.equals(d.evaluate(in, "+0", "yyyyMMdd"))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> r : results) {
                assertTrue(r.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nowFollowsDefaultZoneChanges() {
        TimeZone saved = TimeZone.getDefault();
        try {
            GlNow now = new GlNow();
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")); // UTC+14
            String east = now.evaluate("Z");
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago")); // UTC-11
            String west = now.evaluate("Z");
            assertEquals("+1400", east);
            assertEquals("-1100", west);
        } finally {
            TimeZone.setDefault(saved);
        }
    }
}
