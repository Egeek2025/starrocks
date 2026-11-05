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

import com.starrocks.sql.ast.ParseNode;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParserBaseVisitor;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates Spark parse trees to StarRocks AST. This class handles statements and dispatches every other
 * rule to a specialised builder:
 * <ul>
 *   <li>{@link SparkRelationBuilder}: queries, FROM clause, joins, set operations, CTE.</li>
 *   <li>{@link SparkExprBuilder}: expressions, literals, types, function calls, windows.</li>
 * </ul>
 * A builder handles a grammar rule by declaring a public {@code visitXxx(XxxContext)} method; it is found by
 * reflection when the builder is registered, so adding a rule never requires touching this class. A rule that no
 * builder handles throws {@link SparkUnsupportedException} (except single-child rules, which just delegate to their
 * child), so the statement falls back to the StarRocks parser.
 */
public class SparkAstBuilder extends SparkSqlBaseParserBaseVisitor<ParseNode> {
    private final Map<Class<?>, SparkSqlBaseParserBaseVisitor<ParseNode>> dispatch = new HashMap<>();

    public SparkAstBuilder() {
        register(new SparkRelationBuilder(this));
        register(new SparkExprBuilder(this));
    }

    /**
     * Registers the rules declared by the builder. A rule can be handled by one builder only.
     */
    protected final void register(SparkSqlBaseParserBaseVisitor<ParseNode> builder) {
        for (Method method : builder.getClass().getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isBridge() || method.isSynthetic()
                    || !method.getName().startsWith("visit") || method.getParameterCount() != 1
                    || !ParserRuleContext.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            SparkSqlBaseParserBaseVisitor<ParseNode> previous = dispatch.put(method.getParameterTypes()[0], builder);
            if (previous != null) {
                throw new IllegalStateException("Rule " + method.getParameterTypes()[0].getSimpleName()
                        + " is handled by both " + previous.getClass().getSimpleName() + " and "
                        + builder.getClass().getSimpleName());
            }
        }
    }

    /**
     * Visits a node with the builder that owns its rule.
     */
    @Override
    public ParseNode visit(ParseTree tree) {
        SparkSqlBaseParserBaseVisitor<ParseNode> builder = dispatch.get(tree.getClass());
        if (builder != null) {
            return tree.accept(builder);
        }
        return tree.accept(this);
    }

    /**
     * Visits a node and requires a non-null result of the given type.
     */
    public <T> T visit(ParseTree tree, Class<T> clazz) {
        ParseNode node = visit(tree);
        if (!clazz.isInstance(node)) {
            throw unsupported(tree);
        }
        return clazz.cast(node);
    }

    @Override
    public ParseNode visitChildren(RuleNode node) {
        if (node.getChildCount() == 1 && node.getChild(0) instanceof RuleNode) {
            return visit(node.getChild(0));
        }
        throw unsupported((ParseTree) node);
    }

    public static SparkUnsupportedException unsupported(ParseTree tree) {
        String text = tree instanceof ParserRuleContext
                ? SparkParserUtils.abbreviate(SparkParserUtils.originalText((ParserRuleContext) tree))
                : tree.getText();
        return new SparkUnsupportedException("Spark SQL syntax is not supported yet: "
                + SparkParserUtils.ruleName(tree) + " [" + text + "]");
    }

    public static SparkUnsupportedException unsupported(String feature) {
        return new SparkUnsupportedException("Spark SQL syntax is not supported yet: " + feature);
    }

    // ------------------------------------------- statements ----------------------------------------------------

    @Override
    public ParseNode visitSingleStatement(SparkSqlBaseParser.SingleStatementContext ctx) {
        ParseNode node = visit(ctx.statement());
        if (!(node instanceof StatementBase)) {
            throw unsupported(ctx);
        }
        return node;
    }

    @Override
    public ParseNode visitStatementDefault(SparkSqlBaseParser.StatementDefaultContext ctx) {
        QueryRelation relation = visit(ctx.query(), QueryRelation.class);
        QueryStatement statement = new QueryStatement(relation);
        statement.setQueryStartIndex(ctx.query().start.getStartIndex());
        return statement;
    }

    @Override
    public ParseNode visitExplain(SparkSqlBaseParser.ExplainContext ctx) {
        StatementBase.ExplainLevel level = StatementBase.ExplainLevel.NORMAL;
        if (ctx.LOGICAL() != null) {
            level = StatementBase.ExplainLevel.LOGICAL;
        } else if (ctx.EXTENDED() != null) {
            level = StatementBase.ExplainLevel.VERBOSE;
        } else if (ctx.COST() != null) {
            level = StatementBase.ExplainLevel.COSTS;
        } else if (ctx.CODEGEN() != null) {
            throw unsupported("EXPLAIN CODEGEN");
        }
        // FORMATTED is the plain readable plan, which is the StarRocks default
        ParseNode inner = visit(ctx.statement());
        if (!(inner instanceof QueryStatement)) {
            throw unsupported(ctx);
        }
        ((QueryStatement) inner).setIsExplain(true, level);
        return inner;
    }
}
