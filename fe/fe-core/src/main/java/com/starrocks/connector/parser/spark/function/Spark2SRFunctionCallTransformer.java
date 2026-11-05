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
import com.starrocks.sql.parser.NodePosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The parse time part of the Spark function adapter: renames (L1) and expression templates (L2a) that do not need
 * to know the type of an argument. Each Spark function name has at most one {@link Rule}. The rules are grouped by
 * kind in {@link SparkMathAggFunctions}, {@link SparkStringFunctions}, {@link SparkDateTimeFunctions} and
 * {@link SparkCollectionFunctions}.
 *
 * <p>A rule returns the replacement expression, or null to leave the call as it is. A call left as it is, and any
 * call a rule builds with {@link SparkExprs#sparkFn}, still has the Spark semantics of its name and is rewritten by
 * the analyzer when it depends on the argument types (L2b, {@link SparkFunctionRewriter}). Calls built with
 * {@link SparkExprs#fn} are StarRocks calls.
 */
public final class Spark2SRFunctionCallTransformer {
    /** A rewrite of the calls of one Spark function. */
    @FunctionalInterface
    public interface Rule {
        Expr apply(Call call);
    }

    /** The call being translated, with the helpers the rules share. */
    public static final class Call {
        private final String name;
        private final FunctionCallExpr raw;
        private final List<Expr> args;

        Call(String name, FunctionCallExpr raw) {
            this.name = name;
            this.raw = raw;
            this.args = raw.getParams().isStar() || raw.getParams().exprs() == null
                    ? Collections.emptyList() : new ArrayList<>(raw.getParams().exprs());
        }

        public String name() {
            return name;
        }

        public FunctionCallExpr raw() {
            return raw;
        }

        public NodePosition pos() {
            return raw.getPos();
        }

        public int size() {
            return args.size();
        }

        public boolean isDistinct() {
            return raw.getParams().isDistinct();
        }

        /** The i-th argument as written. */
        public Expr arg(int i) {
            return args.get(i);
        }

        /** An independent copy of the i-th argument, for a template that uses it more than once. */
        public Expr copy(int i) {
            return args.get(i).clone();
        }

        public List<Expr> args() {
            return new ArrayList<>(args);
        }

        /** The call is left as it is: it keeps its Spark meaning for the analyzer. */
        public Expr same() {
            raw.setSparkDialect(true);
            return raw;
        }

        /** The marker that makes the analyzer reject the call with the reason. */
        public Expr unsupported(String reason) {
            return SparkExprs.unsupported(name, reason, pos());
        }
    }

    private static final Map<String, Rule> RULES = new HashMap<>();

    static {
        SparkMathAggFunctions.registerAll();
        SparkStringFunctions.registerAll();
        SparkDateTimeFunctions.registerAll();
        SparkCollectionFunctions.registerAll();
    }

    private Spark2SRFunctionCallTransformer() {
    }

    static void register(String name, Rule rule) {
        if (RULES.put(name, rule) != null) {
            throw new IllegalStateException("Spark function " + name + " is registered twice");
        }
    }

    /** L1: the function is the StarRocks function of another name, with the same arguments. */
    static void alias(String sparkName, String starRocksName) {
        register(sparkName, call -> rename(call, starRocksName));
    }

    static Expr rename(Call call, String starRocksName) {
        FunctionCallExpr renamed = new FunctionCallExpr(starRocksName, call.raw().getParams(), call.pos());
        renamed.setIgnoreNulls(call.raw().getIgnoreNulls());
        return renamed;
    }

    /**
     * Translates a call; null when no rule is registered for its name.
     *
     * @param name the Spark function name in lower case
     */
    public static Expr convert(String name, FunctionCallExpr call) {
        Rule rule = RULES.get(name);
        if (rule == null) {
            return null;
        }
        return rule.apply(new Call(name, call));
    }

    public static boolean isRegistered(String name) {
        return RULES.containsKey(name);
    }

    /** The names with a rule, sorted. */
    public static Set<String> registeredNames() {
        return Collections.unmodifiableSet(new TreeSet<>(RULES.keySet()));
    }
}
