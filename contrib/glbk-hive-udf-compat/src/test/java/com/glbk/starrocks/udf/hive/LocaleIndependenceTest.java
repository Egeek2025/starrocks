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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Week results of the compat set must not depend on the JVM default Locale (nor the JDK version):
 * they behave as the original Hive did on JDK 8 / Locale.US, i.e. weeks start on Sunday.
 */
public class LocaleIndependenceTest {
    private Locale savedLocale;
    private TimeZone savedZone;

    @BeforeEach
    void save() {
        savedLocale = Locale.getDefault();
        savedZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @AfterEach
    void restore() {
        Locale.setDefault(savedLocale);
        TimeZone.setDefault(savedZone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_US", "zh_CN", "de_DE", "ar_SA", "fr_FR"})
    void weekIsSundayFirstInAnyDefaultLocale(String tag) {
        String[] parts = tag.split("_");
        Locale.setDefault(new Locale(parts[0], parts[1]));
        // 2019-04-17 is a Wednesday; the Sunday-first week is 2019-04-14 .. 2019-04-20.
        assertEquals("20190414", new GlDateFirstHive().evaluate("20190417", "week"));
        assertEquals("20190420", new GlDateLastHive().evaluate("20190417", "week"));
        assertEquals("4", new GlHowManyDayHive().evaluate("20190417", "week"));
        // A Sunday starts its own week and a Saturday ends it.
        assertEquals("20190414", new GlDateFirstHive().evaluate("20190414", "week"));
        assertEquals("20190420", new GlDateLastHive().evaluate("20190420", "week"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh_CN", "de_DE"})
    void dayNamesUseUsLocale(String tag) {
        String[] parts = tag.split("_");
        Locale.setDefault(new Locale(parts[0], parts[1]));
        assertEquals("Wed", new GlDateFormatHive().evaluate("20190417", "EEE"));
    }
}
