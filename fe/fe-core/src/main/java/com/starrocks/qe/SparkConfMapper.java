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

package com.starrocks.qe;

import com.starrocks.common.DdlException;
import com.starrocks.connector.parser.spark.SparkSetStmt;
import com.starrocks.connector.parser.spark.SparkUnsupportedException;
import com.starrocks.sql.ast.SetType;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.expression.StringLiteral;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps the configuration keys of Spark's {@code SET key=value} to StarRocks session variables.
 *
 * <p>A key is handled in one of three ways:
 * <ul>
 *   <li>mapped: it becomes one or more StarRocks variables, e.g. {@code spark.sql.session.timeZone} becomes
 *   {@code time_zone};</li>
 *   <li>absorbed: the statement succeeds, does nothing and leaves a warning, e.g. {@code spark.executor.memory}.
 *   An unknown key is absorbed as well, unless {@code spark_strict_conf} is on;</li>
 *   <li>rejected: a value that changes the semantics and is not implemented, e.g. {@code
 *   spark.sql.caseSensitive=true}. A {@link SparkUnsupportedException} is thrown, so the statement is downgraded
 *   or fails with a clear message.</li>
 * </ul>
 * A key without a dot is a StarRocks variable and passes through. The mapping runs while parsing, it does not read
 * the session. What needs the session (warnings, strict mode, the sql_mode bits of ANSI mode) is carried in the
 * {@link Result} and applied by {@link SparkSetStmt} when the statement executes. The resulting variables are real
 * StarRocks variables only, so the statement is forwarded to the leader FE like any other SET.
 */
public final class SparkConfMapper {
    /** Keys that are known to have no effect on StarRocks. They never fail in strict mode. */
    private static final Pattern ABSORBED = Pattern.compile(
            "^(spark\\.executor\\..*|spark\\.driver\\..*|spark\\.sql\\.adaptive\\..*|spark\\.sql\\.files\\..*"
                    + "|spark\\.sql\\.shuffle\\.partitions|spark\\.sql\\.parquet\\..*|spark\\.sql\\.orc\\..*"
                    + "|spark\\.sql\\.hive\\..*|spark\\.sql\\.catalogimplementation|spark\\.sql\\.sources\\.default"
                    + "|spark\\.sql\\.mapkeydedup.*|spark\\.sql\\.legacy\\.timeparserpolicy|spark\\.default\\..*"
                    + "|spark\\.dynamicallocation\\..*|spark\\.serializer|spark\\.speculation.*|spark\\.memory\\..*"
                    + "|spark\\.hadoop\\..*|spark\\.app\\..*|spark\\.ui\\..*|spark\\.network\\..*"
                    + "|hive\\.exec\\.dynamic\\.partition.*|hive\\.exec\\.max\\.dynamic\\.partitions.*"
                    + "|hive\\.exec\\.compress\\..*|hive\\.optimize\\..*|mapreduce\\..*|mapred\\..*)$");

    private SparkConfMapper() {
    }

    /**
     * The outcome of mapping one key.
     */
    public static final class Result {
        private final List<SystemVariable> variables = new ArrayList<>();
        private final List<SparkSetStmt.Notice> notices = new ArrayList<>();
        private Boolean ansiMode;

        public List<SystemVariable> getVariables() {
            return variables;
        }

        public List<SparkSetStmt.Notice> getNotices() {
            return notices;
        }

        /** Not null when the key switches ANSI mode on or off. */
        public Boolean getAnsiMode() {
            return ansiMode;
        }

        private Result variable(String name, String value) {
            // a null expression is "SET name = DEFAULT"
            variables.add(new SystemVariable(SetType.SESSION, name, value == null ? null : new StringLiteral(value)));
            return this;
        }

        private Result notice(String message, boolean unknownKey) {
            notices.add(new SparkSetStmt.Notice(message, unknownKey));
            return this;
        }
    }

    /**
     * Whether the key is a Spark or Hive style configuration key instead of a StarRocks variable. StarRocks
     * variables never contain a dot.
     */
    public static boolean isConfKey(String key) {
        return key.indexOf('.') >= 0;
    }

    /**
     * The StarRocks variable that holds the value of the configuration key, or null if there is none. For a key
     * that is not a configuration key (a StarRocks variable) it is the key itself.
     */
    public static String variableOf(String key) {
        if (!isConfKey(key)) {
            return key;
        }
        switch (key.toLowerCase(Locale.ROOT)) {
            case "spark.sql.casesensitive":
                return SessionVariable.SPARK_CASE_SENSITIVE;
            case "spark.sql.ansi.enabled":
                return SessionVariable.SPARK_ANSI_ENABLED;
            case "spark.sql.session.timezone":
                return SessionVariable.TIME_ZONE;
            case "spark.sql.legacy.sizeofnull":
                return SessionVariable.SPARK_LEGACY_SIZE_OF_NULL;
            case "spark.sql.sources.partitionoverwritemode":
                return SessionVariable.SPARK_PARTITION_OVERWRITE_MODE;
            case "spark.sql.defaultcatalog":
                return SessionVariable.SPARK_DEFAULT_CATALOG;
            default:
                return null;
        }
    }

