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
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.expression.ArithmeticExpr;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.ast.expression.CollectionElementExpr;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.StringLiteral;
import com.starrocks.type.IntegerType;
import com.starrocks.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.starrocks.connector.parser.spark.function.SparkExprs.cast;
import static com.starrocks.connector.parser.spark.function.SparkExprs.cmp;
import static com.starrocks.connector.parser.spark.function.SparkExprs.constInt;
import static com.starrocks.connector.parser.spark.function.SparkExprs.fn;
import static com.starrocks.connector.parser.spark.function.SparkExprs.iff;
import static com.starrocks.connector.parser.spark.function.SparkExprs.lit;

/**
 * The analysis time part of the Spark function adapter (L2b): rewrites that depend on the type of the arguments,
 * for the calls that the parse time adapter marked as Spark calls ({@link FunctionCallExpr#isSparkDialect()}).
 *
 * <p>The expression analyzer works bottom-up and rewrites in place, so a rewrite keeps the node: it renames the call
 * and replaces its arguments. A rewrite that needs a different root node, such as a CAST around the call, is done
 * at parse time instead, see {@link Spark2SRFunctionCallTransformer}.
 *
 * <p>The calls that cannot be supported at all are marked by the parse time adapter with
 * {@link #UNSUPPORTED_MARKER}; analyzing such a call raises the error here, where the statement is rejected before
 * anything can run with different semantics.
 */
public final class SparkFunctionRewriter {
    /** The name of the call that stands for an unsupported Spark function, see {@link SparkExprs#unsupported}. */
    public static final String UNSUPPORTED_MARKER = "spark_unsupported_function";

    private SparkFunctionRewriter() {
    }

    /** What the rewriter needs from the expression analyzer. */
    public interface Context {
        ConnectContext session();

        /** Analyzes one new node whose children are analyzed. */
        void analyze(Expr node);

        /** An independent copy of an analyzed expression, analyzed again: the expression is used a second time. */
        Expr copyOf(Expr analyzed);
    }

    /**
     * Rewrites the call in place if it needs it.
     *
     * @return true if the node was changed, and has to be analyzed again
     */
    public static boolean rewriteInPlace(FunctionCallExpr node, Context ctx) {
        String name = node.getFunctionName().toLowerCase(Locale.ROOT);
        List<Expr> args = node.getChildren();
        switch (name) {
            case UNSUPPORTED_MARKER:
                throw unsupported(node);
            case "size":
                return rewriteSize(node, args, ctx);
            case "concat":
                return rewriteConcat(node, args);
            case "concat_ws":
                return rewriteConcatWs(node, args);
            case "length":
                return rewriteLength(node, args);
            case "round":
                return rewriteRound(node, args);
            case "first":
            case "last":
            case "first_value":
            case "last_value":
                return rewriteFirstLast(node, name);
            case "sequence":
                return rewriteSequence(node, args, ctx);
            default:
                return false;
        }
    }

    private static SemanticException unsupported(FunctionCallExpr node) {
        String function = ((StringLiteral) node.getChild(0)).getStringValue();
        String reason = ((StringLiteral) node.getChild(1)).getStringValue();
        return new SemanticException("Function %s is not supported in the Spark dialect: %s", function, reason);
    }

    /** Replaces the name and the arguments of the call; the call stays the same node. */
    private static boolean replace(FunctionCallExpr node, String name, List<Expr> args) {
        node.resetFnName("", name);
        node.setFn(null);
        List<Expr> newArgs = new ArrayList<>(args);
        node.clearChildren();
        node.addChildren(newArgs);
        // the parameters share the list of children, as they do after clone()
        node.getParams().setExprs(node.getChildren());
        return true;
    }

    // trap 9: size(NULL) is -1 in Spark (spark.sql.legacy.sizeOfNull), NULL in StarRocks; size works on maps too
    private static boolean rewriteSize(FunctionCallExpr node, List<Expr> args, Context ctx) {
        if (args.size() != 1) {
            return false;
        }
        Type type = args.get(0).getType();
        boolean legacy = ctx.session() == null || ctx.session().getSessionVariable().isSparkLegacySizeOfNull();
        if (type.isNull()) {
            if (legacy) {
                return replace(node, "ifnull", Lists.newArrayList(args.get(0), lit(-1)));
            }
            throw new SemanticException("size() needs an array or a map, got NULL");
        }
        if (!type.isArrayType() && !type.isMapType()) {
            throw new SemanticException("size() needs an array or a map, got %s", type.toSql());
        }
        String length = type.isMapType() ? "map_size" : "array_length";
        if (!legacy) {
            return replace(node, length, Lists.newArrayList(args.get(0)));
        }
        FunctionCallExpr lengthCall = fn(length, args.get(0));
        ctx.analyze(lengthCall);
        return replace(node, "ifnull", Lists.newArrayList(lengthCall, lit(-1)));
    }

