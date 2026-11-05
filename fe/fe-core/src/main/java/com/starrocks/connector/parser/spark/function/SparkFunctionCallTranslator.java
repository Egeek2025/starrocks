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

import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;

/**
 * Hook that maps a Spark function call, as written by the user, to the StarRocks expression with the same
 * semantics. It is called for every function call at parse time, with the Spark function name in lower case.
 * An analytic call (OVER) is translated first and wrapped afterwards, so the result must stay a
 * {@link FunctionCallExpr} for those.
 *
 * <p>Phase A: returns the call unchanged. The function adapter fills this class in.
 */
public class SparkFunctionCallTranslator {
    public static final SparkFunctionCallTranslator INSTANCE = new SparkFunctionCallTranslator();

    public Expr translate(FunctionCallExpr call) {
        return call;
    }
}
