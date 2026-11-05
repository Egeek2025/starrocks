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
import com.starrocks.sql.ast.QualifiedName;
import com.starrocks.sql.ast.expression.ArithmeticExpr;
import com.starrocks.sql.ast.expression.BinaryPredicate;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.ast.expression.BoolLiteral;
import com.starrocks.sql.ast.expression.CaseExpr;
import com.starrocks.sql.ast.expression.CaseWhenClause;
import com.starrocks.sql.ast.expression.CastExpr;
import com.starrocks.sql.ast.expression.CompoundPredicate;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.FunctionParams;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.IsNullPredicate;
import com.starrocks.sql.ast.expression.LambdaArgument;
import com.starrocks.sql.ast.expression.LambdaFunctionExpr;
import com.starrocks.sql.ast.expression.NullLiteral;
import com.starrocks.sql.ast.expression.SlotRef;
import com.starrocks.sql.ast.expression.StringLiteral;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.type.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Small builders for the expression templates of the Spark function adapter.
 *
 * <p>An argument that a template uses more than once must be passed through {@link #copy(Expr)}: the translator
 * runs before the analyzer, and an unanalyzed copy is a fully independent tree.
 *
 * <p>{@link #fn} builds a call with StarRocks semantics, {@link #sparkFn} a call that still has the semantics of
 * the Spark function of that name and is marked for the analyzer rewrite, see {@link SparkFunctionRewriter}.
 */
public final class SparkExprs {
    private SparkExprs() {
    }

    /** A call to a StarRocks function; it is not touched by the Spark rewrite of the analyzer. */
    public static FunctionCallExpr fn(String name, Expr... args) {
        return new FunctionCallExpr(name, Lists.newArrayList(args));
    }

    public static FunctionCallExpr fn(String name, List<Expr> args) {
        return new FunctionCallExpr(name, Lists.newArrayList(args));
    }

    /** A call that keeps the Spark meaning of its name until the analyzer has seen the argument types. */
    public static FunctionCallExpr sparkFn(String name, List<Expr> args, NodePosition pos) {
        FunctionCallExpr call = new FunctionCallExpr(name, Lists.newArrayList(args), pos);
        call.setSparkDialect(true);
        return call;
    }

    public static FunctionCallExpr sparkFn(String name, FunctionParams params, NodePosition pos) {
        FunctionCallExpr call = new FunctionCallExpr(name, params, pos);
        call.setSparkDialect(true);
        return call;
    }

    /** The marker that raises "function not supported" when the analyzer reaches it. */
    public static FunctionCallExpr unsupported(String function, String reason, NodePosition pos) {
        FunctionCallExpr call = new FunctionCallExpr(SparkFunctionRewriter.UNSUPPORTED_MARKER,
                Lists.newArrayList(new StringLiteral(function), new StringLiteral(reason)), pos);
        call.setSparkDialect(true);
        return call;
    }

    public static Expr copy(Expr e) {
        return e.clone();
    }

    public static IntLiteral lit(long value) {
        return new IntLiteral(value);
    }

    public static StringLiteral str(String value) {
        return new StringLiteral(value);
    }

    public static BoolLiteral bool(boolean value) {
        return new BoolLiteral(value);
    }

    public static NullLiteral nul() {
        return new NullLiteral();
    }

    public static CastExpr cast(Type type, Expr e) {
        return new CastExpr(type, e);
    }

    public static IsNullPredicate isNull(Expr e) {
        return new IsNullPredicate(e, false);
    }

    public static IsNullPredicate isNotNull(Expr e) {
        return new IsNullPredicate(e, true);
    }

    public static BinaryPredicate cmp(BinaryType op, Expr a, Expr b) {
        return new BinaryPredicate(op, a, b);
    }

    public static CompoundPredicate and(Expr a, Expr b) {
        return new CompoundPredicate(CompoundPredicate.Operator.AND, a, b);
    }

    public static CompoundPredicate not(Expr a) {
        return new CompoundPredicate(CompoundPredicate.Operator.NOT, a, null);
    }

    public static ArithmeticExpr arith(ArithmeticExpr.Operator op, Expr a, Expr b) {
        return new ArithmeticExpr(op, a, b);
    }

    public static FunctionCallExpr iff(Expr cond, Expr thenExpr, Expr elseExpr) {
        return fn("if", cond, thenExpr, elseExpr);
    }

    public static CaseExpr caseWhen(Expr when, Expr then, Expr otherwise) {
        return new CaseExpr(null, Lists.newArrayList(new CaseWhenClause(when, then)), otherwise);
    }

    /** A lambda {@code v -> body(v)}; the argument name cannot clash with a column of the query. */
    public static LambdaFunctionExpr lambda(String argName, UnaryOperator<Expr> body) {
        Expr arg = new SlotRef(QualifiedName.of(List.of(argName)));
        List<Expr> parts = new ArrayList<>();
        parts.add(body.apply(arg));
        parts.add(new LambdaArgument(argName));
        return new LambdaFunctionExpr(parts);
    }

    public static List<Expr> list(Expr... exprs) {
        return new ArrayList<>(Arrays.asList(exprs));
    }

    /**
     * The value of an integer constant, including the negative constants the Spark parser writes as
     * {@code -1 * k}. Null for anything else.
     */
    public static Long constInt(Expr e) {
        if (e instanceof IntLiteral) {
            return ((IntLiteral) e).getLongValue();
        }
        if (e instanceof ArithmeticExpr && ((ArithmeticExpr) e).getOp() == ArithmeticExpr.Operator.MULTIPLY
                && e.getChildren().size() == 2) {
            Long left = constInt(e.getChild(0));
            Long right = constInt(e.getChild(1));
            if (left != null && right != null) {
                return left * right;
            }
        }
        return null;
    }

    /** The value of a string literal, null for anything else. */
    public static String constStr(Expr e) {
        return e instanceof StringLiteral ? ((StringLiteral) e).getStringValue() : null;
    }
}
