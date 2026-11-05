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

import com.google.common.collect.Lists;
import com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.Call;
import com.starrocks.sql.ast.expression.ArithmeticExpr;
import com.starrocks.sql.ast.expression.ArrayExpr;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.ast.expression.BoolLiteral;
import com.starrocks.sql.ast.expression.CaseExpr;
import com.starrocks.sql.ast.expression.CaseWhenClause;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FloatLiteral;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.FunctionParams;
import com.starrocks.sql.ast.expression.InformationFunction;
import com.starrocks.sql.ast.expression.IsNullPredicate;
import com.starrocks.type.FloatType;

import java.util.ArrayList;
import java.util.List;

import static com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.alias;
import static com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.register;
import static com.starrocks.connector.parser.spark.function.SparkExprs.arith;
import static com.starrocks.connector.parser.spark.function.SparkExprs.bool;
import static com.starrocks.connector.parser.spark.function.SparkExprs.caseWhen;
import static com.starrocks.connector.parser.spark.function.SparkExprs.cast;
import static com.starrocks.connector.parser.spark.function.SparkExprs.cmp;
import static com.starrocks.connector.parser.spark.function.SparkExprs.fn;
import static com.starrocks.connector.parser.spark.function.SparkExprs.iff;
import static com.starrocks.connector.parser.spark.function.SparkExprs.isNotNull;
import static com.starrocks.connector.parser.spark.function.SparkExprs.lit;
import static com.starrocks.connector.parser.spark.function.SparkExprs.not;
import static com.starrocks.connector.parser.spark.function.SparkExprs.nul;
import static com.starrocks.connector.parser.spark.function.SparkExprs.str;

/**
 * Math, conditional, aggregate and miscellaneous functions of the Spark adapter, see
 * {@link Spark2SRFunctionCallTransformer}.
 */
final class SparkMathAggFunctions {
    private SparkMathAggFunctions() {
    }

    static void registerAll() {
        registerAliases();
        registerConditional();
        registerMath();
        registerAggregates();
        registerCollectingAggregates();
        registerRuntimeConcepts();
    }

    private static void registerAliases() {
        // trap 1: Spark stddev/std/variance are the sample versions, StarRocks' are the population versions
        alias("stddev", "stddev_samp");
        alias("std", "stddev_samp");
        alias("variance", "var_samp");
        alias("nvl", "ifnull");
        alias("mean", "avg");
        alias("try_avg", "avg");
        alias("any", "bool_or");
        alias("some", "bool_or");
        alias("approx_percentile", "percentile_approx");
        alias("shiftleft", "bit_shift_left");
        alias("shiftright", "bit_shift_right");
        alias("shiftrightunsigned", "bit_shift_right_logical");
        alias("try_mod", "mod");
        alias("array_size", "array_length");
    }

    private static void registerConditional() {
        // nvl2(a, b, c) -> if(a is not null, b, c)
        register("nvl2", c -> c.size() == 3 ? iff(isNotNull(c.arg(0)), c.arg(1), c.arg(2)) : null);
        register("isnull", c -> c.size() == 1 ? new IsNullPredicate(c.arg(0), false) : null);
        register("isnotnull", c -> c.size() == 1 ? new IsNullPredicate(c.arg(0), true) : null);
        // equal_null(a, b) is the null safe equality
        register("equal_null", c -> c.size() == 2 ? cmp(BinaryType.EQ_FOR_NULL, c.arg(0), c.arg(1)) : null);
        // trap 5: greatest/least skip NULL arguments in Spark, StarRocks returns NULL if any argument is NULL;
        // array_max/array_min skip NULL elements and return NULL only if every element is NULL
        register("greatest", c -> c.size() >= 2 ? fn("array_max", new ArrayExpr(null, c.args())) : null);
        register("least", c -> c.size() >= 2 ? fn("array_min", new ArrayExpr(null, c.args())) : null);
        // decode(expr, search, result, ..., default): the Oracle style decode with a null safe comparison; the
        // two argument decode(binary, charset) has no equivalent
        register("decode", c -> {
            if (c.size() < 3) {
                return c.unsupported("decode(binary, charset) is not supported, only the "
                        + "decode(expr, search, result, ...) form is");
            }
            List<CaseWhenClause> whens = new ArrayList<>();
            int i = 1;
            for (; i + 1 < c.size(); i += 2) {
                whens.add(new CaseWhenClause(
                        cmp(BinaryType.EQ_FOR_NULL, c.copy(0), c.arg(i)), c.arg(i + 1)));
            }
            Expr otherwise = i < c.size() ? c.arg(i) : null;
            return new CaseExpr(null, whens, otherwise);
        });
    }

