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

import com.starrocks.common.Config;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.parser.CaseInsensitiveStream;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseLexer;
import com.starrocks.sql.parser.spark.gen.SparkSqlBaseParser;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the generated Spark SQL lexer and parser. The lexer reads an upper-cased view of the input (the Spark
 * lexer only knows upper case letters) while token texts keep the original case. A SQL text is split at
 * top-level semicolons, and every statement is parsed with SLL prediction first and with full LL prediction
 * when SLL fails.
 */
public class SparkParser {
    private final SparkParseOptions options;

    public SparkParser(SparkParseOptions options) {
        this.options = options;
    }

    public SparkParser(SessionVariable sessionVariable) {
        this(SparkParseOptions.from(sessionVariable));
    }

    /**
     * Parses all non-empty statements of the SQL text.
     *
     * @throws ParsingException on a syntax error, with the line and column of the offending token
     */
    public List<SparkSqlBaseParser.SingleStatementContext> parseStatements(String sql) {
        List<List<Token>> segments = split(tokenize(sql));
        List<SparkSqlBaseParser.SingleStatementContext> result = new ArrayList<>(segments.size());
        for (List<Token> segment : segments) {
            result.add(parseSegment(segment));
        }
        return result;
    }

    private List<Token> tokenize(String sql) {
        SparkSqlBaseLexer lexer = new SparkSqlBaseLexer(new CaseInsensitiveStream(CharStreams.fromString(sql)));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ThrowingErrorListener());
        if (Config.enable_concurrent_parse_optimization) {
            DFA[] lexerDecisionDFA = new DFA[SparkSqlBaseLexer._ATN.getNumberOfDecisions()];
            for (int i = 0; i < lexerDecisionDFA.length; i++) {
                lexerDecisionDFA[i] = new DFA(SparkSqlBaseLexer._ATN.getDecisionState(i), i);
            }
            lexer.setInterpreter(new LexerATNSimulator(lexer, SparkSqlBaseLexer._ATN, lexerDecisionDFA,
                    new PredictionContextCache()));
        }
        List<Token> tokens = new ArrayList<>();
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            tokens.add(token);
        }
        if (lexer.has_unclosed_bracketed_comment) {
            throw new ParsingException("Unclosed bracketed comment", NodePosition.ZERO);
        }
        return tokens;
    }

    /**
     * Splits at SEMICOLON tokens and drops segments without any token on the default channel.
     */
    private static List<List<Token>> split(List<Token> tokens) {
        List<List<Token>> segments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        boolean hasContent = false;
        for (Token token : tokens) {
            if (token.getType() == SparkSqlBaseLexer.SEMICOLON) {
                if (hasContent) {
                    segments.add(current);
                }
                current = new ArrayList<>();
                hasContent = false;
                continue;
            }
            current.add(token);
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                hasContent = true;
            }
        }
        if (hasContent) {
            segments.add(current);
        }
        return segments;
    }

    private SparkSqlBaseParser.SingleStatementContext parseSegment(List<Token> segment) {
        List<Token> withEof = new ArrayList<>(segment);
        Token last = segment.get(segment.size() - 1);
        CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
        eof.setLine(last.getLine());
        eof.setCharPositionInLine(last.getCharPositionInLine() + (last.getText() == null ? 0 : last.getText().length()));
        eof.setStartIndex(last.getStopIndex() + 1);
        eof.setStopIndex(last.getStopIndex());
        withEof.add(eof);

        CommonTokenStream tokenStream = new CommonTokenStream(new ListTokenSource(withEof));
        SparkSqlBaseParser parser = new SparkSqlBaseParser(tokenStream);
        parser.legacy_setops_precedence_enabled = options.isLegacySetopsPrecedence();
        parser.legacy_exponent_literal_as_decimal_enabled = options.isLegacyExponentLiteralAsDecimal();
        parser.SQL_standard_keyword_behavior = options.isSqlStandardKeywordBehavior();
        parser.double_quoted_identifiers = options.isDoubleQuotedIdentifiers();
        parser.removeErrorListeners();
        parser.addErrorListener(new ThrowingErrorListener());
        if (!Config.enable_parser_context_cache || Config.enable_concurrent_parse_optimization) {
            DFA[] decisionDFA = new DFA[parser.getATN().getNumberOfDecisions()];
            for (int i = 0; i < decisionDFA.length; i++) {
                decisionDFA[i] = new DFA(parser.getATN().getDecisionState(i), i);
            }
            parser.setInterpreter(new ParserATNSimulator(parser, parser.getATN(), decisionDFA,
                    new PredictionContextCache()));
        }

        try {
            // first try SLL with a bailing error strategy, which is much faster for valid statements
            parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
            parser.setErrorHandler(new BailErrorStrategy());
            return parser.singleStatement();
        } catch (ParseCancellationException e) {
            // rewind and parse again with full LL prediction, which reports the precise error
            tokenStream.seek(0);
            parser.reset();
            parser.getInterpreter().setPredictionMode(PredictionMode.LL);
            parser.setErrorHandler(new DefaultErrorStrategy());
            return parser.singleStatement();
        }
    }

    private static class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            String detail;
            if (offendingSymbol instanceof Token) {
                Token token = (Token) offendingSymbol;
                detail = token.getType() == Token.EOF ? "Syntax error at end of input"
                        : "Syntax error at or near '" + token.getText() + "'";
            } else {
                detail = "Syntax error: " + msg;
            }
            throw new ParsingException(detail + " (" + msg + ")", new NodePosition(line, charPositionInLine));
        }
    }
}
