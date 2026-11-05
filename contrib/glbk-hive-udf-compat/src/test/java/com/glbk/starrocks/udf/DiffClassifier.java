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

import com.glbk.starrocks.udf.HiveOracle.Outcome;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains differences between the original Hive outcome and the corrected outcome. The explanation is
 * computed from the INPUTS (with independent re-implementations of the date and offset grammars), then the
 * shape of the two outcomes is checked as well. Returns null when a difference is not explained.
 */
final class DiffClassifier {
    static final String QUARTER_FIX = "QUARTER_FIX";
    static final String WEEK_FIX = "WEEK_FIX";
    static final String HOUR_24_FIX = "HOUR_24_FIX";
    static final String CALENDAR_DAY_FIX = "CALENDAR_DAY_FIX";
    static final String STRICT_PARSE = "STRICT_PARSE";
    static final String STRICT_SET = "STRICT_SET";
    static final String PARAM_ERROR_POLICY = "PARAM_ERROR_POLICY";
    static final String UNKNOWN_INTERVAL = "UNKNOWN_INTERVAL";
    static final String WALL_CLOCK_TZ = "WALL_CLOCK_TZ";
    static final String PRE_EPOCH_TRUNC = "PRE_EPOCH_TRUNC";
    static final String OFFSET_TRIM = "OFFSET_TRIM";
    static final String YEAR_RANGE = "YEAR_RANGE";

    static final List<String> ALL = Arrays.asList(QUARTER_FIX, WEEK_FIX, HOUR_24_FIX, CALENDAR_DAY_FIX, STRICT_PARSE,
            STRICT_SET, PARAM_ERROR_POLICY, UNKNOWN_INTERVAL, WALL_CLOCK_TZ, PRE_EPOCH_TRUNC, OFFSET_TRIM,
            YEAR_RANGE);

    private static final DateTimeFormatter STRICT_14 =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final LocalDateTime EPOCH_PLUS_1 = LocalDateTime.of(1970, 1, 2, 0, 0);
    private static final long WINDOW_DAYS = 3;

    private DiffClassifier() {
    }

    // ---------------------------------------------------------------- independent grammars

