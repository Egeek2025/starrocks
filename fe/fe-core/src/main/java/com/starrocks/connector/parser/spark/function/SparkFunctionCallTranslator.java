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

import com.google.common.base.Strings;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;

import java.util.Locale;

/**
 * Hook that maps a Spark function call, as written by the user, to the StarRocks expression with the same
 * semantics. It is called for every function call at parse time, with the Spark function name in lower case.
 * An analytic call (OVER) is translated first and wrapped afterwards, so the result must stay a
 * {@link FunctionCallExpr} for those.
 *
 * <p>The translation has three steps:
 * <ol>
 *   <li>a rule of {@link Spark2SRFunctionCallTransformer} (renames and templates that do not need argument
 *   types);</li>
 *   <li>a function on the {@link SparkFunctionCatalog} list is marked so that the analyzer rejects it with the
 *   reason ({@link SparkFunctionRewriter#UNSUPPORTED_MARKER});</li>
 *   <li>any other call is left as it is and marked as a Spark call, so that the analyzer can still rewrite it by
 *   argument type (size, concat, length, round, first/last, sequence, ...).</li>
 * </ol>
 * A call to a function the adapter does not know (a user defined function) passes unchanged.
 */
public class SparkFunctionCallTranslator {
    public static final SparkFunctionCallTranslator INSTANCE = new SparkFunctionCallTranslator();

    public Expr translate(FunctionCallExpr call) {
        // a function of a database (db.fn) is a user defined function, not a Spark built-in
        if (!Strings.isNullOrEmpty(call.getDbName()) || call.getFunctionName() == null
                || call.getFunctionName().contains(".")) {
            return call;
        }
        String name = call.getFunctionName().toLowerCase(Locale.ROOT);
        Expr translated = Spark2SRFunctionCallTransformer.convert(name, call);
        if (translated != null) {
            return translated;
        }
        String reason = SparkFunctionCatalog.unsupportedReason(name);
        if (reason != null) {
            return SparkExprs.unsupported(name, reason, call.getPos());
        }
        call.setSparkDialect(true);
        return call;
    }
}
