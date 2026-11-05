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

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a Spark (Java {@code DateTimeFormatter}) datetime pattern such as {@code yyyy-MM-dd HH:mm:ss.SSS} to
 * the MySQL style format of StarRocks ({@code %Y-%m-%d %H:%i:%s.%f}).
 *
 * <p>The translation never guesses: a pattern letter without an exact equivalent raises
 * {@link UnsupportedPatternException}, so the caller reports it instead of returning a different string.
 *
 * <p>Formatting and parsing differ in two places. A fraction of seconds is {@code %f} (always 6 digits) when
 * formatting, so a fraction of n digits becomes a separate {@link Piece} that the caller cuts down to n digits;
 * when parsing {@code %f} reads any number of digits. An unpadded minute or second ({@code m}, {@code s}) cannot be
 * produced by MySQL formats, but both parse one or two digits.
 */
public final class JavaDateTimePatternTranslator {
    private JavaDateTimePatternTranslator() {
    }

    /** The pattern uses a letter or a construct that has no exact StarRocks equivalent. */
    public static class UnsupportedPatternException extends RuntimeException {
        public UnsupportedPatternException(String message) {
            super(message);
        }
    }

    /** A part of a translated format: a MySQL format chunk, or a fraction of seconds with the digits to keep. */
    public static final class Piece {
        private final String mysql;
        private final int fractionDigits;

        private Piece(String mysql, int fractionDigits) {
            this.mysql = mysql;
            this.fractionDigits = fractionDigits;
        }

        public boolean isFraction() {
            return fractionDigits > 0;
        }

        public String getMysql() {
            return mysql;
        }

        public int getFractionDigits() {
            return fractionDigits;
        }
    }

    /**
     * Translates a pattern for parsing (str_to_date, unix_timestamp(str, fmt), ...). The result is a single MySQL
     * format string.
     */
    public static String toMysqlParseFormat(String pattern) {
        StringBuilder out = new StringBuilder();
        for (Piece piece : translate(pattern, false)) {
            out.append(piece.getMysql());
        }
        return out.toString();
    }

    /**
     * Translates a pattern for formatting (date_format, from_unixtime(n, fmt)). A fraction of seconds is a piece of
     * its own.
     */
    public static List<Piece> toMysqlFormatPieces(String pattern) {
        return translate(pattern, true);
    }

    /** The MySQL format of a pattern without a fraction of seconds; null when the pattern has one. */
    public static String toMysqlFormatIfPlain(String pattern) {
        StringBuilder out = new StringBuilder();
        for (Piece piece : translate(pattern, true)) {
            if (piece.isFraction()) {
                return null;
            }
            out.append(piece.getMysql());
        }
        return out.toString();
    }

    private static List<Piece> translate(String pattern, boolean forFormatting) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int n = pattern.length();
        int i = 0;
        while (i < n) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                int end = i + 1;
                if (end < n && pattern.charAt(end) == '\'') {
                    // '' is a single quote
                    chunk.append('\'');
                    i = end + 1;
                    continue;
                }
                StringBuilder literal = new StringBuilder();
                boolean closed = false;
                while (end < n) {
                    char q = pattern.charAt(end);
                    if (q == '\'') {
                        if (end + 1 < n && pattern.charAt(end + 1) == '\'') {
                            literal.append('\'');
                            end += 2;
                            continue;
                        }
                        closed = true;
                        end++;
                        break;
                    }
                    literal.append(q);
                    end++;
                }
                if (!closed) {
                    throw new UnsupportedPatternException("Unterminated quote in datetime pattern '" + pattern + "'");
                }
                appendLiteral(chunk, literal.toString());
                i = end;
                continue;
            }
            if (isLetter(c)) {
                int j = i;
                while (j < n && pattern.charAt(j) == c) {
                    j++;
                }
                int count = j - i;
                if (c == 'S') {
                    if (forFormatting) {
                        if (chunk.length() > 0) {
                            pieces.add(new Piece(chunk.toString(), 0));
                            chunk.setLength(0);
                        }
                        pieces.add(new Piece("%f", count));
                    } else {
                        chunk.append("%f");
                    }
                } else {
                    chunk.append(letter(c, count, forFormatting, pattern));
                }
                i = j;
                continue;
            }
            if (c == '[' || c == ']' || c == '{' || c == '}' || c == '#') {
                throw new UnsupportedPatternException("Unsupported character '" + c + "' in datetime pattern '"
                        + pattern + "'");
            }
            appendLiteral(chunk, String.valueOf(c));
            i++;
        }
        if (chunk.length() > 0 || pieces.isEmpty()) {
            pieces.add(new Piece(chunk.toString(), 0));
        }
        return pieces;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static void appendLiteral(StringBuilder out, String literal) {
        for (int k = 0; k < literal.length(); k++) {
            char c = literal.charAt(k);
            out.append(c);
            if (c == '%') {
                out.append('%');
            }
        }
    }

    private static String letter(char c, int count, boolean forFormatting, String pattern) {
        switch (c) {
            case 'y':
                return count == 2 ? "%y" : "%Y";
            case 'M':
            case 'L':
                if (count == 1) {
                    return "%c";
                } else if (count == 2) {
                    return "%m";
                } else if (count == 3) {
                    return "%b";
                } else if (count == 4) {
                    return "%M";
                }
                break;
            case 'd':
                if (count == 1) {
                    return "%e";
                } else if (count == 2) {
                    return "%d";
                }
                break;
            case 'H':
                if (count == 1) {
                    return "%k";
                } else if (count == 2) {
                    return "%H";
                }
                break;
            case 'h':
                if (count == 1) {
                    return "%l";
                } else if (count == 2) {
                    return "%h";
                }
                break;
            case 'm':
                if (count == 2 || (count == 1 && !forFormatting)) {
                    return "%i";
                }
                break;
            case 's':
                if (count == 2 || (count == 1 && !forFormatting)) {
                    return "%s";
                }
                break;
            case 'E':
                if (count <= 3) {
                    return "%a";
                } else if (count == 4) {
                    return "%W";
                }
                break;
            case 'a':
                if (count == 1) {
                    return "%p";
                }
                break;
            case 'D':
                if (count == 3) {
                    return "%j";
                }
                break;
            default:
                break;
        }
        throw new UnsupportedPatternException("Unsupported pattern letter '" + repeat(c, count)
                + "' in datetime pattern '" + pattern + "'; it has no exact StarRocks equivalent");
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
