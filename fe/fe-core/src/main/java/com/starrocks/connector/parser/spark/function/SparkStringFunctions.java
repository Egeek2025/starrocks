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

import com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.Call;
import com.starrocks.sql.ast.expression.ArithmeticExpr;
import com.starrocks.sql.ast.expression.ArrayExpr;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.ast.expression.CollectionElementExpr;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.MapExpr;
import com.starrocks.type.AnyMapType;
import com.starrocks.type.IntegerType;

import java.util.ArrayList;
import java.util.List;

import static com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.alias;
import static com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.register;
import static com.starrocks.connector.parser.spark.function.SparkExprs.arith;
import static com.starrocks.connector.parser.spark.function.SparkExprs.cmp;
import static com.starrocks.connector.parser.spark.function.SparkExprs.constInt;
import static com.starrocks.connector.parser.spark.function.SparkExprs.constStr;
import static com.starrocks.connector.parser.spark.function.SparkExprs.fn;
import static com.starrocks.connector.parser.spark.function.SparkExprs.iff;
import static com.starrocks.connector.parser.spark.function.SparkExprs.lit;
import static com.starrocks.connector.parser.spark.function.SparkExprs.nul;
import static com.starrocks.connector.parser.spark.function.SparkExprs.str;

/**
 * String, regular expression, JSON and URL functions of the Spark adapter, see
 * {@link Spark2SRFunctionCallTransformer}.
 */
final class SparkStringFunctions {
    private SparkStringFunctions() {
    }

    static void registerAll() {
        registerAliases();
        registerSubstringAndTrim();
        registerMisc();
        registerRegexp();
        registerSplit();
    }

    private static void registerAliases() {
        // trap 13 (length) needs the argument type and is rewritten by the analyzer
        alias("len", "char_length");
        alias("octet_length", "length");
        alias("startswith", "starts_with");
        alias("endswith", "ends_with");
        alias("base64", "to_base64");
        alias("unbase64", "from_base64");
        alias("regexp_like", "regexp");
        alias("rlike", "regexp");
        alias("json_array_length", "json_length");
    }

    /** An integer literal argument of substring-like functions is INT in the backend. */
    private static Expr asInt(Expr e) {
        Long value = constInt(e);
        return e instanceof IntLiteral ? new IntLiteral(value, IntegerType.INT) : e;
    }

    private static void registerSubstringAndTrim() {
        // trap 12: substring(s, 0, n) starts at the first character in Spark, StarRocks returns ''
        for (String name : new String[] {"substring", "substr"}) {
            register(name, c -> {
                if (c.size() != 2 && c.size() != 3) {
                    return null;
                }
                Long pos = constInt(c.arg(1));
                Expr fixedPos;
                if (pos != null) {
                    fixedPos = lit(pos == 0 ? 1 : pos);
                } else {
                    fixedPos = iff(cmp(BinaryType.EQ, c.arg(1), lit(0)), lit(1), c.copy(1));
                }
                List<Expr> args = new ArrayList<>();
                args.add(c.arg(0));
                args.add(asInt(fixedPos));
                if (c.size() == 3) {
                    args.add(asInt(c.arg(2)));
                }
                return fn("substring", args);
            });
        }
        // Spark's two argument trim functions take the characters first: trim(trimStr, str)
        for (String name : new String[] {"trim", "ltrim", "rtrim"}) {
            register(name, c -> c.size() == 2 ? fn(c.name(), c.arg(1), c.arg(0)) : null);
        }
        register("bit_length", c -> c.size() == 1
                ? arith(ArithmeticExpr.Operator.MULTIPLY,
                fn("length", c.arg(0)), lit(8)) : null);
        register("btrim", c -> c.size() == 1 || c.size() == 2 ? fn("trim", c.args()) : null);
        register("replace", c -> c.size() == 2 ? fn("replace", c.arg(0), c.arg(1), str("")) : null);
        register("position", c -> c.size() == 2 || c.size() == 3 ? fn("locate", c.args()) : null);
        register("contains", c -> c.size() == 2
                ? cmp(BinaryType.GT, fn("instr", c.arg(0), c.arg(1)), lit(0)) : null);
        // elt(n, a, b, ...) is the n-th of the remaining arguments, NULL when out of range
        register("elt", c -> {
            if (c.size() < 2) {
                return null;
            }
            List<Expr> items = c.args().subList(1, c.size());
            return new CollectionElementExpr(new ArrayExpr(null, items), c.arg(0), false);
        });
        // chr/char of an ASCII code; other codes are a different byte sequence in StarRocks
        for (String name : new String[] {"chr", "char"}) {
            register(name, c -> {
                Long code = c.size() == 1 ? constInt(c.arg(0)) : null;
                if (code != null && code < 0) {
                    return str("");
                }
                if (code != null && code < 128) {
                    return fn("char", new IntLiteral(code, IntegerType.INT));
                }
                return c.unsupported(c.name() + " is supported for constant codes 0..127 only");
            });
        }
    }

