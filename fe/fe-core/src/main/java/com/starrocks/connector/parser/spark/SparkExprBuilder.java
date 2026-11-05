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
import com.starrocks.common.util.DateUtils;
import com.starrocks.connector.parser.spark.function.SparkFunctionCallTranslator;
import com.starrocks.sql.ast.OrderByElement;
import com.starrocks.sql.ast.ParseNode;
import com.starrocks.sql.ast.QualifiedName;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.UnitIdentifier;
import com.starrocks.sql.ast.expression.AnalyticExpr;
import com.starrocks.sql.ast.expression.AnalyticWindow;
import com.starrocks.sql.ast.expression.AnalyticWindowBoundary;
import com.starrocks.sql.ast.expression.ArithmeticExpr;
import com.starrocks.sql.ast.expression.ArrayExpr;
import com.starrocks.sql.ast.expression.BetweenPredicate;
import com.starrocks.sql.ast.expression.BinaryPredicate;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.ast.expression.BoolLiteral;
import com.starrocks.sql.ast.expression.CaseExpr;
import com.starrocks.sql.ast.expression.CaseWhenClause;
import com.starrocks.sql.ast.expression.CastExpr;
import com.starrocks.sql.ast.expression.CollectionElementExpr;
import com.starrocks.sql.ast.expression.CompoundPredicate;
import com.starrocks.sql.ast.expression.DateLiteral;
import com.starrocks.sql.ast.expression.DecimalLiteral;
import com.starrocks.sql.ast.expression.ExistsPredicate;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FloatLiteral;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.FunctionParams;
import com.starrocks.sql.ast.expression.GroupingFunctionCallExpr;
import com.starrocks.sql.ast.expression.InPredicate;
import com.starrocks.sql.ast.expression.InformationFunction;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.sql.ast.expression.IntervalLiteral;
import com.starrocks.sql.ast.expression.IsNullPredicate;
import com.starrocks.sql.ast.expression.LambdaArgument;
import com.starrocks.sql.ast.expression.LambdaFunctionExpr;
import com.starrocks.sql.ast.expression.LargeIntLiteral;
import com.starrocks.sql.ast.expression.LikePredicate;
import com.starrocks.sql.ast.expression.MapExpr;
import com.starrocks.sql.ast.expression.NamedArgument;
import com.starrocks.sql.ast.expression.NullLiteral;
import com.starrocks.sql.ast.expression.SlotRef;
import com.starrocks.sql.ast.expression.StringLiteral;
import com.starrocks.sql.ast.expression.SubfieldExpr;
import com.starrocks.sql.ast.expression.Subquery;
import com.starrocks.sql.ast.expression.TimestampArithmeticExpr;
import com.starrocks.sql.ast.expression.TypeDef;
import com.starrocks.sql.ast.expression.VarBinaryLiteral;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseLexer;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParserBaseVisitor;
import com.starrocks.type.AnyMapType;
import com.starrocks.type.ArrayType;
import com.starrocks.type.BooleanType;
import com.starrocks.type.DateType;
import com.starrocks.type.FloatType;
import com.starrocks.type.IntegerType;
import com.starrocks.type.MapType;
import com.starrocks.type.NullType;
import com.starrocks.type.StringType;
import com.starrocks.type.StructField;
import com.starrocks.type.StructType;
import com.starrocks.type.Type;
import com.starrocks.type.TypeFactory;
import com.starrocks.type.VarbinaryType;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.starrocks.connector.parser.spark.SparkAstBuilder.unsupported;
import static com.starrocks.connector.parser.spark.SparkParserUtils.pos;

/**
 * Builds expressions: column references, literals, operators, predicates, CASE, CAST with Spark type names,
 * function calls (including DISTINCT aggregates, FILTER and window functions), subquery expressions and EXISTS.
 *
 * <p>Every function call goes through {@link SparkFunctionCallTranslator}, except the constructors array(...) and
 * map(...), which are the StarRocks array and map expressions, and struct(...), which is named_struct with the Spark
 * field names. Window references ({@code OVER w}) are resolved against the WINDOW clause of the enclosing query.
 * INTERVAL literals exist only as an operand of {@code +} or {@code -} next to a date or timestamp, and become a
 * chain of {@link TimestampArithmeticExpr}, one per unit. TRY_CAST is a CAST: StarRocks returns NULL when a cast
 * fails.
 *
 * <p>Not covered yet (they throw {@link SparkUnsupportedException}): other uses of INTERVAL, first/last/any_value/
 * overlay/percentile/timestampadd/timestampdiff forms, parameters markers.
 */
