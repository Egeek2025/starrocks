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

import com.starrocks.sql.parser.NodePosition;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless helpers shared by the Spark AST builders.
 */
public final class SparkParserUtils {
    private SparkParserUtils() {
    }

    public static NodePosition pos(ParserRuleContext ctx) {
        Token start = ctx.start;
        Token stop = ctx.stop;
        if (start == null) {
            return NodePosition.ZERO;
        }
        if (stop == null) {
            return new NodePosition(start.getLine(), start.getCharPositionInLine());
        }
        return new NodePosition(start, stop);
    }

    public static NodePosition pos(Token token) {
        return new NodePosition(token.getLine(), token.getCharPositionInLine());
    }

    /**
     * The rule name of a context, e.g. "RegularQuerySpecification" for error messages.
     */
    public static String ruleName(ParseTree ctx) {
        String name = ctx.getClass().getSimpleName();
        return name.endsWith("Context") ? name.substring(0, name.length() - "Context".length()) : name;
    }

    /**
     * Original SQL text of a context including the whitespace between its tokens.
     */
    public static String originalText(ParserRuleContext ctx) {
        if (ctx.start == null || ctx.stop == null) {
            return ctx.getText();
        }
        return ctx.start.getInputStream().getText(
                new org.antlr.v4.runtime.misc.Interval(ctx.start.getStartIndex(), ctx.stop.getStopIndex()));
    }

    public static String abbreviate(String text) {
        String single = text.replaceAll("\\s+", " ");
        return single.length() > 60 ? single.substring(0, 57) + "..." : single;
    }

    // ------------------------------------------ identifiers ----------------------------------------------------

    public static String identifier(SparkSqlBaseParser.IdentifierContext ctx) {
        return unquoteIdentifier(ctx.getText());
    }

    public static String identifier(SparkSqlBaseParser.StrictIdentifierContext ctx) {
        return unquoteIdentifier(ctx.getText());
    }

    /**
     * An identifier followed by "-ident" parts is how Spark captures unquoted identifiers like test-table, which it
     * rejects.
     */
    public static String identifier(SparkSqlBaseParser.ErrorCapturingIdentifierContext ctx) {
        if (ctx.errorCapturingIdentifierExtra() instanceof SparkSqlBaseParser.ErrorIdentContext) {
            throw new ParsingException("Possibly unquoted identifier " + ctx.getText()
                    + " detected. Please consider quoting it with back-quotes as `" + ctx.getText() + "`",
                    pos(ctx));
        }
        return identifier(ctx.identifier());
    }

    private static String unquoteIdentifier(String text) {
        if (text.length() >= 2 && text.charAt(0) == '`' && text.charAt(text.length() - 1) == '`') {
            return text.substring(1, text.length() - 1).replace("``", "`");
        }
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            // only produced when double_quoted_identifiers is on
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    public static List<String> multipartIdentifier(SparkSqlBaseParser.MultipartIdentifierContext ctx) {
        List<String> parts = new ArrayList<>();
        for (SparkSqlBaseParser.ErrorCapturingIdentifierContext part : ctx.parts) {
            parts.add(identifier(part));
        }
        return parts;
    }

    public static List<String> identifierSeq(SparkSqlBaseParser.IdentifierListContext ctx) {
        List<String> names = new ArrayList<>();
        for (SparkSqlBaseParser.ErrorCapturingIdentifierContext ident : ctx.identifierSeq().ident) {
            names.add(identifier(ident));
        }
        return names;
    }

    // ------------------------------------------ string literals ------------------------------------------------

    /**
     * Value of one STRING_LITERAL or DOUBLEQUOTED_STRING token, with Spark's backslash escapes resolved.
     * Raw strings (R'...') are returned verbatim.
     */
    public static String stringTokenValue(TerminalNode node) {
        String text = node.getText();
        if (text.length() >= 3 && (text.charAt(0) == 'R' || text.charAt(0) == 'r')
                && (text.charAt(1) == '\'' || text.charAt(1) == '"')) {
            return text.substring(2, text.length() - 1);
        }
        return unescapeSqlString(text.substring(1, text.length() - 1));
    }

    /**
     * Adjacent string literals are concatenated, as in Spark.
     */
    public static String stringLit(SparkSqlBaseParser.StringLitContext ctx) {
        return stringTokenValue((TerminalNode) ctx.getChild(0));
    }

    /**
     * Port of Spark's ParserUtils.unescapeSQLString for the default (non escapedStringLiterals) mode.
     */
    public static String unescapeSqlString(String b) {
        StringBuilder sb = new StringBuilder(b.length());
        int i = 0;
        int length = b.length();
        while (i < length) {
            char c = b.charAt(i);
            if (c != '\\' || i + 1 >= length) {
                sb.append(c);
                i++;
                continue;
            }
            char n = b.charAt(i + 1);
            // \u0000 style unicode escape
            if (n == 'u' && isHex(b, i + 2, 4)) {
                sb.append((char) Integer.parseInt(b.substring(i + 2, i + 6), 16));
                i += 6;
            } else if (n == 'U' && isHex(b, i + 2, 8)) {
                sb.appendCodePoint(Integer.parseInt(b.substring(i + 2, i + 10), 16));
                i += 10;
            } else if (n >= '0' && n <= '7' && isOctal(b, i + 1, 3)) {
                sb.append((char) Integer.parseInt(b.substring(i + 1, i + 4), 8));
                i += 4;
            } else {
                switch (n) {
                    case '0':
                        sb.append('\u0000');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'Z':
                        sb.append('\u001A');
                        break;
                    case '%':
                    case '_':
                        // keep the backslash so that LIKE patterns still see the escape
                        sb.append('\\').append(n);
                        break;
                    default:
                        sb.append(n);
                        break;
                }
                i += 2;
            }
        }
        return sb.toString();
    }

    private static boolean isHex(String s, int from, int count) {
        if (from + count > s.length()) {
            return false;
        }
        for (int i = from; i < from + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOctal(String s, int from, int count) {
        if (from + count > s.length()) {
            return false;
        }
        for (int i = from; i < from + count; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '7') {
                return false;
            }
        }
        return true;
    }

    /**
     * Skips single-child rule chains, e.g. expression -> booleanExpression -> predicated -> ... and returns the
     * innermost context.
     */
    public static ParseTree unwrapSingleChild(ParseTree tree) {
        ParseTree current = tree;
        while (current.getChildCount() == 1 && current.getChild(0) instanceof ParserRuleContext) {
            current = current.getChild(0);
        }
        return current;
    }
}