    private static void registerMisc() {
        register("struct", c -> fn("row", c.args()));
        register("map", c -> {
            if (c.size() % 2 != 0) {
                return c.unsupported("map() needs key and value pairs");
            }
            return new MapExpr(AnyMapType.ANY_MAP, c.args(), c.pos());
        });
        // str_to_map(text[, pairDelim[, keyValueDelim]]): the delimiters are regular expressions in Spark
        register("str_to_map", c -> {
            if (c.size() < 1 || c.size() > 3) {
                return null;
            }
            for (int i = 1; i < c.size(); i++) {
                String delimiter = constStr(c.arg(i));
                if (delimiter == null || !SparkRegexSupport.isPlainLiteral(delimiter)) {
                    return c.unsupported("str_to_map delimiters must be constants without regular expression "
                            + "characters");
                }
            }
            Expr pair = c.size() > 1 ? c.arg(1) : str(",");
            Expr keyValue = c.size() > 2 ? c.arg(2) : str(":");
            return fn("str_to_map", c.arg(0), pair, keyValue);
        });
        // parse_url(url, part[, key]): the QUERY part with a key
        register("parse_url", c -> {
            if (c.size() != 3) {
                return null;
            }
            String part = constStr(c.arg(1));
            if (part == null || !part.equalsIgnoreCase("QUERY")) {
                return c.unsupported("parse_url with three arguments is supported for the QUERY part only");
            }
            return fn("url_extract_parameter", c.arg(0), c.arg(2));
        });
    }

    private static void registerRegexp() {
        // trap 11: the group index defaults to 1 in Spark (StarRocks needs it), and the pattern is a Java regex
        for (String name : new String[] {"regexp_extract", "regexp_extract_all"}) {
            register(name, c -> {
                if (c.size() != 2 && c.size() != 3) {
                    return null;
                }
                String rejected = checkPattern(c, 1);
                if (rejected != null) {
                    return c.unsupported(rejected);
                }
                return fn(c.name(), c.arg(0), c.arg(1), c.size() == 3 ? c.arg(2) : lit(1));
            });
        }
        // regexp_substr(str, regexp): the first match, NULL if there is none
        register("regexp_substr", c -> {
            if (c.size() != 2) {
                return null;
            }
            String rejected = checkPattern(c, 1);
            if (rejected != null) {
                return c.unsupported(rejected);
            }
            return iff(fn("regexp", c.arg(0), c.arg(1)),
                    fn("regexp_extract", c.copy(0), c.copy(1), lit(0)), nul());
        });
        // the replacement of Spark has $1 for a group, RE2 has \1
        register("regexp_replace", c -> {
            if (c.size() != 3) {
                return c.size() == 4 ? c.unsupported("regexp_replace with a start position is not supported") : null;
            }
            String rejected = checkPattern(c, 1);
            if (rejected != null) {
                return c.unsupported(rejected);
            }
            String replacement = constStr(c.arg(2));
            if (replacement == null) {
                return c.unsupported("the replacement of regexp_replace must be a constant, Spark and StarRocks "
                        + "write group references differently");
            }
            String translated = SparkRegexSupport.translateReplacement(replacement);
            if (translated == null) {
                return c.unsupported("the replacement '" + replacement + "' of regexp_replace uses a syntax "
                        + "that StarRocks does not have");
            }
            return fn("regexp_replace", c.arg(0), c.arg(1), str(translated));
        });
    }

    /** The reason why the constant pattern at the position cannot run in RE2, or null. */
    private static String checkPattern(Call c, int index) {
        String pattern = constStr(c.arg(index));
        if (pattern == null) {
            return null;
        }
        String feature = SparkRegexSupport.findNonRe2Feature(pattern);
        if (feature == null) {
            return null;
        }
        return "the regular expression '" + pattern + "' uses the " + feature
                + " which is a Java feature that StarRocks (RE2) does not support";
    }

    private static void registerSplit() {
        // trap 10: Spark's split takes a regular expression and a limit, StarRocks' split a literal delimiter
        register("split", c -> {
            if (c.size() != 2 && c.size() != 3) {
                return null;
            }
            String rejected = checkPattern(c, 1);
            if (rejected != null) {
                return c.unsupported(rejected);
            }
            Long limit = null;
            if (c.size() == 3) {
                limit = constInt(c.arg(2));
                if (limit == null) {
                    return c.unsupported("the limit of split must be a constant");
                }
            }
            boolean hasLimit = limit != null && limit > 0;
            String pattern = constStr(c.arg(1));
            if (!hasLimit && pattern != null && SparkRegexSupport.isPlainLiteral(pattern)) {
                return fn("split", c.arg(0), c.arg(1));
            }
            if (hasLimit) {
                return fn("regexp_split", c.arg(0), c.arg(1), new IntLiteral(limit, IntegerType.INT));
            }
            return fn("regexp_split", c.arg(0), c.arg(1));
        });
    }
}