public class SparkExprBuilder extends SparkSqlBaseParserBaseVisitor<ParseNode> {
    private static final Set<String> EXTRACT_FIELDS = Set.of("year", "quarter", "month", "week", "day", "dayofmonth",
            "dayofweek", "dayofyear", "hour", "minute", "second");

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LARGEINT_MIN = BigInteger.ONE.shiftLeft(127).negate();
    private static final BigInteger LARGEINT_MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    private static final BigInteger INT256_MAX = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE);

    private final SparkAstBuilder root;
    private final SparkFunctionCallTranslator functionTranslator;

    public SparkExprBuilder(SparkAstBuilder root) {
        this(root, SparkFunctionCallTranslator.INSTANCE);
    }

    public SparkExprBuilder(SparkAstBuilder root, SparkFunctionCallTranslator functionTranslator) {
        this.root = root;
        this.functionTranslator = functionTranslator;
    }

    private Expr expr(ParseTree tree) {
        return root.visit(tree, Expr.class);
    }

    private List<Expr> exprs(List<? extends ParseTree> trees) {
        List<Expr> result = new ArrayList<>(trees.size());
        for (ParseTree tree : trees) {
            result.add(expr(tree));
        }
        return result;
    }

    // ---------------------------------------- boolean expressions ----------------------------------------------

    @Override
    public ParseNode visitLogicalNot(SparkSqlBaseParser.LogicalNotContext ctx) {
        return new CompoundPredicate(CompoundPredicate.Operator.NOT, expr(ctx.booleanExpression()), null, pos(ctx));
    }

    @Override
    public ParseNode visitExists(SparkSqlBaseParser.ExistsContext ctx) {
        QueryRelation query = root.visit(ctx.query(), QueryRelation.class);
        return new ExistsPredicate(new Subquery(new QueryStatement(query)), false, pos(ctx));
    }

    @Override
    public ParseNode visitLogicalBinary(SparkSqlBaseParser.LogicalBinaryContext ctx) {
        CompoundPredicate.Operator op = ctx.operator.getType() == SparkSqlBaseLexer.AND
                ? CompoundPredicate.Operator.AND : CompoundPredicate.Operator.OR;
        return new CompoundPredicate(op, expr(ctx.left), expr(ctx.right), pos(ctx));
    }

    @Override
    public ParseNode visitPredicated(SparkSqlBaseParser.PredicatedContext ctx) {
        Expr value = expr(ctx.valueExpression());
        SparkSqlBaseParser.PredicateContext predicate = ctx.predicate();
        if (predicate == null) {
            return value;
        }
        NodePosition pos = pos(ctx);
        boolean not = predicate.NOT() != null;
        switch (predicate.kind.getType()) {
            case SparkSqlBaseLexer.BETWEEN:
                return new BetweenPredicate(value, expr(predicate.lower), expr(predicate.upper), not, pos);
            case SparkSqlBaseLexer.IN:
                if (predicate.query() != null) {
                    QueryRelation query = root.visit(predicate.query(), QueryRelation.class);
                    return new InPredicate(value, new Subquery(new QueryStatement(query)), not, pos);
                }
                return new InPredicate(value, exprs(predicate.expression()), not, pos);
            case SparkSqlBaseLexer.RLIKE:
                return negateIf(not, new LikePredicate(LikePredicate.Operator.REGEXP, value,
                        expr(predicate.pattern), pos), pos);
            case SparkSqlBaseLexer.LIKE:
            case SparkSqlBaseLexer.ILIKE:
                return buildLike(predicate, value, not, pos);
            case SparkSqlBaseLexer.NULL:
                return new IsNullPredicate(value, not, pos);
            case SparkSqlBaseLexer.TRUE:
                return negateIf(not, new BinaryPredicate(BinaryType.EQ_FOR_NULL, value, new BoolLiteral(true), pos),
                        pos);
            case SparkSqlBaseLexer.FALSE:
                return negateIf(not, new BinaryPredicate(BinaryType.EQ_FOR_NULL, value, new BoolLiteral(false), pos),
                        pos);
            case SparkSqlBaseLexer.UNKNOWN:
                return new IsNullPredicate(value, not, pos);
            case SparkSqlBaseLexer.DISTINCT:
                // IS [NOT] DISTINCT FROM is the negation of the null safe equality
                return negateIf(!not, new BinaryPredicate(BinaryType.EQ_FOR_NULL, value, expr(predicate.right), pos),
                        pos);
            default:
                throw unsupported(predicate);
        }
    }

    private Expr buildLike(SparkSqlBaseParser.PredicateContext predicate, Expr value, boolean not, NodePosition pos) {
        boolean ilike = predicate.kind.getType() == SparkSqlBaseLexer.ILIKE;
        if (predicate.quantifier != null) {
            // LIKE ANY (p1, p2) is (value LIKE p1 OR value LIKE p2), LIKE ALL is the conjunction
            if (predicate.expression().isEmpty()) {
                throw unsupported("LIKE ANY/SOME/ALL without patterns");
            }
            boolean all = predicate.quantifier.getType() == SparkSqlBaseLexer.ALL;
            Expr combined = null;
            for (SparkSqlBaseParser.ExpressionContext patternCtx : predicate.expression()) {
                Expr like = like(combined == null ? value : value.clone(), expr(patternCtx), ilike, pos);
                combined = combined == null ? like : new CompoundPredicate(
                        all ? CompoundPredicate.Operator.AND : CompoundPredicate.Operator.OR, combined, like, pos);
            }
            return negateIf(not, combined, pos);
        }
        Expr pattern = expr(predicate.pattern);
        if (predicate.escapeChar != null) {
            pattern = escapePattern(pattern, SparkParserUtils.stringLit(predicate.escapeChar), predicate);
        }
        return negateIf(not, like(value, pattern, ilike, pos), pos);
    }

    private static Expr like(Expr value, Expr pattern, boolean ilike, NodePosition pos) {
        if (ilike) {
            // ilike(a, b) is like(lower(a), lower(b))
            return new FunctionCallExpr(FunctionSet.LIKE, Lists.newArrayList(
                    new FunctionCallExpr(FunctionSet.LOWER, Lists.newArrayList(value)),
                    new FunctionCallExpr(FunctionSet.LOWER, Lists.newArrayList(pattern))), pos);
        }
        return new LikePredicate(LikePredicate.Operator.LIKE, value, pattern, pos);
    }

    /**
     * LIKE ... ESCAPE 'c': StarRocks only knows the backslash as escape character, so a literal pattern is
     * rewritten: "c%", "c_" and "cc" become "\%", "\_" and "c", and a literal backslash is doubled.
     */
    private static Expr escapePattern(Expr pattern, String escape, ParserRuleContext ctx) {
        if (escape.length() != 1) {
            throw new ParsingException("Invalid escape string: the escape string must have only one character",
                    pos(ctx));
        }
        if (!(pattern instanceof StringLiteral)) {
            throw unsupported("LIKE ... ESCAPE with a pattern that is not a string literal");
        }
        char escapeChar = escape.charAt(0);
        if (escapeChar == '\\') {
            return pattern;
        }
        String text = ((StringLiteral) pattern).getValue();
        StringBuilder rewritten = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == escapeChar && i + 1 < text.length()) {
                char next = text.charAt(++i);
                if (next == '%' || next == '_') {
                    rewritten.append('\\').append(next);
                } else if (next == '\\') {
                    rewritten.append("\\\\");
                } else {
                    rewritten.append(next);
                }
            } else if (c == '\\') {
                rewritten.append("\\\\");
            } else {
                rewritten.append(c);
            }
        }
        return new StringLiteral(rewritten.toString(), pattern.getPos());
    }

    private static Expr negateIf(boolean negate, Expr expr, NodePosition pos) {
        return negate ? new CompoundPredicate(CompoundPredicate.Operator.NOT, expr, null, pos) : expr;
    }

    // ---------------------------------------- value expressions ------------------------------------------------

    @Override
    public ParseNode visitArithmeticUnary(SparkSqlBaseParser.ArithmeticUnaryContext ctx) {
        Expr child = expr(ctx.valueExpression());
        NodePosition pos = pos(ctx);
        switch (ctx.operator.getType()) {
            case SparkSqlBaseLexer.MINUS:
                return new ArithmeticExpr(ArithmeticExpr.Operator.MULTIPLY, new IntLiteral(-1), child, pos);
            case SparkSqlBaseLexer.PLUS:
                return child;
            default:
                return new ArithmeticExpr(ArithmeticExpr.Operator.BITNOT, child, null, pos);
        }
    }

    @Override
    public ParseNode visitArithmeticBinary(SparkSqlBaseParser.ArithmeticBinaryContext ctx) {
        Expr left = expr(ctx.left);
        Expr right = expr(ctx.right);
        NodePosition pos = pos(ctx);
        if (left instanceof SparkInterval || right instanceof SparkInterval) {
            return buildIntervalArithmetic(ctx, left, right, pos);
        }
        ArithmeticExpr.Operator op;
        switch (ctx.operator.getType()) {
            case SparkSqlBaseLexer.ASTERISK:
                op = ArithmeticExpr.Operator.MULTIPLY;
                break;
            case SparkSqlBaseLexer.SLASH:
                op = ArithmeticExpr.Operator.DIVIDE;
                break;
            case SparkSqlBaseLexer.PERCENT:
                op = ArithmeticExpr.Operator.MOD;
                break;
            case SparkSqlBaseLexer.DIV:
                op = ArithmeticExpr.Operator.INT_DIVIDE;
                break;
            case SparkSqlBaseLexer.PLUS:
                op = ArithmeticExpr.Operator.ADD;
                break;
            case SparkSqlBaseLexer.MINUS:
                op = ArithmeticExpr.Operator.SUBTRACT;
                break;
            case SparkSqlBaseLexer.AMPERSAND:
                op = ArithmeticExpr.Operator.BITAND;
                break;
            case SparkSqlBaseLexer.HAT:
                op = ArithmeticExpr.Operator.BITXOR;
                break;
            case SparkSqlBaseLexer.PIPE:
                op = ArithmeticExpr.Operator.BITOR;
                break;
            case SparkSqlBaseLexer.CONCAT_PIPE:
                // string concatenation; array concatenation by || is not translated yet
                return functionTranslator.translate(new FunctionCallExpr("concat",
                        Lists.newArrayList(left, right), pos));
            default:
                throw unsupported(ctx);
        }
        return new ArithmeticExpr(op, left, right, pos);
    }

    @Override
    public ParseNode visitComparison(SparkSqlBaseParser.ComparisonContext ctx) {
        BinaryType op;
        switch (ctx.comparisonOperator().getStart().getType()) {
            case SparkSqlBaseLexer.EQ:
                op = BinaryType.EQ;
                break;
            case SparkSqlBaseLexer.NEQ:
            case SparkSqlBaseLexer.NEQJ:
                op = BinaryType.NE;
                break;
            case SparkSqlBaseLexer.LT:
                op = BinaryType.LT;
                break;
            case SparkSqlBaseLexer.LTE:
                op = BinaryType.LE;
                break;
            case SparkSqlBaseLexer.GT:
                op = BinaryType.GT;
                break;
            case SparkSqlBaseLexer.GTE:
                op = BinaryType.GE;
                break;
            default:
                op = BinaryType.EQ_FOR_NULL;
                break;
        }
        return new BinaryPredicate(op, expr(ctx.left), expr(ctx.right), pos(ctx));
    }

    // ---------------------------------------------- intervals --------------------------------------------------

    /**
     * One unit of an interval: the StarRocks time unit and the amount, which can be negative.
     */
    private static final class IntervalPart {
        private final String unit;
        private final long amount;

        private IntervalPart(String unit, long amount) {
            this.unit = unit;
            this.amount = amount;
        }
    }

    /**
     * A Spark interval literal, like INTERVAL 1 DAY 2 HOURS. It is only built to be consumed by the
     * {@code +}/{@code -} next to it, which turns it into a chain of {@link TimestampArithmeticExpr}.
     */
    private static final class SparkInterval extends IntervalLiteral {
        private final List<IntervalPart> parts;

        private SparkInterval(List<IntervalPart> parts, NodePosition pos) {
            super(new IntLiteral(parts.get(0).amount), new UnitIdentifier(parts.get(0).unit), pos);
            this.parts = parts;
        }

        @Override
        public Expr clone() {
            return new SparkInterval(parts, getPos());
        }
    }

    // year-month and day-time units of "INTERVAL 'value' <from> TO <to>", in order, and the separator that follows
    private static final List<String> YEAR_MONTH_UNITS = List.of("YEAR", "MONTH");
    private static final List<String> DAY_TIME_UNITS = List.of("DAY", "HOUR", "MINUTE", "SECOND");
    private static final List<String> DAY_TIME_SEPARATORS = List.of(" ", ":", ":");

    @Override
    public ParseNode visitIntervalLiteral(SparkSqlBaseParser.IntervalLiteralContext ctx) {
        if (!isIntervalOperand(ctx)) {
            throw unsupported("INTERVAL value outside of date +/- interval");
        }
        List<IntervalPart> parts = new ArrayList<>();
        SparkSqlBaseParser.IntervalContext interval = ctx.interval();
        if (interval.errorCapturingMultiUnitsInterval() != null) {
            SparkSqlBaseParser.ErrorCapturingMultiUnitsIntervalContext multi =
                    interval.errorCapturingMultiUnitsInterval();
            if (multi.unitToUnitInterval() != null) {
                throw unsupported(ctx);
            }
            SparkSqlBaseParser.MultiUnitsIntervalContext body = multi.body;
            for (int i = 0; i < body.intervalValue().size(); i++) {
                addIntervalPart(parts, unitName(body.unit.get(i)), intervalNumber(body.intervalValue(i)), ctx);
            }
        } else {
            SparkSqlBaseParser.ErrorCapturingUnitToUnitIntervalContext single =
                    interval.errorCapturingUnitToUnitInterval();
            if (single.error1 != null || single.error2 != null) {
                throw unsupported(ctx);
            }
            parts.addAll(unitToUnitParts(single.body, ctx));
        }
        return new SparkInterval(parts, pos(ctx));
    }

    /**
     * An interval is supported as operand of a + or - only: constant -> primary -> value expression -> binary.
     */
    private static boolean isIntervalOperand(SparkSqlBaseParser.IntervalLiteralContext ctx) {
        ParserRuleContext primary = ctx.getParent();
        ParserRuleContext value = primary == null ? null : primary.getParent();
        ParserRuleContext binary = value == null ? null : value.getParent();
        if (!(primary instanceof SparkSqlBaseParser.ConstantDefaultContext)
                || !(value instanceof SparkSqlBaseParser.ValueExpressionDefaultContext)
                || !(binary instanceof SparkSqlBaseParser.ArithmeticBinaryContext)) {
            return false;
        }
        int op = ((SparkSqlBaseParser.ArithmeticBinaryContext) binary).operator.getType();
        return op == SparkSqlBaseLexer.PLUS || op == SparkSqlBaseLexer.MINUS;
    }

    private Expr buildIntervalArithmetic(SparkSqlBaseParser.ArithmeticBinaryContext ctx, Expr left, Expr right,
                                         NodePosition pos) {
        boolean add = ctx.operator.getType() == SparkSqlBaseLexer.PLUS;
        if (left instanceof SparkInterval && right instanceof SparkInterval) {
            throw unsupported("arithmetic between two INTERVAL values");
        }
        if (left instanceof SparkInterval && !add) {
            throw unsupported("INTERVAL - value");
        }
        SparkInterval interval = (SparkInterval) (left instanceof SparkInterval ? left : right);
        Expr result = left instanceof SparkInterval ? right : left;
        ArithmeticExpr.Operator op = add ? ArithmeticExpr.Operator.ADD : ArithmeticExpr.Operator.SUBTRACT;
        for (IntervalPart part : interval.parts) {
            result = new TimestampArithmeticExpr(op, result, new IntLiteral(part.amount), part.unit, false, pos);
        }
        return result;
    }

    private static String unitName(ParserRuleContext unit) {
        // MONTHS -> MONTH
        String name = unit.getText().toUpperCase(Locale.ROOT);
        return name.endsWith("S") ? name.substring(0, name.length() - 1) : name;
    }

    private static BigDecimal intervalNumber(SparkSqlBaseParser.IntervalValueContext value) {
        String text;
        if (value.INTEGER_VALUE() != null) {
            text = value.INTEGER_VALUE().getText();
        } else if (value.DECIMAL_VALUE() != null) {
            text = value.DECIMAL_VALUE().getText();
        } else {
            text = SparkParserUtils.stringLit(value.stringLit()).trim();
        }
        try {
            BigDecimal number = new BigDecimal(text);
            return value.MINUS() != null ? number.negate() : number;
        } catch (NumberFormatException e) {
            throw unsupported("INTERVAL value " + text);
        }
    }

    /**
     * Adds an amount of a Spark interval unit, converted to the units StarRocks can add.
     */
    private static void addIntervalPart(List<IntervalPart> parts, String unit, BigDecimal amount,
                                        ParserRuleContext ctx) {
        try {
            switch (unit) {
                case "YEAR":
                case "MONTH":
                case "WEEK":
                case "DAY":
                case "HOUR":
                case "MINUTE":
                    parts.add(intervalPart(unit, amount.longValueExact(), ctx));
                    return;
                case "SECOND":
                    if (amount.stripTrailingZeros().scale() <= 0) {
                        parts.add(intervalPart(unit, amount.longValueExact(), ctx));
                    } else {
                        parts.add(intervalPart("MICROSECOND", amount.movePointRight(6).longValueExact(), ctx));
                    }
                    return;
                case "MILLISECOND":
                    parts.add(intervalPart("MICROSECOND", amount.movePointRight(3).longValueExact(), ctx));
                    return;
                case "MICROSECOND":
                    parts.add(intervalPart("MICROSECOND", amount.longValueExact(), ctx));
                    return;
                case "NANOSECOND":
                    parts.add(intervalPart("MICROSECOND", amount.movePointLeft(3).longValueExact(), ctx));
                    return;
                default:
                    throw unsupported(ctx);
            }
        } catch (ArithmeticException e) {
            throw unsupported("INTERVAL amount " + amount.toPlainString() + " " + unit);
        }
    }

    private static IntervalPart intervalPart(String unit, long amount, ParserRuleContext ctx) {
        if (amount > Integer.MAX_VALUE || amount < Integer.MIN_VALUE) {
            throw unsupported("INTERVAL amount out of range: " + amount + " " + unit);
        }
        return new IntervalPart(unit, amount);
    }

    /**
     * INTERVAL '1-2' YEAR TO MONTH, INTERVAL '1 02:03:04.5' DAY TO SECOND, INTERVAL '10:20' HOUR TO MINUTE, ...
     */
    private static List<IntervalPart> unitToUnitParts(SparkSqlBaseParser.UnitToUnitIntervalContext ctx,
                                                      ParserRuleContext errorCtx) {
        String from = unitName(ctx.from);
        String to = unitName(ctx.to);
        List<String> units = YEAR_MONTH_UNITS.contains(from) ? YEAR_MONTH_UNITS : DAY_TIME_UNITS;
        int first = units.indexOf(from);
        int last = units.indexOf(to);
        if (first < 0 || last <= first) {
            throw unsupported(errorCtx);
        }
        StringBuilder regex = new StringBuilder("^([+-])?");
        for (int i = first; i <= last; i++) {
            regex.append("(\\d+)");
            if (i < last) {
                regex.append(Pattern.quote(units == YEAR_MONTH_UNITS ? "-" : DAY_TIME_SEPARATORS.get(i)));
            }
        }
        if (to.equals("SECOND")) {
            regex.append("(?:\\.(\\d{1,9}))?");
        }
        regex.append("$");
        String text = ctx.value.stringLit() == null ? null : SparkParserUtils.stringLit(ctx.value.stringLit()).trim();
        Matcher matcher = text == null ? null : Pattern.compile(regex.toString()).matcher(text);
        if (matcher == null || !matcher.matches()) {
            throw unsupported("INTERVAL value " + text + " for " + from + " TO " + to);
        }
        boolean negative = "-".equals(matcher.group(1)) != (ctx.value.MINUS() != null);
        List<IntervalPart> parts = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            long amount = Long.parseLong(matcher.group(i - first + 2));
            addIntervalPart(parts, units.get(i), BigDecimal.valueOf(negative ? -amount : amount), errorCtx);
        }
        int fractionGroup = last - first + 3;
        if (to.equals("SECOND") && matcher.group(fractionGroup) != null) {
            String fraction = (matcher.group(fractionGroup) + "000000000").substring(0, 9);
            long nanos = Long.parseLong(fraction);
            if (nanos % 1000 != 0) {
                throw unsupported("INTERVAL with nanoseconds");
            }
            parts.add(intervalPart("MICROSECOND", negative ? -nanos / 1000 : nanos / 1000, errorCtx));
        }
        return parts;
    }

    // -------------------------------------- primary expressions ------------------------------------------------

    @Override
    public ParseNode visitColumnReference(SparkSqlBaseParser.ColumnReferenceContext ctx) {
        String name = SparkParserUtils.identifier(ctx.identifier());
        return new SlotRef(QualifiedName.of(List.of(name), pos(ctx)));
    }

    @Override
    public ParseNode visitDereference(SparkSqlBaseParser.DereferenceContext ctx) {
        Expr base = expr(ctx.base);
        String field = SparkParserUtils.identifier(ctx.fieldName);
        if (base instanceof SlotRef) {
            List<String> parts = new ArrayList<>(((SlotRef) base).getQualifiedName().getParts());
            parts.add(field);
            return new SlotRef(QualifiedName.of(parts, pos(ctx)));
        } else if (base instanceof SubfieldExpr) {
            SubfieldExpr subfield = (SubfieldExpr) base;
            List<String> names = new ArrayList<>(subfield.getFieldNames());
            names.add(field);
            return new SubfieldExpr(subfield.getChild(0), names, pos(ctx));
        }
        return new SubfieldExpr(base, List.of(field), pos(ctx));
    }

    @Override
    public ParseNode visitSubscript(SparkSqlBaseParser.SubscriptContext ctx) {
        // Spark arrays are 0-based. The base type is unknown here, so the analyzer shifts the index when the
        // base turns out to be an array and leaves maps alone.
        CollectionElementExpr element = new CollectionElementExpr(expr(ctx.value), expr(ctx.index), false);
        element.setZeroBasedSubscript(true);
        return element;
    }

    @Override
    public ParseNode visitParenthesizedExpression(SparkSqlBaseParser.ParenthesizedExpressionContext ctx) {
        return root.visit(ctx.expression());
    }

    @Override
    public ParseNode visitSubqueryExpression(SparkSqlBaseParser.SubqueryExpressionContext ctx) {
        QueryRelation query = root.visit(ctx.query(), QueryRelation.class);
        return new Subquery(new QueryStatement(query), pos(ctx));
    }

    @Override
    public ParseNode visitCurrentLike(SparkSqlBaseParser.CurrentLikeContext ctx) {
        NodePosition pos = pos(ctx);
        switch (ctx.name.getType()) {
            case SparkSqlBaseLexer.CURRENT_DATE:
                return functionTranslator.translate(new FunctionCallExpr("current_date", new ArrayList<>(), pos));
            case SparkSqlBaseLexer.CURRENT_TIMESTAMP:
                return functionTranslator.translate(
                        new FunctionCallExpr("current_timestamp", new ArrayList<>(), pos));
            case SparkSqlBaseLexer.CURRENT_USER:
                return new InformationFunction(FunctionSet.CURRENT_USER.toUpperCase(), pos);
            default:
                return new InformationFunction("USER", pos);
        }
    }

    @Override
    public ParseNode visitSearchedCase(SparkSqlBaseParser.SearchedCaseContext ctx) {
        return new CaseExpr(null, whenClauses(ctx.whenClause()),
                ctx.elseExpression == null ? null : expr(ctx.elseExpression), pos(ctx));
    }

    @Override
    public ParseNode visitSimpleCase(SparkSqlBaseParser.SimpleCaseContext ctx) {
        return new CaseExpr(expr(ctx.value), whenClauses(ctx.whenClause()),
                ctx.elseExpression == null ? null : expr(ctx.elseExpression), pos(ctx));
    }

    private List<CaseWhenClause> whenClauses(List<SparkSqlBaseParser.WhenClauseContext> clauses) {
        List<CaseWhenClause> result = new ArrayList<>();
        for (SparkSqlBaseParser.WhenClauseContext clause : clauses) {
            result.add(new CaseWhenClause(expr(clause.condition), expr(clause.result), pos(clause)));
        }
        return result;
    }

    @Override
    public ParseNode visitCast(SparkSqlBaseParser.CastContext ctx) {
        // TRY_CAST(x AS t) is CAST(x AS t): a failing cast gives NULL in StarRocks (the default, non strict mode)
        return new CastExpr(new TypeDef(toType(ctx.dataType())), expr(ctx.expression()), pos(ctx));
    }

    /**
     * struct(a, b AS c, 1 + 2) is named_struct('a', a, 'c', b, 'col3', 1 + 2): Spark names the fields after the
     * alias or the column, or colN (1-based position) for other expressions.
     */
    @Override
    public ParseNode visitStruct(SparkSqlBaseParser.StructContext ctx) {
        List<Expr> args = new ArrayList<>();
        int position = 0;
        for (SparkSqlBaseParser.NamedExpressionContext named : ctx.argument) {
            position++;
            if (named.identifierList() != null) {
                throw unsupported("multiple aliases in struct()");
            }
            Expr value = expr(named.expression());
            String name;
            if (named.name != null) {
                name = SparkParserUtils.identifier(named.name);
            } else if (value instanceof SlotRef) {
                List<String> parts = ((SlotRef) value).getQualifiedName().getParts();
                name = parts.get(parts.size() - 1);
            } else if (value instanceof SubfieldExpr) {
                List<String> fields = ((SubfieldExpr) value).getFieldNames();
                name = fields.get(fields.size() - 1);
            } else {
                name = "col" + position;
            }
            args.add(new StringLiteral(name));
            args.add(value);
        }
        if (args.isEmpty()) {
            throw unsupported("struct() without fields");
        }
        return new FunctionCallExpr(FunctionSet.NAMED_STRUCT, args, pos(ctx));
    }

    @Override
    public ParseNode visitLambda(SparkSqlBaseParser.LambdaContext ctx) {
        List<Expr> arguments = new ArrayList<>();
        // the body is the first child, followed by the argument declarations
        arguments.add(expr(ctx.expression()));
        for (SparkSqlBaseParser.IdentifierContext identifier : ctx.identifier()) {
            arguments.add(new LambdaArgument(SparkParserUtils.identifier(identifier)));
        }
        return new LambdaFunctionExpr(arguments, pos(ctx));
    }

    @Override
    public ParseNode visitSubstring(SparkSqlBaseParser.SubstringContext ctx) {
        List<Expr> args = new ArrayList<>();
        args.add(expr(ctx.str));
        args.add(expr(ctx.pos));
        if (ctx.len != null) {
            args.add(expr(ctx.len));
        }
        return functionTranslator.translate(new FunctionCallExpr("substring", args, pos(ctx)));
    }

    @Override
    public ParseNode visitExtract(SparkSqlBaseParser.ExtractContext ctx) {
        String field = SparkParserUtils.identifier(ctx.field).toLowerCase();
        if (!EXTRACT_FIELDS.contains(field)) {
            throw unsupported("EXTRACT(" + field + " FROM ...)");
        }
        return functionTranslator.translate(
                new FunctionCallExpr(field, Lists.newArrayList(expr(ctx.source)), pos(ctx)));
    }

    @Override
    public ParseNode visitTrim(SparkSqlBaseParser.TrimContext ctx) {
        if (ctx.trimStr != null) {
            throw unsupported("TRIM with a trim string");
        }
        String name = "trim";
        if (ctx.trimOption != null) {
            if (ctx.trimOption.getType() == SparkSqlBaseLexer.LEADING) {
                name = "ltrim";
            } else if (ctx.trimOption.getType() == SparkSqlBaseLexer.TRAILING) {
                name = "rtrim";
            }
        }
        return functionTranslator.translate(new FunctionCallExpr(name, Lists.newArrayList(expr(ctx.srcStr)),
                pos(ctx)));
    }

    @Override
    public ParseNode visitPosition(SparkSqlBaseParser.PositionContext ctx) {
        return functionTranslator.translate(new FunctionCallExpr("instr",
                Lists.newArrayList(expr(ctx.str), expr(ctx.substr)), pos(ctx)));
    }

    // ---------------------------------------------- functions --------------------------------------------------

    @Override
    public ParseNode visitFunctionCall(SparkSqlBaseParser.FunctionCallContext ctx) {
        String name = functionName(ctx.functionName());
        NodePosition pos = pos(ctx);
        boolean distinct = ctx.setQuantifier() != null && ctx.setQuantifier().DISTINCT() != null;

        List<Expr> args = new ArrayList<>();
        boolean star = false;
        for (SparkSqlBaseParser.FunctionArgumentContext arg : ctx.argument) {
            if (arg.namedArgumentExpression() != null) {
                SparkSqlBaseParser.NamedArgumentExpressionContext named = arg.namedArgumentExpression();
                args.add(new NamedArgument(SparkParserUtils.identifier(named.key), expr(named.value), pos(named)));
                continue;
            }
            ParseTree inner = SparkParserUtils.unwrapSingleChild(arg.expression());
            if (inner instanceof SparkSqlBaseParser.StarContext) {
                SparkSqlBaseParser.StarContext starCtx = (SparkSqlBaseParser.StarContext) inner;
                if (starCtx.qualifiedName() != null || ctx.argument.size() != 1) {
                    throw unsupported(ctx);
                }
                star = true;
            } else {
                args.add(expr(arg.expression()));
            }
        }

        if (ctx.windowSpec() == null && ctx.where == null && !distinct && !star) {
            if (name.equals("array")) {
                return new ArrayExpr(null, args, pos);
            }
            if (name.equals("map")) {
                if (args.size() % 2 != 0) {
                    throw new ParsingException("map expects an even number of arguments (key/value pairs), got "
                            + args.size(), pos);
                }
                return new MapExpr(AnyMapType.ANY_MAP, args, pos);
            }
        }

        if (ctx.windowSpec() == null && (name.equals("grouping") || name.equals("grouping_id"))) {
            return new GroupingFunctionCallExpr(name, args, pos);
        }

        FunctionCallExpr call;
        if (ctx.where != null) {
            // an aggregate filter becomes the combinator "<agg>_if(args..., condition)"
            if (distinct) {
                throw unsupported("DISTINCT aggregate with FILTER");
            }
            Expr condition = expr(ctx.where);
            if (star) {
                if (!name.equals(FunctionSet.COUNT)) {
                    throw unsupported(ctx);
                }
                call = new FunctionCallExpr(FunctionSet.COUNT + FunctionSet.AGG_STATE_IF_SUFFIX,
                        new FunctionParams(false, Lists.newArrayList(condition), null, false, null), pos);
            } else {
                args.add(condition);
                call = new FunctionCallExpr(name + FunctionSet.AGG_STATE_IF_SUFFIX, new FunctionParams(false, args),
                        pos);
            }
        } else if (star) {
            call = new FunctionCallExpr(name, FunctionParams.createStarParam(), pos);
        } else {
            call = new FunctionCallExpr(name, new FunctionParams(distinct, args), pos);
        }
        if (ctx.nullsOption != null && ctx.nullsOption.getType() == SparkSqlBaseLexer.IGNORE) {
            call.setIgnoreNulls(true);
        }

        Expr translated = functionTranslator.translate(call);
        if (ctx.windowSpec() == null) {
            return translated;
        }
        if (!(translated instanceof FunctionCallExpr)) {
            throw unsupported("window function " + name);
        }
        return buildOver((FunctionCallExpr) translated, ctx.windowSpec(), pos);
    }

    private static String functionName(SparkSqlBaseParser.FunctionNameContext ctx) {
        if (ctx.qualifiedName() == null) {
            if (ctx.IDENTIFIER_KW() != null && ctx.expression() != null) {
                throw unsupported("IDENTIFIER(...) function name");
            }
            // IDENTIFIER, FILTER, LEFT and RIGHT used as function names
            return ctx.getText().toLowerCase();
        }
        List<String> parts = new ArrayList<>();
        for (SparkSqlBaseParser.IdentifierContext part : ctx.qualifiedName().identifier()) {
            parts.add(SparkParserUtils.identifier(part));
        }
        return String.join(".", parts).toLowerCase();
    }

    private AnalyticExpr buildOver(FunctionCallExpr call, SparkSqlBaseParser.WindowSpecContext spec, NodePosition pos) {
        SparkSqlBaseParser.WindowDefContext def = resolveWindow(spec, new HashSet<>());
        if (def.CLUSTER() != null) {
            throw unsupported("window CLUSTER BY");
        }
        call.setIsAnalyticFnCall(true);
        List<Expr> partitions = exprs(def.partition);
        List<OrderByElement> orderBy = new ArrayList<>();
        for (SparkSqlBaseParser.SortItemContext item : def.sortItem()) {
            orderBy.add(root.visit(item, OrderByElement.class));
        }
        AnalyticWindow frame = def.windowFrame() == null ? null : buildFrame(def.windowFrame());
        return new AnalyticExpr(call, partitions, orderBy, frame, null, pos, null, List.of());
    }

    /**
     * The definition of a window specification: itself, or for a reference the definition in the WINDOW clause
     * of the innermost enclosing query that declares that name. StarRocks has no named windows, so the definition
     * is inlined (and built again) for every use.
     */
    private SparkSqlBaseParser.WindowDefContext resolveWindow(SparkSqlBaseParser.WindowSpecContext spec,
                                                              Set<String> visiting) {
        if (spec instanceof SparkSqlBaseParser.WindowDefContext) {
            return (SparkSqlBaseParser.WindowDefContext) spec;
        }
        SparkSqlBaseParser.WindowRefContext ref = (SparkSqlBaseParser.WindowRefContext) spec;
        String name = SparkParserUtils.identifier(ref.name);
        if (!visiting.add(name.toLowerCase(Locale.ROOT))) {
            throw new ParsingException("Window " + name + " is defined in terms of itself", pos(ref));
        }
        for (ParserRuleContext scope = ref.getParent(); scope != null; scope = scope.getParent()) {
            SparkSqlBaseParser.WindowClauseContext clause = null;
            if (scope instanceof SparkSqlBaseParser.RegularQuerySpecificationContext) {
                clause = ((SparkSqlBaseParser.RegularQuerySpecificationContext) scope).windowClause();
            } else if (scope instanceof SparkSqlBaseParser.FromStatementBodyContext) {
                clause = ((SparkSqlBaseParser.FromStatementBodyContext) scope).windowClause();
            } else if (scope instanceof SparkSqlBaseParser.QueryContext) {
                clause = ((SparkSqlBaseParser.QueryContext) scope).queryOrganization().windowClause();
            }
            if (clause == null) {
                continue;
            }
            for (SparkSqlBaseParser.NamedWindowContext named : clause.namedWindow()) {
                if (SparkParserUtils.identifier(named.name).equalsIgnoreCase(name)) {
                    return resolveWindow(named.windowSpec(), visiting);
                }
            }
        }
        throw new ParsingException("Window specification " + name + " is not defined in the WINDOW clause",
                pos(ref));
    }

    private AnalyticWindow buildFrame(SparkSqlBaseParser.WindowFrameContext ctx) {
        AnalyticWindow.Type type = ctx.frameType.getType() == SparkSqlBaseLexer.RANGE
                ? AnalyticWindow.Type.RANGE : AnalyticWindow.Type.ROWS;
        AnalyticWindowBoundary start = buildBoundary(ctx.start);
        if (ctx.end != null) {
            return new AnalyticWindow(type, start, buildBoundary(ctx.end), pos(ctx));
        }
        return new AnalyticWindow(type, start, pos(ctx));
    }

    private AnalyticWindowBoundary buildBoundary(SparkSqlBaseParser.FrameBoundContext ctx) {
        if (ctx.UNBOUNDED() != null) {
            return new AnalyticWindowBoundary(ctx.boundType.getType() == SparkSqlBaseLexer.PRECEDING
                    ? AnalyticWindowBoundary.BoundaryType.UNBOUNDED_PRECEDING
                    : AnalyticWindowBoundary.BoundaryType.UNBOUNDED_FOLLOWING, null);
        }
        if (ctx.CURRENT() != null) {
            return new AnalyticWindowBoundary(AnalyticWindowBoundary.BoundaryType.CURRENT_ROW, null);
        }
        return new AnalyticWindowBoundary(ctx.boundType.getType() == SparkSqlBaseLexer.PRECEDING
                ? AnalyticWindowBoundary.BoundaryType.PRECEDING
                : AnalyticWindowBoundary.BoundaryType.FOLLOWING, expr(ctx.expression()));
    }

    // ---------------------------------------------- literals ---------------------------------------------------

    @Override
    public ParseNode visitNullLiteral(SparkSqlBaseParser.NullLiteralContext ctx) {
        return new NullLiteral(pos(ctx));
    }

    @Override
    public ParseNode visitBooleanLiteral(SparkSqlBaseParser.BooleanLiteralContext ctx) {
        return new BoolLiteral(ctx.booleanValue().TRUE() != null, pos(ctx));
    }

    @Override
    public ParseNode visitStringLiteral(SparkSqlBaseParser.StringLiteralContext ctx) {
        StringBuilder value = new StringBuilder();
        for (SparkSqlBaseParser.StringLitContext part : ctx.stringLit()) {
            value.append(SparkParserUtils.stringLit(part));
        }
        return new StringLiteral(value.toString(), pos(ctx));
    }

    @Override
    public ParseNode visitTypeConstructor(SparkSqlBaseParser.TypeConstructorContext ctx) {
        NodePosition pos = pos(ctx);
        String value = SparkParserUtils.stringLit(ctx.stringLit());
        SparkSqlBaseParser.LiteralTypeContext type = ctx.literalType();
        switch (type.getStart().getType()) {
            case SparkSqlBaseLexer.DATE:
                return new DateLiteral(parseDateTime(value, pos), DateType.DATE);
            case SparkSqlBaseLexer.TIMESTAMP:
            case SparkSqlBaseLexer.TIMESTAMP_LTZ:
            case SparkSqlBaseLexer.TIMESTAMP_NTZ:
                return new DateLiteral(parseDateTime(value, pos), DateType.DATETIME);
            case SparkSqlBaseLexer.BINARY_HEX:
                return new VarBinaryLiteral(value, pos);
            default:
                throw unsupported(ctx);
        }
    }

    private static LocalDateTime parseDateTime(String value, NodePosition pos) {
        try {
            return DateUtils.parseStrictDateTime(value.trim());
        } catch (RuntimeException e) {
            throw new ParsingException("Invalid date or timestamp literal: " + value, pos);
        }
    }

    @Override
    public ParseNode visitIntegerLiteral(SparkSqlBaseParser.IntegerLiteralContext ctx) {
        return integer(ctx.getText(), pos(ctx));
    }

    private static Expr integer(String text, NodePosition pos) {
        BigInteger value = new BigInteger(text);
        if (value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
            return new IntLiteral(value.longValue(), pos);
        } else if (value.compareTo(LARGEINT_MIN) >= 0 && value.compareTo(LARGEINT_MAX) <= 0) {
            return new LargeIntLiteral(value.toString(), pos);
        } else if (value.abs().compareTo(INT256_MAX) <= 0) {
            return new DecimalLiteral(value.toString(), pos);
        }
        throw new ParsingException("Numeric overflow " + text, pos);
    }

    @Override
    public ParseNode visitBigIntLiteral(SparkSqlBaseParser.BigIntLiteralContext ctx) {
        return typedInteger(ctx.getText(), 1, IntegerType.BIGINT, pos(ctx));
    }

    @Override
    public ParseNode visitSmallIntLiteral(SparkSqlBaseParser.SmallIntLiteralContext ctx) {
        return typedInteger(ctx.getText(), 1, IntegerType.SMALLINT, pos(ctx));
    }

    @Override
    public ParseNode visitTinyIntLiteral(SparkSqlBaseParser.TinyIntLiteralContext ctx) {
        return typedInteger(ctx.getText(), 1, IntegerType.TINYINT, pos(ctx));
    }

    private static Expr typedInteger(String text, int suffixLength, Type type, NodePosition pos) {
        try {
            return new IntLiteral(Long.parseLong(text.substring(0, text.length() - suffixLength)), type);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ParsingException("Invalid numeric literal: " + text, pos);
        }
    }

    @Override
    public ParseNode visitDecimalLiteral(SparkSqlBaseParser.DecimalLiteralContext ctx) {
        return decimal(ctx.getText(), pos(ctx));
    }

    @Override
    public ParseNode visitLegacyDecimalLiteral(SparkSqlBaseParser.LegacyDecimalLiteralContext ctx) {
        return decimal(ctx.getText(), pos(ctx));
    }

    @Override
    public ParseNode visitBigDecimalLiteral(SparkSqlBaseParser.BigDecimalLiteralContext ctx) {
        String text = ctx.getText();
        return decimal(text.substring(0, text.length() - 2), pos(ctx));
    }

    private static Expr decimal(String text, NodePosition pos) {
        try {
            BigDecimal decimal = new BigDecimal(text);
            int integerDigits = DecimalLiteral.getRealPrecision(decimal) - DecimalLiteral.getRealScale(decimal);
            if (integerDigits > 38) {
                return new FloatLiteral(decimal.doubleValue(), pos);
            }
            return new DecimalLiteral(decimal, pos);
        } catch (NumberFormatException e) {
            throw new ParsingException("Invalid numeric literal: " + text, pos);
        }
    }

    @Override
    public ParseNode visitExponentLiteral(SparkSqlBaseParser.ExponentLiteralContext ctx) {
        return new FloatLiteral(Double.parseDouble(ctx.getText()), pos(ctx));
    }

    @Override
    public ParseNode visitDoubleLiteral(SparkSqlBaseParser.DoubleLiteralContext ctx) {
        String text = ctx.getText();
        return new FloatLiteral(Double.parseDouble(text.substring(0, text.length() - 1)), FloatType.DOUBLE);
    }

    @Override
    public ParseNode visitFloatLiteral(SparkSqlBaseParser.FloatLiteralContext ctx) {
        String text = ctx.getText();
        return new FloatLiteral(Double.parseDouble(text.substring(0, text.length() - 1)), FloatType.FLOAT);
    }

    // ----------------------------------------------- types -----------------------------------------------------

    /**
     * Maps a Spark data type to the StarRocks type: STRING becomes VARCHAR(65533), BINARY becomes VARBINARY and
     * TIMESTAMP becomes DATETIME.
     */
    public Type toType(SparkSqlBaseParser.DataTypeContext ctx) {
        if (ctx instanceof SparkSqlBaseParser.ComplexDataTypeContext) {
            SparkSqlBaseParser.ComplexDataTypeContext complex = (SparkSqlBaseParser.ComplexDataTypeContext) ctx;
            switch (complex.complex.getType()) {
                case SparkSqlBaseLexer.ARRAY:
                    return new ArrayType(toType(complex.dataType(0)));
                case SparkSqlBaseLexer.MAP:
                    return new MapType(toType(complex.dataType(0)), toType(complex.dataType(1)));
                default:
                    return toStructType(complex);
            }
        }
        if (!(ctx instanceof SparkSqlBaseParser.PrimitiveDataTypeContext)) {
            // INTERVAL types
            throw unsupported(ctx);
        }
        SparkSqlBaseParser.PrimitiveDataTypeContext primitive = (SparkSqlBaseParser.PrimitiveDataTypeContext) ctx;
        List<TerminalNode> params = primitive.INTEGER_VALUE();
        int first = params.isEmpty() ? -1 : Integer.parseInt(params.get(0).getText());
        int second = params.size() < 2 ? -1 : Integer.parseInt(params.get(1).getText());
        Token type = primitive.type().getStart();
        switch (type.getType()) {
            case SparkSqlBaseLexer.BOOLEAN:
                return BooleanType.BOOLEAN;
            case SparkSqlBaseLexer.TINYINT:
            case SparkSqlBaseLexer.BYTE:
                return IntegerType.TINYINT;
            case SparkSqlBaseLexer.SMALLINT:
            case SparkSqlBaseLexer.SHORT:
                return IntegerType.SMALLINT;
            case SparkSqlBaseLexer.INT:
            case SparkSqlBaseLexer.INTEGER:
                return IntegerType.INT;
            case SparkSqlBaseLexer.BIGINT:
            case SparkSqlBaseLexer.LONG:
                return IntegerType.BIGINT;
            case SparkSqlBaseLexer.FLOAT:
            case SparkSqlBaseLexer.REAL:
                return FloatType.FLOAT;
            case SparkSqlBaseLexer.DOUBLE:
                return FloatType.DOUBLE;
            case SparkSqlBaseLexer.DATE:
                return DateType.DATE;
            case SparkSqlBaseLexer.TIMESTAMP:
            case SparkSqlBaseLexer.TIMESTAMP_LTZ:
            case SparkSqlBaseLexer.TIMESTAMP_NTZ:
                return DateType.DATETIME;
            case SparkSqlBaseLexer.STRING:
                return TypeFactory.createVarcharType(StringType.DEFAULT_STRING_LENGTH);
            case SparkSqlBaseLexer.CHAR:
            case SparkSqlBaseLexer.CHARACTER:
                requireLength(first, "CHAR", ctx);
                return TypeFactory.createCharType(first);
            case SparkSqlBaseLexer.VARCHAR:
                requireLength(first, "VARCHAR", ctx);
                return TypeFactory.createVarcharType(first);
            case SparkSqlBaseLexer.BINARY:
                return VarbinaryType.VARBINARY;
            case SparkSqlBaseLexer.DECIMAL:
            case SparkSqlBaseLexer.DEC:
            case SparkSqlBaseLexer.NUMERIC:
                // Spark: DECIMAL is DECIMAL(10, 0)
                return TypeFactory.createUnifiedDecimalType(first < 0 ? 10 : first, second < 0 ? 0 : second);
            case SparkSqlBaseLexer.VOID:
                return NullType.NULL;
            default:
                throw unsupported("data type " + type.getText());
        }
    }

    private static void requireLength(int length, String type, ParserRuleContext ctx) {
        if (length < 0) {
            throw new ParsingException("DataType " + type + " requires a length parameter", pos(ctx));
        }
    }

    private Type toStructType(SparkSqlBaseParser.ComplexDataTypeContext ctx) {
        ArrayList<StructField> fields = new ArrayList<>();
        if (ctx.complexColTypeList() != null) {
            for (SparkSqlBaseParser.ComplexColTypeContext field : ctx.complexColTypeList().complexColType()) {
                fields.add(new StructField(SparkParserUtils.identifier(field.identifier()),
                        toType(field.dataType())));
            }
        }
        return new StructType(fields);
    }
}
