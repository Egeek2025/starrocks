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

package com.glbk.starrocks.udf.hive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The examples from the original glbk-hive-udf README, run against the StarRocks ports. */
public class ReadmeExamplesTest {
    private static String delta(String... a) {
        return new GlDateDeltaHive().evaluate(a);
    }

    @Test
    void dateDelta() {
        assertEquals("20180105", delta("20180102", "+3"));
        assertEquals("20180105", delta("20180102", "day=+3"));
        assertEquals("2018-01-05", delta("2018-01-02", "day=+3"));
        assertEquals("20180105030405", delta("20180102030405", "day=+3"));
        assertEquals("2018-01-05 03:04:05", delta("2018-01-02 03:04:05", "day=+3"));
        assertEquals("2018-12-31", delta("2018-09-06", "year=+1,month=1,day=1,day=-1"));
        assertEquals("20180831", delta("20180906", "day=1,day=-1"));
        assertEquals("2018-12-31", delta("2018-09-06", "y=+1,m=1,d=1,d=-1"));
        assertEquals("20180831", delta("2018-09-06", "day=1,day=-1", "yyyyMMdd"));
    }

    @Test
    void dateDiff() {
        GlDateDiffHive f = new GlDateDiffHive();
        // README says -31 / -1 / -12 (date1 - date2); the @Description text saying '31' is wrong.
        assertEquals("-31", f.evaluate("20180101", "20180201", "day"));
        assertEquals("-1", f.evaluate("20170101", "20180201", "year"));
        assertEquals("-13", f.evaluate("20170101", "20180201", "month"));
        assertEquals("-31", f.evaluate("2018-01-01", "2018-02-01", "day"));
        assertEquals("106", f.evaluate("20190417", new GlDateFirstHive().evaluate("20190417", "year")));
        assertEquals("16", f.evaluate("20190417", new GlDateFirstHive().evaluate("20190417", "quarter")));
    }

    @Test
    void dateFormat() {
        GlDateFormatHive f = new GlDateFormatHive();
        assertEquals("20180102", f.evaluate("2018-01-02"));
        assertEquals("20180102", f.evaluate("2018-01-02", "yyyyMMdd"));
        assertEquals("20180102", f.evaluate("2018-01-02 03:04:05", "yyyyMMdd"));
        assertEquals("2018-01-02", f.evaluate("20180102030405", "yyyy-MM-dd"));
        assertEquals("2018-01-02 03:04:05", f.evaluate("20180102030405", "yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    void firstLastHowMany() {
        assertEquals("20190101", new GlDateFirstHive().evaluate("20190417", "year"));
        assertEquals("20190401", new GlDateFirstHive().evaluate("20190417", "quarter"));
        assertEquals("20190401", new GlDateFirstHive().evaluate("20190417", "month"));
        assertEquals("20190414", new GlDateFirstHive().evaluate("20190417", "week"));
        assertEquals("20191231", new GlDateLastHive().evaluate("20190417", "year"));
        assertEquals("20190630", new GlDateLastHive().evaluate("20190417", "quarter"));
        assertEquals("20190430", new GlDateLastHive().evaluate("20190417", "month"));
        assertEquals("20190420", new GlDateLastHive().evaluate("20190417", "week"));
        assertEquals("107", new GlHowManyDayHive().evaluate("20190417", "year"));
        assertEquals("17", new GlHowManyDayHive().evaluate("20190417", "quarter"));
        assertEquals("17", new GlHowManyDayHive().evaluate("20190417", "month"));
        assertEquals("4", new GlHowManyDayHive().evaluate("20190417", "week"));
    }
}