    // concatenation of arrays: concat for strings, array_concat for arrays
    private static boolean rewriteConcat(FunctionCallExpr node, List<Expr> args) {
        boolean anyArray = false;
        for (Expr arg : args) {
            if (arg.getType().isArrayType()) {
                anyArray = true;
            }
        }
        if (!anyArray) {
            return false;
        }
        for (Expr arg : args) {
            if (!arg.getType().isArrayType() && !arg.getType().isNull()) {
                throw new SemanticException("concat cannot mix an array with %s", arg.getType().toSql());
            }
        }
        return replace(node, "array_concat", args);
    }

    // concat_ws(sep, array) joins the elements and skips NULL
    private static boolean rewriteConcatWs(FunctionCallExpr node, List<Expr> args) {
        boolean anyArray = false;
        for (int i = 1; i < args.size(); i++) {
            if (args.get(i).getType().isArrayType()) {
                anyArray = true;
            }
        }
        if (!anyArray) {
            return false;
        }
        if (args.size() != 2) {
            throw new SemanticException("concat_ws with an array is supported for concat_ws(sep, array) only");
        }
        return replace(node, "array_join", Lists.newArrayList(args.get(1), args.get(0)));
    }

    // trap 13: length counts characters in Spark, bytes in StarRocks (the bytes of a binary are the same in both)
    private static boolean rewriteLength(FunctionCallExpr node, List<Expr> args) {
        if (args.size() != 1 || args.get(0).getType().isBinaryType()) {
            return false;
        }
        return replace(node, "char_length", args);
    }

    // trap 16: round(DOUBLE) is a DOUBLE in Spark, a BIGINT in StarRocks; the two argument form is a DOUBLE
    private static boolean rewriteRound(FunctionCallExpr node, List<Expr> args) {
        if (args.size() != 1 || !args.get(0).getType().isFloatingPointType()) {
            return false;
        }
        return replace(node, "round", Lists.newArrayList(args.get(0), lit(0)));
    }

    // first/last are aggregates or window functions; StarRocks has any_value and first_value/last_value
    private static boolean rewriteFirstLast(FunctionCallExpr node, String name) {
        boolean first = name.startsWith("first");
        if (node.isAnalyticFnCall()) {
            String windowName = first ? "first_value" : "last_value";
            if (name.equals(windowName)) {
                return false;
            }
            return replace(node, windowName, node.getChildren());
        }
        if (node.getIgnoreNulls()) {
            throw new SemanticException("%s(..., ignoreNulls = true) is supported as a window function only", name);
        }
        return replace(node, "any_value", node.getChildren());
    }

    // sequence(start, stop[, step]) of integers; the default step is -1 when start > stop
    private static boolean rewriteSequence(FunctionCallExpr node, List<Expr> args, Context ctx) {
        if (args.size() != 2 && args.size() != 3) {
            return false;
        }
        for (Expr arg : args) {
            if (!arg.getType().isIntegerType()) {
                throw new SemanticException("sequence is supported for integer arguments only, got %s",
                        arg.getType().toSql());
            }
        }
        List<Expr> newArgs = new ArrayList<>(args);
        if (args.size() == 2) {
            Expr ascending = cmp(BinaryType.LE, ctx.copyOf(args.get(0)), ctx.copyOf(args.get(1)));
            ctx.analyze(ascending);
            Expr step = iff(ascending, lit(1), lit(-1));
            ctx.analyze(step);
            newArgs.add(step);
        }
        List<Expr> converted = new ArrayList<>();
        for (Expr arg : newArgs) {
            // the backend generates INT sequences from the small integer types
            if (arg.getType().equals(IntegerType.TINYINT) || arg.getType().equals(IntegerType.SMALLINT)) {
                Expr cast = cast(IntegerType.INT, arg);
                ctx.analyze(cast);
                converted.add(cast);
            } else {
                converted.add(arg);
            }
        }
        return replace(node, "array_generate", converted);
    }

    /**
     * The element access {@code element_at(array, index)} of Spark: the index is 1-based, a negative index counts from
     * the end, and index 0 is an error. The base is known to be an array.
     */
    public static void rewriteElementAt(CollectionElementExpr node, Context ctx) {
        Expr array = node.getChild(0);
        Expr index = node.getChild(1);
        Long constant = constInt(index);
        if (constant != null) {
            if (constant == 0) {
                throw new SemanticException("element_at: the index 0 is invalid, SQL array indices start at 1");
            }
            if (constant < 0) {
                // the k-th element from the end is the k-th element of the reversed array
                FunctionCallExpr reversed = fn("reverse", array);
                ctx.analyze(reversed);
                node.setChild(0, reversed);
                node.setChild(1, new IntLiteral(-constant));
            }
            return;
        }
        // index < 0 ? array_length(array) + index + 1 : index
        Expr negative = cmp(BinaryType.LT, ctx.copyOf(index), lit(0));
        ctx.analyze(negative);
        FunctionCallExpr length = fn("array_length", ctx.copyOf(array));
        ctx.analyze(length);
        Expr sum = new ArithmeticExpr(ArithmeticExpr.Operator.ADD, length, ctx.copyOf(index));
        ctx.analyze(sum);
        Expr fromEnd = new ArithmeticExpr(ArithmeticExpr.Operator.ADD, sum, lit(1));
        ctx.analyze(fromEnd);
        Expr position = iff(negative, fromEnd, index);
        ctx.analyze(position);
        node.setChild(1, position);
    }
}