    private static void registerMath() {
        // the double result of sign is DOUBLE in Spark, StarRocks returns FLOAT
        register("signum", c -> c.size() == 1 ? cast(FloatType.DOUBLE, fn("sign", c.arg(0))) : null);
        register("sign", c -> c.size() == 1 ? cast(FloatType.DOUBLE, fn("sign", c.arg(0))) : null);
        register("try_divide", c -> c.size() == 2 ? arith(ArithmeticExpr.Operator.DIVIDE, c.arg(0), c.arg(1)) : null);
        // trap 16 is the return type of round(double), handled by the analyzer; the two argument ceil/floor
        // (scale) has no equivalent
        register("ceil", c -> c.size() == 2 ? c.unsupported("ceil(expr, scale) is not supported") : null);
        register("ceiling", c -> c.size() == 2 ? c.unsupported("ceiling(expr, scale) is not supported") : null);
        register("floor", c -> c.size() == 2 ? c.unsupported("floor(expr, scale) is not supported") : null);

        register("acosh", c -> c.size() == 1 ? fn("ln", arith(ArithmeticExpr.Operator.ADD, c.arg(0),
                fn("sqrt", arith(ArithmeticExpr.Operator.SUBTRACT,
                        arith(ArithmeticExpr.Operator.MULTIPLY, c.copy(0), c.copy(0)), lit(1))))) : null);
        register("asinh", c -> c.size() == 1 ? fn("ln", arith(ArithmeticExpr.Operator.ADD, c.arg(0),
                fn("sqrt", arith(ArithmeticExpr.Operator.ADD,
                        arith(ArithmeticExpr.Operator.MULTIPLY, c.copy(0), c.copy(0)), lit(1))))) : null);
        register("atanh", c -> c.size() == 1 ? arith(ArithmeticExpr.Operator.MULTIPLY, new FloatLiteral(0.5D),
                fn("ln", arith(ArithmeticExpr.Operator.DIVIDE,
                        arith(ArithmeticExpr.Operator.ADD, lit(1), c.arg(0)),
                        arith(ArithmeticExpr.Operator.SUBTRACT, lit(1), c.copy(0))))) : null);
        register("sec", c -> c.size() == 1 ? arith(ArithmeticExpr.Operator.DIVIDE, lit(1), fn("cos", c.arg(0))) : null);
        register("csc", c -> c.size() == 1 ? arith(ArithmeticExpr.Operator.DIVIDE, lit(1), fn("sin", c.arg(0))) : null);
        register("expm1", c -> c.size() == 1
                ? arith(ArithmeticExpr.Operator.SUBTRACT, fn("exp", c.arg(0)), lit(1)) : null);
        register("log1p", c -> c.size() == 1
                ? fn("ln", arith(ArithmeticExpr.Operator.ADD, lit(1), c.arg(0))) : null);
        register("hypot", c -> c.size() == 2 ? fn("sqrt", arith(ArithmeticExpr.Operator.ADD,
                arith(ArithmeticExpr.Operator.MULTIPLY, c.arg(0), c.copy(0)),
                arith(ArithmeticExpr.Operator.MULTIPLY, c.arg(1), c.copy(1)))) : null);
        // bit_get(x, pos): the bit at the position, counted from the least significant bit
        register("bit_get", c -> c.size() == 2 ? fn("bitand", fn("bit_shift_right", c.arg(0), c.arg(1)), lit(1)) : null);
        register("getbit", c -> c.size() == 2 ? fn("bitand", fn("bit_shift_right", c.arg(0), c.arg(1)), lit(1)) : null);
        // Box-Muller; the result is not reproducible with a seed, so randn(seed) is rejected
        register("randn", c -> {
            if (c.size() != 0) {
                return c.unsupported("randn(seed) is not reproducible in StarRocks");
            }
            return arith(ArithmeticExpr.Operator.MULTIPLY,
                    fn("sqrt", arith(ArithmeticExpr.Operator.MULTIPLY, lit(-2), fn("ln", fn("rand")))),
                    fn("cos", arith(ArithmeticExpr.Operator.MULTIPLY,
                            arith(ArithmeticExpr.Operator.MULTIPLY, lit(2), fn("pi")), fn("rand"))));
        });
        // isnan(NULL) is false; NaN is the only double that differs from itself
        register("isnan", c -> c.size() == 1
                ? fn("ifnull", cmp(BinaryType.NE, c.arg(0), c.copy(0)), bool(false)) : null);
        register("nanvl", c -> c.size() == 2
                ? iff(fn("ifnull", cmp(BinaryType.NE, c.arg(0), c.copy(0)), bool(false)), c.arg(1), c.copy(0)) : null);
    }

