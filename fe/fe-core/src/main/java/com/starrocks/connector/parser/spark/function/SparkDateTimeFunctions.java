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

package com.starrocks.connector.parser.spark.function;

import com.google.common.collect.ImmutableMap;
import com.starrocks.connector.parser.spark.function.JavaDateTimePatternTranslator.Piece;
import com.starrocks.connector.parser.spark.function.JavaDateTimePatternTranslator.UnsupportedPatternException;
import com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.Call;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.SlotRef;
import com.starrocks.type.DateType;
import com.starrocks.type.IntegerType;
import com.starrocks.type.VarcharType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.register;
import static com.starrocks.connector.parser.spark.function.SparkExprs.cast;
import static com.starrocks.connector.parser.spark.function.SparkExprs.constStr;
import static com.starrocks.connector.parser.spark.function.SparkExprs.fn;
import static com.starrocks.connector.parser.spark.function.SparkExprs.nul;
import static com.starrocks.connector.parser.spark.function.SparkExprs.str;

/**
 * Date and time functions of the Spark adapter, see {@link Spark2SRFunctionCallTransformer}.
 *
 * <p>Spark writes datetime patterns the Java way ({@code yyyy-MM-dd HH:mm:ss}), StarRocks the MySQL way
 * ({@code %Y-%m-%d %H:%i:%s}); a constant pattern is translated by {@link JavaDateTimePatternTranslator}, anything
 * else is rejected.
 */
final class SparkDateTimeFunctions {
    private static final String DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss";

    // timestampadd units to the StarRocks function that adds them
    private static final Map<String, String> ADD_FUNCTIONS = ImmutableMap.<String, String>builder()
            .put("year", "years_add").put("quarter", "quarters_add").put("month", "months_add")
            .put("week", "weeks_add").put("day", "days_add").put("hour", "hours_add")
            .put("minute", "minutes_add").put("second", "seconds_add").put("millisecond", "milliseconds_add")
            .put("microsecond", "microseconds_add")
            .build();

    // timestampdiff units to the unit of date_diff
    private static final Map<String, String> DIFF_UNITS = ImmutableMap.<String, String>builder()
            .put("year", "year").put("quarter", "quarter").put("month", "month").put("week", "week")
            .put("day", "day").put("hour", "hour").put("minute", "minute").put("second", "second")
            .put("millisecond", "millisecond")
            .build();

    // Spark spellings of the units of date_trunc and trunc to the unit of StarRocks' date_trunc
    private static final Map<String, String> TRUNC_UNITS = ImmutableMap.<String, String>builder()
            .put("year", "year").put("yyyy", "year").put("yy", "year")
            .put("quarter", "quarter")
            .put("month", "month").put("mon", "month").put("mm", "month")
            .put("week", "week")
            .put("day", "day").put("dd", "day")
            .put("hour", "hour").put("minute", "minute").put("second", "second")
            .put("millisecond", "millisecond").put("microsecond", "microsecond")
            .build();

    // trunc(date, fmt) accepts the units of a date only
    private static final Set<String> TRUNC_DATE_UNITS = Set.of("year", "quarter", "month", "week");

    private static final Map<String, String> DAY_OF_WEEK = ImmutableMap.<String, String>builder()
            .put("su", "Su").put("sun", "Sun").put("sunday", "Sunday")
            .put("mo", "Mo").put("mon", "Mon").put("monday", "Monday")
            .put("tu", "Tu").put("tue", "Tue").put("tuesday", "Tuesday")
            .put("we", "We").put("wed", "Wed").put("wednesday", "Wednesday")
            .put("th", "Th").put("thu", "Thu").put("thursday", "Thursday")
            .put("fr", "Fr").put("fri", "Fri").put("friday", "Friday")
            .put("sa", "Sa").put("sat", "Sat").put("saturday", "Saturday")
            .build();

    private SparkDateTimeFunctions() {
    }

    static void registerAll() {
        registerFormatting();
        registerConversions();
        registerArithmetic();
        registerTruncation();
        registerTimeZones();
        registerEpoch();
    }

    private static IntLiteral intLit(long value) {
        return new IntLiteral(value, IntegerType.INT);
    }

