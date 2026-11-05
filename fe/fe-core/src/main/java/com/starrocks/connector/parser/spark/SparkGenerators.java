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

package com.starrocks.connector.parser.spark;

import com.google.common.collect.Lists;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.ast.TableFunctionRelation;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.type.IntegerType;
import com.starrocks.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Analysis time expansion of the Spark generators EXPLODE and POSEXPLODE. The Spark parser can only emit
 * {@code unnest(arg)} because StarRocks {@code unnest} takes arrays only, while Spark accepts maps too:
 * <ul>
 *   <li>{@code explode(array)}: {@code unnest(array)}, columns (col)</li>
 *   <li>{@code explode(map)}: {@code unnest(map_keys(map), map_values(map))}, columns (key, value)</li>
 *   <li>{@code posexplode(array)}: {@code unnest(positions, array)}, columns (pos, col)</li>
 *   <li>{@code posexplode(map)}: {@code unnest(positions, map_keys(map), map_values(map))}, columns
 *   (pos, key, value)</li>
 * </ul>
 * The positions are 0-based, as in Spark: {@code array_slice(array_generate(0, n, 1), 1, n)} where n is the number
 * of elements; the slice makes an empty input produce no position instead of the position 0.
 */
public final class SparkGenerators {
    private SparkGenerators() {
    }

    /**
     * Rewrites the arguments of the table function. {@code typeOf} analyzes a copy of the expression and returns
     * its type. The marker of the relation is cleared so that the rewrite happens once.
     */
    public static void rewrite(TableFunctionRelation node, Function<Expr, Type> typeOf) {
        TableFunctionRelation.SparkGenerator kind = node.getSparkGenerator();
        node.setSparkGenerator(null);
        List<Expr> params = node.getFunctionParams().exprs();
        if (kind == null || params.size() != 1) {
            return;
        }
        Expr argument = params.get(0);
        Type type = typeOf.apply(argument);

        boolean isMap = type != null && type.isMapType();
        List<Expr> args = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (kind == TableFunctionRelation.SparkGenerator.POSEXPLODE) {
            args.add(positions(isMap ? call(FunctionSet.MAP_SIZE, argument.clone()) : call(FunctionSet.ARRAY_LENGTH,
                    argument.clone())));
            names.add("pos");
        }
        if (isMap) {
            args.add(call(FunctionSet.MAP_KEYS, argument));
            args.add(call(FunctionSet.MAP_VALUES, argument.clone()));
            names.add("key");
            names.add("value");
        } else {
            args.add(argument);
            names.add("col");
        }
        node.getFunctionParams().setExprs(args);
        if (node.getColumnOutputNames() == null) {
            node.setColumnOutputNames(names);
        }
    }

    private static Expr call(String name, Expr... args) {
        return new FunctionCallExpr(name, Lists.newArrayList(args));
    }

    /**
     * The array [0, 1, ..., size - 1] of INT.
     */
    private static Expr positions(Expr size) {
        Expr all = call(FunctionSet.ARRAY_GENERATE, new IntLiteral(0, IntegerType.INT), size,
                new IntLiteral(1, IntegerType.INT));
        return call(FunctionSet.ARRAY_SLICE, all, new IntLiteral(1, IntegerType.INT), size.clone());
    }
}
