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
import com.starrocks.sql.ast.expression.BoolLiteral;
import com.starrocks.sql.ast.expression.CollectionElementExpr;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.LambdaFunctionExpr;
import com.starrocks.type.IntegerType;

import static com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.alias;
import static com.starrocks.connector.parser.spark.function.Spark2SRFunctionCallTransformer.register;
import static com.starrocks.connector.parser.spark.function.SparkExprs.arith;
import static com.starrocks.connector.parser.spark.function.SparkExprs.cast;
import static com.starrocks.connector.parser.spark.function.SparkExprs.cmp;
import static com.starrocks.connector.parser.spark.function.SparkExprs.constInt;
import static com.starrocks.connector.parser.spark.function.SparkExprs.fn;
import static com.starrocks.connector.parser.spark.function.SparkExprs.iff;
import static com.starrocks.connector.parser.spark.function.SparkExprs.isNotNull;
import static com.starrocks.connector.parser.spark.function.SparkExprs.isNull;
import static com.starrocks.connector.parser.spark.function.SparkExprs.lambda;
import static com.starrocks.connector.parser.spark.function.SparkExprs.lit;
import static com.starrocks.connector.parser.spark.function.SparkExprs.sparkFn;

/**
 * Array, map and higher-order functions of the Spark adapter, see {@link Spark2SRFunctionCallTransformer}.
 */
final class SparkCollectionFunctions {
    private static final String LAMBDA_ARG = "_spark_v";

    private SparkCollectionFunctions() {
    }

    static void registerAll() {
        registerHigherOrder();
        registerSorting();
        registerArrays();
        registerElementAccess();
    }

    private static boolean isLambda(Expr e, int parameters) {
        // the children of a lambda are the body followed by the parameters
        return e instanceof LambdaFunctionExpr && e.getChildren().size() == parameters + 1;
    }

    private static IntLiteral intLit(long value) {
        return new IntLiteral(value, IntegerType.INT);
    }

    private static void registerHigherOrder() {
        // trap 18: transform/filter/exists take the array first, the StarRocks functions the lambda first
        register("transform", c -> {
            if (c.size() != 2) {
                return null;
            }
            if (isLambda(c.arg(1), 1)) {
                return fn("array_map", c.arg(1), c.arg(0));
            }
            if (isLambda(c.arg(1), 2)) {
                // transform(arr, (x, i) -> ...): i is the 0-based position
                Expr last = cast(IntegerType.INT, arith(ArithmeticExpr.Operator.SUBTRACT,
                        fn("array_length", c.copy(0)), lit(1)));
                return fn("array_map", c.arg(1), c.arg(0), fn("array_generate", intLit(0), last, intLit(1)));
            }
            return c.unsupported("transform needs a lambda with one or two parameters");
        });
        register("filter", c -> {
            if (c.size() != 2) {
                return null;
            }
            if (isLambda(c.arg(1), 1)) {
                return fn("array_filter", c.arg(0), c.arg(1));
            }
            return c.unsupported("filter needs a lambda with one parameter");
        });
        register("exists", c -> c.size() == 2 && isLambda(c.arg(1), 1) ? fn("any_match", c.arg(1), c.arg(0)) : null);
        register("forall", c -> c.size() == 2 && isLambda(c.arg(1), 1) ? fn("all_match", c.arg(1), c.arg(0)) : null);
    }

    /** The elements of the array that are not NULL (or the NULLs). */
    private static Expr nonNulls(Expr array) {
        return fn("array_filter", array, lambda(LAMBDA_ARG, v -> isNotNull(v)));
    }

    private static Expr nulls(Expr array) {
        return fn("array_filter", array, lambda(LAMBDA_ARG, v -> isNull(v)));
    }

    private static void registerSorting() {
        // trap 15: array_sort puts NULL last in Spark, first in StarRocks
        register("array_sort", c -> {
            if (c.size() != 1) {
                return null;
            }
            return fn("array_concat", fn("array_sort", nonNulls(c.arg(0))), nulls(c.copy(0)));
        });
        // sort_array(arr[, asc]): NULL first when ascending, last when descending
        register("sort_array", c -> {
            if (c.size() == 1) {
                return fn("array_sort", c.arg(0));
            }
            if (c.size() != 2) {
                return null;
            }
            if (!(c.arg(1) instanceof BoolLiteral)) {
                return c.unsupported("the sort order of sort_array must be a constant");
            }
            if (((BoolLiteral) c.arg(1)).getValue()) {
                return fn("array_sort", c.arg(0));
            }
            return fn("array_concat", fn("reverse", fn("array_sort", nonNulls(c.arg(0)))), nulls(c.copy(0)));
        });
    }

    private static void registerArrays() {
        alias("flatten", "array_flatten");
        register("array", c -> new ArrayExpr(null, c.args(), c.pos()));
        register("cardinality", c -> sparkFn("size", c.args(), c.pos()));
        register("array_union", c -> c.size() == 2 ? fn("array_distinct", fn("array_concat", c.arg(0), c.arg(1))) : null);
        register("array_compact", c -> c.size() == 1
                ? fn("array_filter", c.arg(0), lambda(LAMBDA_ARG, v -> isNotNull(v))) : null);
        register("array_prepend", c -> c.size() == 2
                ? fn("array_concat", new ArrayExpr(null, SparkExprs.list(c.arg(1))), c.arg(0)) : null);
        register("map_contains_key", c -> c.size() == 2
                ? fn("array_contains", fn("map_keys", c.arg(0)), c.arg(1)) : null);
        // slice(arr, start, length): a negative start counts from the end
        register("slice", c -> {
            if (c.size() != 3) {
                return null;
            }
            Long start = constInt(c.arg(1));
            if (start != null && start > 0) {
                return fn("array_slice", c.arg(0), c.arg(1), c.arg(2));
            }
            if (start != null && start == 0) {
                return c.unsupported("slice starts at 1, a start of 0 is an error in Spark");
            }
            Expr fromEnd = arith(ArithmeticExpr.Operator.ADD,
                    arith(ArithmeticExpr.Operator.ADD, fn("array_length", c.copy(0)), c.copy(1)), lit(1));
            Expr begin = start != null ? fromEnd
                    : iff(cmp(BinaryType.LT, c.copy(1), lit(0)), fromEnd, c.arg(1));
            return fn("array_slice", c.arg(0), begin, c.arg(2));
        });
    }

    private static void registerElementAccess() {
        // trap 8: a negative index counts from the end; the analyzer applies it once it knows the base is an array
        for (String name : new String[] {"element_at", "try_element_at"}) {
            register(name, c -> {
                if (c.size() != 2) {
                    return c.unsupported(c.name() + " expects two arguments");
                }
                CollectionElementExpr element = new CollectionElementExpr(c.arg(0), c.arg(1), false);
                element.setSparkElementAt(true);
                return element;
            });
        }
        // get(arr, index) is the 0-based element access
        register("get", c -> {
            if (c.size() != 2) {
                return null;
            }
            CollectionElementExpr element = new CollectionElementExpr(c.arg(0), c.arg(1), false);
            element.setZeroBasedSubscript(true);
            return element;
        });
    }
}