    /** Independent strict parse: null when invalid. */
    static LocalDateTime strictParse(String s) {
        if (s == null) {
            return null;
        }
        String ds = s.replace(" ", "").replace("-", "").replace(":", "");
        if (!ds.matches("[0-9]{8}|[0-9]{14}")) {
            return null;
        }
        if (ds.length() == 8) {
            ds = ds + "000000";
        }
        try {
            LocalDateTime t = LocalDateTime.parse(ds, STRICT_14);
            return t.getYear() < 1 ? null : t;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasTimePart(String s) {
        return s.replace(" ", "").replace("-", "").replace(":", "").length() == 14;
    }

    private static boolean validPattern(String p) {
        if (p == null) {
            return false;
        }
        try {
            new SimpleDateFormat(p, Locale.US);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Result of analysing an offsets string with a regex grammar. */
    static final class Offsets {
        boolean malformed;
        boolean outOfRange;
        boolean hourSet; // unsigned hour 0..23
        boolean ymdSet; // unsigned year/month/day within range
        boolean valueTrim; // whitespace around the value (original threw NumberFormatException)
    }

    private static final Pattern ITEM = Pattern.compile("^([^=]*)=(.*)$", Pattern.DOTALL);
    private static final Pattern INT = Pattern.compile("[+-]?[0-9]+");
    private static final Map<String, int[]> RANGES = new HashMap<>();

    static {
        for (String[] u : new String[][] {{"year", "y"}, {"month", "m"}, {"day", "d"}, {"hour", "h"},
            {"minute", "n"}, {"second", "s"}}) {
            int[] r;
            switch (u[0]) {
                case "year":
                    r = new int[] {1, 9999};
                    break;
                case "month":
                    r = new int[] {1, 12};
                    break;
                case "day":
                    r = new int[] {1, 31};
                    break;
                case "hour":
                    r = new int[] {0, 23};
                    break;
                default:
                    r = new int[] {0, 59};
                    break;
            }
            RANGES.put(u[0], r);
            RANGES.put(u[1], r);
        }
    }

    static Offsets analyzeOffsets(String offsets) {
        Offsets o = new Offsets();
        if (blank(offsets)) {
            return o;
        }
        String text = offsets.contains(",") || offsets.contains("=") ? offsets : "day=" + offsets;
        for (String raw : text.split(",")) {
            Matcher m = ITEM.matcher(raw.trim());
            if (!m.matches()) {
                o.malformed = true;
                continue;
            }
            String unit = m.group(1).trim();
            String rawValue = m.group(2);
            String value = rawValue.trim();
            int[] range = RANGES.get(unit);
            if (range == null || !INT.matcher(value).matches()) {
                o.malformed = true;
                continue;
            }
            long v;
            try {
                v = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                o.malformed = true;
                continue;
            }
            if (!rawValue.equals(value)) {
                o.valueTrim = true;
            }
            boolean signed = value.startsWith("+") || value.startsWith("-");
            if (!signed) {
                if (v < range[0] || v > range[1]) {
                    o.outOfRange = true;
                } else if (unit.equals("h") || unit.equals("hour")) {
                    o.hourSet = true;
                } else if (Arrays.asList("year", "month", "day", "y", "m", "d").contains(unit)) {
                    o.ymdSet = true;
                }
            }
        }
        return o;
    }

    private static final List<String> INTERVALS =
            Arrays.asList("year", "y", "quarter", "q", "month", "m", "week", "w", "day", "d", "hour", "h", "minute", "n",
                    "second", "s");
    private static final List<String> DTYPES =
            Arrays.asList("year", "y", "quarter", "q", "month", "m", "week", "w");

    // ---------------------------------------------------------------- time zone helpers

    /** True when the zone has a UTC-offset transition within +-WINDOW_DAYS of any of the given wall-clock times. */
    static boolean nearTransition(String zoneId, List<LocalDateTime> times) {
        ZoneRules rules = ZoneId.of(zoneId).getRules();
        for (LocalDateTime t : times) {
            Instant i = t.toInstant(ZoneOffset.UTC);
            ZoneOffsetTransition next = rules.nextTransition(i.minusSeconds(WINDOW_DAYS * 86400));
            if (next != null && next.getInstant().isBefore(i.plusSeconds(WINDOW_DAYS * 86400))) {
                return true;
            }
        }
        return false;
    }

    private static boolean betweenHasTransition(String zoneId, LocalDateTime a, LocalDateTime b) {
        LocalDateTime lo = a.isBefore(b) ? a : b;
        LocalDateTime hi = a.isBefore(b) ? b : a;
        ZoneRules rules = ZoneId.of(zoneId).getRules();
        Instant from = lo.toInstant(ZoneOffset.UTC).minusSeconds(86400);
        ZoneOffsetTransition next = rules.nextTransition(from);
        return next != null && next.getInstant().isBefore(hi.toInstant(ZoneOffset.UTC).plusSeconds(86400));
    }

    private static boolean preEpoch(List<LocalDateTime> times) {
        for (LocalDateTime t : times) {
            if (t.isBefore(EPOCH_PLUS_1)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- classification

    /**
     * @return a category name, or null if the difference between {@code hive} and {@code fixed} is not explained
     */
    static String classify(String fn, String[] args, String zoneId, Outcome hive, Outcome fixed) {
        boolean zoneIsUtc = zoneId.equals("UTC");
        int n = args.length;
        // number and position of date arguments, and the parameter check
        List<String> dates = new ArrayList<>();
        boolean paramBad = false;
        boolean intervalBad = false;
        boolean offsetsMalformed = false;
        boolean offsetsOutOfRange = false;
        Offsets off = new Offsets();
        String unit = null; // interval / dtype
        switch (fn) {
            case "gl_date_delta":
                if (n < 2 || n > 3 || args[0] == null) {
                    return null;
                }
                dates.add(args[0]);
                off = analyzeOffsets(args[1]);
                offsetsMalformed = off.malformed;
                offsetsOutOfRange = !off.malformed && off.outOfRange;
                if (n == 3 && (args[2] == null || (!blank(args[2]) && !validPattern(args[2])))) {
                    paramBad = true;
                }
                paramBad |= offsetsMalformed || offsetsOutOfRange;
                break;
            case "gl_date_diff":
                if (n < 2 || n > 3 || args[0] == null || args[1] == null) {
                    return null;
                }
                dates.add(args[0]);
                dates.add(args[1]);
                unit = n == 3 ? args[2] : "day";
                intervalBad = !INTERVALS.contains(unit);
                paramBad = intervalBad;
                break;
            case "gl_date_format":
                if (n < 1 || n > 2 || args[0] == null) {
                    return null;
                }
                dates.add(args[0]);
                paramBad = n == 2 && !validPattern(args[1]);
                break;
            case "gl_now":
                return null; // no date inputs: any difference is unexplained
            default: // how_many_day, first, last
                if (n != 2 || args[0] == null) {
                    return null;
                }
                dates.add(args[0]);
                unit = args[1];
                paramBad = !DTYPES.contains(unit);
                break;
        }

        if (paramBad) {
            if (fixed.kind != Outcome.Kind.THROW) {
                return null;
            }
            if (fn.equals("gl_date_diff") && intervalBad) {
                return hive.kind == Outcome.Kind.VALUE ? UNKNOWN_INTERVAL : null;
            }
            if (fn.equals("gl_date_delta") && !offsetsMalformed && offsetsOutOfRange) {
                return STRICT_SET;
            }
            return PARAM_ERROR_POLICY;
        }

        List<LocalDateTime> times = new ArrayList<>();
        boolean invalid = false;
        for (String d : dates) {
            LocalDateTime t = strictParse(d);
            if (t == null) {
                invalid = true;
            } else {
                times.add(t);
            }
        }
        if (invalid) {
            return fixed.kind == Outcome.Kind.NULL ? STRICT_PARSE : null;
        }

        boolean bothValues = hive.kind == Outcome.Kind.VALUE && fixed.kind == Outcome.Kind.VALUE;
        boolean nonUtcNear = !zoneIsUtc && nearTransition(zoneId, times);
        switch (fn) {
            case "gl_date_delta":
                if (fixed.kind == Outcome.Kind.NULL && hive.kind == Outcome.Kind.VALUE) {
                    if (off.ymdSet) {
                        return STRICT_SET;
                    }
                    // the original printed years above 9999 (e.g. 100000101); the fixed version returns NULL
                    return times.get(0).getYear() == 9999 ? YEAR_RANGE : null;
                }
                if (hive.kind == Outcome.Kind.THROW && fixed.kind != Outcome.Kind.THROW) {
                    // original threw on " +3 " (NumberFormatException); the fixed version trims the value,
                    // and the result may then be NULL for one of the other reasons above
                    boolean nullExplained = off.ymdSet || times.get(0).getYear() == 9999;
                    return off.valueTrim && (fixed.kind == Outcome.Kind.VALUE || nullExplained) ? OFFSET_TRIM : null;
                }
                if (bothValues && off.hourSet) {
                    return HOUR_24_FIX;
                }
                if (bothValues && nonUtcNear) {
                    return WALL_CLOCK_TZ;
                }
                return null;
            case "gl_date_diff":
                if (!bothValues) {
                    return null;
                }
                switch (unit) {
                    case "quarter":
                    case "q":
                        return QUARTER_FIX;
                    case "week":
                    case "w":
                        return WEEK_FIX;
                    case "day":
                    case "d":
                        return !zoneIsUtc || preEpoch(times) ? CALENDAR_DAY_FIX : null;
                    case "hour":
                    case "h":
                    case "minute":
                    case "n":
                    case "second":
                    case "s":
                        // elapsed time (original) vs wall-clock fields (new) differ when a zone transition
                        // lies between the two dates
                        if (!zoneIsUtc && betweenHasTransition(zoneId, times.get(0), times.get(1))) {
                            return WALL_CLOCK_TZ;
                        }
                        return preEpoch(times) ? PRE_EPOCH_TRUNC : null;
                    default:
                        return null;
                }
            case "gl_date_format":
                return bothValues && nonUtcNear && hasTimePart(dates.get(0)) ? WALL_CLOCK_TZ : null;
            case "gl_how_many_day":
                if (bothValues && (unit.equals("quarter") || unit.equals("q"))) {
                    LocalDateTime t = times.get(0);
                    LocalDate qStart = LocalDate.of(t.getYear(), (t.getMonthValue() - 1) / 3 * 3 + 1, 1);
                    if (preEpoch(times) || (!zoneIsUtc && betweenHasTransition(zoneId, qStart.atTime(t.toLocalTime()), t))) {
                        return CALENDAR_DAY_FIX;
                    }
                }
                return null;
            case "gl_date_last":
                // the last day of the week of 9999-12-31 is in year 10000
                return fixed.kind == Outcome.Kind.NULL && hive.kind == Outcome.Kind.VALUE
                        && times.get(0).getYear() == 9999 ? YEAR_RANGE : null;
            default:
                return null;
        }
    }
}