    private static void registerAggregates() {
        // count_if(cond), max_by, min_by, bool_or, corr, covar_*, stddev_pop, var_pop: same name, same meaning
        register("approx_count_distinct", c -> {
            // the relative standard deviation of Spark's HyperLogLog++ has no equivalent, it is dropped
            if (c.size() == 2) {
                return fn("approx_count_distinct", c.arg(0));
            }
            return null;
        });
        register("percentile_approx", c -> {
            if (c.size() == 3) {
                // Spark's accuracy and StarRocks' compression are not the same number, use the default
                return fn("percentile_approx", c.arg(0), c.arg(1));
            }
            return null;
        });
        register("percentile", c -> {
            if (c.size() == 2 && !(c.arg(1) instanceof ArrayExpr)) {
                return fn("percentile_cont", c.arg(0), c.arg(1));
            }
            return c.unsupported("percentile with a frequency or an array of percentages is not supported");
        });
        register("median", c -> c.size() == 1 ? fn("percentile_cont", c.arg(0), new FloatLiteral(0.5D)) : null);
        // bool_and / every: NULL when there is no non-null value, else no row is false
        register("bool_and", SparkMathAggFunctions::boolAnd);
        register("every", SparkMathAggFunctions::boolAnd);
        register("regr_count", c -> c.size() == 2 ? fn("count", iff(
                SparkExprs.and(isNotNull(c.arg(0)), isNotNull(c.arg(1))), lit(1), nul())) : null);
        register("regr_avgx", c -> c.size() == 2 ? fn("avg", iff(
                SparkExprs.and(isNotNull(c.arg(0)), isNotNull(c.arg(1))), c.copy(1), nul())) : null);
        register("regr_avgy", c -> c.size() == 2 ? fn("avg", iff(
                SparkExprs.and(isNotNull(c.arg(0)), isNotNull(c.arg(1))), c.copy(0), nul())) : null);

        // first/last/first_value/last_value: an aggregate or a window function, which the call does not say until
        // the analyzer sees the OVER clause. The ignore-nulls argument becomes the flag the window function uses.
        for (String name : new String[] {"first", "last", "first_value", "last_value"}) {
            register(name, c -> {
                if (c.size() == 2) {
                    if (!(c.arg(1) instanceof BoolLiteral)) {
                        return c.unsupported(c.name() + " with a non-constant ignoreNulls argument");
                    }
                    FunctionCallExpr call = SparkExprs.sparkFn(c.name(), Lists.newArrayList(c.arg(0)), c.pos());
                    call.setIgnoreNulls(((BoolLiteral) c.arg(1)).getValue());
                    return call;
                }
                return null;
            });
        }
    }

    private static Expr boolAnd(Call c) {
        if (c.size() != 1) {
            return null;
        }
        Expr noRow = cmp(BinaryType.EQ, fn("count", c.arg(0)), lit(0));
        Expr allTrue = cmp(BinaryType.EQ, fn("count_if", not(c.copy(0))), lit(0));
        return caseWhen(noRow, nul(), allTrue);
    }

    private static void registerCollectingAggregates() {
        // trap 6: collect_list/collect_set drop NULL, array_agg keeps it
        register("collect_list", c -> collect(c, false));
        register("array_agg", c -> collect(c, false));
        register("collect_set", c -> collect(c, true));
        // an aggregate FILTER clause arrives as <agg>_if(args..., condition)
        register("collect_list_if", c -> collectIf(c, false));
        register("collect_set_if", c -> collectIf(c, true));
    }

    private static Expr collect(Call c, boolean distinct) {
        if (c.size() != 1) {
            return c.unsupported(c.name() + " expects exactly one argument");
        }
        boolean distinctValues = distinct || c.isDistinct();
        String name = (distinctValues ? "array_agg_distinct" : "array_agg") + "_if";
        return new FunctionCallExpr(name, new FunctionParams(false, Lists.newArrayList(c.arg(0), isNotNull(c.copy(0)))),
                c.pos());
    }

    private static Expr collectIf(Call c, boolean distinct) {
        if (c.size() != 2) {
            return c.unsupported(c.name() + " expects an argument and a filter");
        }
        String name = (distinct || c.isDistinct() ? "array_agg_distinct" : "array_agg") + "_if";
        Expr condition = SparkExprs.and(c.arg(1), isNotNull(c.copy(0)));
        return new FunctionCallExpr(name, new FunctionParams(false, Lists.newArrayList(c.arg(0), condition)), c.pos());
    }

    private static void registerRuntimeConcepts() {
        // Spark runtime concepts without a StarRocks counterpart: partitions and input files
        register("spark_partition_id", c -> lit(0));
        register("input_file_name", c -> str(""));
        register("input_file_block_start", c -> lit(-1));
        register("input_file_block_length", c -> lit(-1));
        register("current_database", c -> new InformationFunction("DATABASE", c.pos()));
        register("current_schema", c -> new InformationFunction("DATABASE", c.pos()));
        register("current_catalog", c -> new InformationFunction("CATALOG", c.pos()));
    }
}