    /** The unit named by an argument: a string literal, or the bare keyword (day, hour, ...) as written in Spark. */
    private static String unitOf(Expr e) {
        String text = constStr(e);
        if (text == null && e instanceof SlotRef) {
            SlotRef slot = (SlotRef) e;
            if (slot.getTblNameWithoutAnalyzed() == null) {
                text = slot.getColumnName();
            }
        }
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    /** date_format of the expression with a Java pattern; a fraction of seconds is cut to the digits asked for. */
    private static Expr formatWith(Expr ts, String javaPattern) {
        List<Piece> pieces = JavaDateTimePatternTranslator.toMysqlFormatPieces(javaPattern);
        if (pieces.size() == 1 && !pieces.get(0).isFraction()) {
            return fn("date_format", ts, str(pieces.get(0).getMysql()));
        }
        List<Expr> parts = new ArrayList<>();
        for (Piece piece : pieces) {
            if (piece.isFraction()) {
                Expr micros = fn("date_format", ts.clone(), str("%f"));
                int digits = piece.getFractionDigits();
                parts.add(digits <= 6 ? fn("substr", micros, intLit(1), intLit(digits))
                        : fn("rpad", micros, intLit(digits), str("0")));
            } else {
                parts.add(fn("date_format", ts.clone(), str(piece.getMysql())));
            }
        }
        return fn("concat", parts);
    }

    private static void registerFormatting() {
        // trap 4: Java patterns instead of MySQL formats
        register("date_format", c -> {
            if (c.size() != 2) {
                return null;
            }
            String pattern = constStr(c.arg(1));
            if (pattern == null) {
                return c.unsupported("the pattern of date_format must be a constant");
            }
            try {
                return formatWith(c.arg(0), pattern);
            } catch (UnsupportedPatternException e) {
                return c.unsupported(e.getMessage());
            }
        });
        register("from_unixtime", c -> {
            if (c.size() == 1) {
                return fn("from_unixtime", c.arg(0));
            }
            if (c.size() != 2) {
                return null;
            }
            String pattern = constStr(c.arg(1));
            if (pattern == null) {
                return c.unsupported("the pattern of from_unixtime must be a constant");
            }
            if (DEFAULT_PATTERN.equals(pattern)) {
                return fn("from_unixtime", c.arg(0));
            }
            try {
                return formatWith(cast(DateType.DATETIME, fn("from_unixtime", c.arg(0))), pattern);
            } catch (UnsupportedPatternException e) {
                return c.unsupported(e.getMessage());
            }
        });
        for (String name : new String[] {"unix_timestamp", "to_unix_timestamp"}) {
            register(name, c -> {
                if (c.size() == 2) {
                    String pattern = constStr(c.arg(1));
                    if (pattern == null) {
                        return c.unsupported("the pattern of " + c.name() + " must be a constant");
                    }
                    try {
                        return fn("unix_timestamp", c.arg(0),
                                str(JavaDateTimePatternTranslator.toMysqlParseFormat(pattern)));
                    } catch (UnsupportedPatternException e) {
                        return c.unsupported(e.getMessage());
                    }
                }
                return fn("unix_timestamp", c.args());
            });
        }
    }

    /** The datetime of a string by a Java pattern. */
    private static Expr parseWith(Call c, Expr text, String pattern) {
        try {
            return fn("str_to_date", text, str(JavaDateTimePatternTranslator.toMysqlParseFormat(pattern)));
        } catch (UnsupportedPatternException e) {
            return c.unsupported(e.getMessage());
        }
    }

    private static void registerConversions() {
        register("to_date", c -> {
            if (c.size() == 1) {
                return cast(DateType.DATE, c.arg(0));
            }
            if (c.size() != 2) {
                return null;
            }
            String pattern = constStr(c.arg(1));
            if (pattern == null) {
                return c.unsupported("the pattern of to_date must be a constant");
            }
            try {
                return cast(DateType.DATE, fn("str_to_date", c.arg(0),
                        str(JavaDateTimePatternTranslator.toMysqlParseFormat(pattern))));
            } catch (UnsupportedPatternException e) {
                return c.unsupported(e.getMessage());
            }
        });
        for (String name : new String[] {"to_timestamp", "try_to_timestamp"}) {
            register(name, c -> {
                if (c.size() == 1) {
                    return cast(DateType.DATETIME, c.arg(0));
                }
                if (c.size() != 2) {
                    return null;
                }
                String pattern = constStr(c.arg(1));
                if (pattern == null) {
                    return c.unsupported("the pattern of " + c.name() + " must be a constant");
                }
                return parseWith(c, c.arg(0), pattern);
            });
        }
        for (String name : new String[] {"timestamp", "to_timestamp_ntz", "to_timestamp_ltz"}) {
            register(name, c -> c.size() == 1 ? cast(DateType.DATETIME, c.arg(0)) : null);
        }
        register("date", c -> c.size() == 1 ? cast(DateType.DATE, c.arg(0)) : null);
        register("make_date", c -> {
            if (c.size() != 3) {
                return null;
            }
            Expr text = fn("concat", cast(VarcharType.VARCHAR, c.arg(0)), str("-"),
                    cast(VarcharType.VARCHAR, c.arg(1)), str("-"), cast(VarcharType.VARCHAR, c.arg(2)));
            return cast(DateType.DATE, fn("str_to_date", text, str("%Y-%m-%d")));
        });
    }

    private static void registerArithmetic() {
        // trap 3: date_add/date_sub/add_months return a DATE in Spark and a DATETIME in StarRocks
        register("date_add", c -> c.size() == 2 ? cast(DateType.DATE, fn("days_add", c.arg(0), c.arg(1))) : null);
        register("date_sub", c -> c.size() == 2 ? cast(DateType.DATE, fn("days_sub", c.arg(0), c.arg(1))) : null);
        register("add_months", c -> c.size() == 2
                ? cast(DateType.DATE, fn("add_months", c.arg(0), c.arg(1))) : null);
        for (String name : new String[] {"dateadd", "timestampadd"}) {
            register(name, c -> {
                if (c.size() == 2 && c.name().equals("dateadd")) {
                    return cast(DateType.DATE, fn("days_add", c.arg(0), c.arg(1)));
                }
                if (c.size() != 3) {
                    return null;
                }
                String function = ADD_FUNCTIONS.get(unitOf(c.arg(0)));
                if (function == null) {
                    return c.unsupported("unsupported unit " + unitOf(c.arg(0)));
                }
                return fn(function, c.arg(2), c.arg(1));
            });
        }
        // datediff(end, start) in days; the three argument form datediff(unit, start, end) is timestampdiff
        for (String name : new String[] {"datediff", "date_diff", "timestampdiff"}) {
            register(name, c -> {
                if (c.size() == 2 && !c.name().equals("timestampdiff")) {
                    return fn("datediff", c.arg(0), c.arg(1));
                }
                if (c.size() != 3) {
                    return null;
                }
                String unit = DIFF_UNITS.get(unitOf(c.arg(0)));
                if (unit == null) {
                    return c.unsupported("unsupported unit " + unitOf(c.arg(0)));
                }
                return fn("date_diff", str(unit), c.arg(2), c.arg(1));
            });
        }
        // the day of week is case insensitive in Spark, StarRocks wants Sun/Sunday
        register("next_day", c -> {
            if (c.size() != 2) {
                return null;
            }
            String dow = constStr(c.arg(1));
            if (dow != null) {
                String normalized = DAY_OF_WEEK.get(dow.toLowerCase(Locale.ROOT));
                if (normalized == null) {
                    return cast(DateType.DATE, nul());
                }
                return fn("next_day", c.arg(0), str(normalized));
            }
            return fn("next_day", c.arg(0), fn("initcap", fn("lower", c.arg(1))));
        });
    }

    private static void registerTruncation() {
        // date_trunc(unit, ts): the unit has more spellings in Spark, and a DATE argument is a timestamp
        register("date_trunc", c -> {
            if (c.size() != 2) {
                return null;
            }
            String text = constStr(c.arg(0));
            if (text == null) {
                return c.unsupported("the unit of date_trunc must be a constant");
            }
            String unit = TRUNC_UNITS.get(text.toLowerCase(Locale.ROOT));
            if (unit == null) {
                return cast(DateType.DATETIME, nul());
            }
            return fn("date_trunc", str(unit), cast(DateType.DATETIME, c.arg(1)));
        });
        // trap 17: trunc(date, fmt) has the date first, accepts a few units only, and returns a DATE
        register("trunc", c -> {
            if (c.size() != 2) {
                return null;
            }
            String text = constStr(c.arg(1));
            if (text == null) {
                return c.unsupported("the unit of trunc must be a constant");
            }
            String unit = TRUNC_UNITS.get(text.toLowerCase(Locale.ROOT));
            if (unit == null || !TRUNC_DATE_UNITS.contains(unit)) {
                return cast(DateType.DATE, nul());
            }
            return cast(DateType.DATE, fn("date_trunc", str(unit), cast(DateType.DATETIME, c.arg(0))));
        });
    }

    private static void registerTimeZones() {
        register("from_utc_timestamp", c -> c.size() == 2 ? fn("convert_tz", c.arg(0), str("UTC"), c.arg(1)) : null);
        register("to_utc_timestamp", c -> c.size() == 2 ? fn("convert_tz", c.arg(0), c.arg(1), str("UTC")) : null);
        // convert_timezone([sourceTz,] targetTz, ts)
        register("convert_timezone", c -> {
            if (c.size() == 3) {
                return fn("convert_tz", c.arg(2), c.arg(0), c.arg(1));
            }
            if (c.size() == 2) {
                return fn("convert_tz", c.arg(1), fn("current_timezone"), c.arg(0));
            }
            return null;
        });
    }

    private static void registerEpoch() {
        register("date_from_unix_date", c -> c.size() == 1
                ? cast(DateType.DATE, fn("days_add", cast(DateType.DATE, str("1970-01-01")), c.arg(0))) : null);
        register("unix_date", c -> c.size() == 1
                ? fn("datediff", c.arg(0), cast(DateType.DATE, str("1970-01-01"))) : null);
        register("timestamp_seconds", c -> c.size() == 1
                ? cast(DateType.DATETIME, fn("from_unixtime", c.arg(0))) : null);
        register("unix_seconds", c -> c.size() == 1 ? fn("unix_timestamp", c.arg(0)) : null);
    }
}
