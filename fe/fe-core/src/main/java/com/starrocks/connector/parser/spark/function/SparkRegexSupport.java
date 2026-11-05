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

/**
 * Spark uses Java regular expressions, StarRocks uses RE2. The two agree on the common syntax; this class finds the
 * Java constructs that RE2 does not have (so they are reported instead of failing or matching differently at run
 * time) and translates the replacement string of {@code regexp_replace}.
 */
public final class SparkRegexSupport {
    private static final String REGEX_META = "\\.[]{}()*+-?^$|";

    private SparkRegexSupport() {
    }

    /** The regex feature RE2 lacks, or null when the pattern is accepted. */
    public static String findNonRe2Feature(String regex) {
        int n = regex.length();
        for (int i = 0; i < n; i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = regex.charAt(i + 1);
                if (next >= '1' && next <= '9') {
                    return "back-reference \\" + next;
                }
                if (next == 'k') {
                    return "named back-reference \\k";
                }
                if (next == 'G' || next == 'Z' || next == 'R' || next == 'X' || next == 'h' || next == 'H') {
                    return "escape \\" + next;
                }
                i++;
                continue;
            }
            if (c == '(' && i + 2 < n && regex.charAt(i + 1) == '?') {
                char kind = regex.charAt(i + 2);
                if (kind == '=' || kind == '!') {
                    return "look-ahead (?" + kind;
                }
                if (kind == '>') {
                    return "atomic group (?>";
                }
                if (kind == '<' && i + 3 < n && (regex.charAt(i + 3) == '=' || regex.charAt(i + 3) == '!')) {
                    return "look-behind (?<" + regex.charAt(i + 3);
                }
            }
            if ((c == '*' || c == '+' || c == '?' || c == '}') && i + 1 < n && regex.charAt(i + 1) == '+') {
                return "possessive quantifier " + c + "+";
            }
        }
        return null;
    }

    /** Whether the string has no regex meta character, so splitting by it is splitting by the literal text. */
    public static boolean isPlainLiteral(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (REGEX_META.indexOf(text.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Translates a Java replacement string ({@code $1}, {@code \$}) to the RE2 rewrite string ({@code \1}).
     * Returns null for a replacement that cannot be translated (a named group).
     */
    public static String translateReplacement(String replacement) {
        StringBuilder out = new StringBuilder();
        int n = replacement.length();
        for (int i = 0; i < n; i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    return null;
                }
                char next = replacement.charAt(++i);
                if (next == '\\') {
                    out.append("\\\\");
                } else {
                    out.append(next);
                }
            } else if (c == '$') {
                if (i + 1 >= n) {
                    return null;
                }
                char next = replacement.charAt(i + 1);
                if (next >= '0' && next <= '9') {
                    // Java reads as many digits as still form an existing group; one digit is the common case
                    out.append('\\').append(next);
                    i++;
                } else {
                    return null;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