    /**
     * Maps {@code SET key=value}.
     *
     * @throws SparkUnsupportedException if the key or its value is not supported
     */
    public static Result set(String key, String rawValue) {
        String value = unquote(rawValue);
        Result result = new Result();
        if (!isConfKey(key)) {
            // a StarRocks session variable
            return result.variable(key, value);
        }
        String lower = key.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "spark.sql.casesensitive":
                if (parseBool(key, value)) {
                    throw new SparkUnsupportedException("Spark SQL syntax is not supported yet: " + key
                            + "=true, StarRocks resolves table and column names case-insensitively");
                }
                return result.variable(SessionVariable.SPARK_CASE_SENSITIVE, "false");
            case "spark.sql.ansi.enabled":
                boolean ansi = parseBool(key, value);
                result.ansiMode = ansi;
                return result.variable(SessionVariable.SPARK_ANSI_ENABLED, String.valueOf(ansi));
            case "spark.sql.session.timezone":
                return result.variable(SessionVariable.TIME_ZONE, value);
            case "spark.sql.legacy.sizeofnull":
                return result.variable(SessionVariable.SPARK_LEGACY_SIZE_OF_NULL,
                        String.valueOf(parseBool(key, value)));
            case "spark.sql.sources.partitionoverwritemode":
                String mode = value.toLowerCase(Locale.ROOT);
                if (!mode.equals("static") && !mode.equals("dynamic")) {
                    throw new SparkUnsupportedException("Spark SQL syntax is not supported yet: invalid value '"
                            + value + "' of " + key + ", expect STATIC or DYNAMIC");
                }
                return result.variable(SessionVariable.SPARK_PARTITION_OVERWRITE_MODE, mode);
            case "spark.sql.defaultcatalog":
                return result.variable(SessionVariable.SPARK_DEFAULT_CATALOG, value);
            case "spark.sql.autobroadcastjointhreshold":
                // Spark counts bytes, StarRocks counts rows: only "disabled" has an exact counterpart
                if (value.trim().startsWith("-")) {
                    return result.variable(SessionVariable.BROADCAST_ROW_LIMIT, "-1");
                }
                return result.notice("Spark configuration '" + key + "' is ignored, StarRocks chooses the join "
                        + "strategy by cost (broadcast_row_limit limits the broadcast table by rows)", false);
            case "spark.sql.parser.escapedstringliterals":
                if (parseBool(key, value)) {
                    throw new SparkUnsupportedException("Spark SQL syntax is not supported yet: " + key + "=true");
                }
                return result.notice("Spark configuration '" + key + "' is ignored", false);
            default:
                break;
        }
        if (ABSORBED.matcher(lower).matches()) {
            return result.notice("Spark configuration '" + key + "' is ignored, it has no effect on StarRocks", false);
        }
        return result.notice("Unknown configuration '" + key + "' is ignored, it has no effect on StarRocks", true);
    }

    /**
     * Maps {@code RESET key}: the variable behind the key goes back to its default.
     */
    public static Result reset(String key) {
        Result result = new Result();
        if (!isConfKey(key)) {
            return result.variable(key, null);
        }
        String variable = variableOf(key);
        if (variable != null) {
            if (SessionVariable.SPARK_ANSI_ENABLED.equals(variable)) {
                result.ansiMode = Boolean.FALSE;
            }
            return result.variable(variable, null);
        }
        return result.notice("Spark configuration '" + key + "' is ignored, it has no effect on StarRocks",
                !ABSORBED.matcher(key.toLowerCase(Locale.ROOT)).matches());
    }

    /**
     * Changes the sql_mode bits that make arithmetic follow ANSI semantics. They are set when ANSI mode is turned on
     * and cleared when it is turned off. The change goes through
     * {@link ConnectContext#modifySystemVariable} so that it is forwarded to the leader like any variable.
     */
    public static void applyAnsiSqlMode(ConnectContext ctx, boolean ansi) throws DdlException {
        long bits = SqlModeHelper.MODE_ERROR_IF_OVERFLOW | SqlModeHelper.MODE_ERROR_FOR_DIVISION_BY_ZERO;
        long current = ctx.getSessionVariable().getSqlMode();
        long updated = ansi ? (current | bits) : (current & ~bits);
        if (updated != current) {
            ctx.modifySystemVariable(new SystemVariable(SetType.SESSION, SessionVariable.SQL_MODE,
                    new StringLiteral(Long.toString(updated))), false);
        }
    }

    private static boolean parseBool(String key, String value) {
        String v = value.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        throw new SparkUnsupportedException("Spark SQL syntax is not supported yet: invalid value '" + value
                + "' of " + key + ", expect true or false");
    }

    /**
     * Removes one pair of surrounding quotes, which Spark keeps in the raw value of SET.
     */
    static String unquote(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '\'' || first == '"' || first == '`') && first == last) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }
}
