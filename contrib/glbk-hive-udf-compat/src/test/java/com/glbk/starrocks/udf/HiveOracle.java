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

package com.glbk.starrocks.udf;

import com.glbk.hive.udf.date.gl_date_delta;
import com.glbk.hive.udf.date.gl_date_diff;
import com.glbk.hive.udf.date.gl_date_first;
import com.glbk.hive.udf.date.gl_date_format;
import com.glbk.hive.udf.date.gl_date_last;
import com.glbk.hive.udf.date.gl_how_many_day;
import com.glbk.hive.udf.date.gl_now;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.hadoop.io.Text;

/** Test support: runs the ORIGINAL Hive UDF classes and the corrected StarRocks classes on the same inputs. */
final class HiveOracle {
    static final String[] FUNCTIONS = {"gl_date_delta", "gl_date_diff", "gl_date_format", "gl_now",
        "gl_how_many_day", "gl_date_first", "gl_date_last"};

    private HiveOracle() {
    }

    /** Result of one call: a value, SQL NULL, or an exception. */
    static final class Outcome {
        enum Kind { VALUE, NULL, THROW }

        final Kind kind;
        final String value;

        private Outcome(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        static Outcome of(Supplier<String> call) {
            try {
                String v = call.get();
                return v == null ? new Outcome(Kind.NULL, null) : new Outcome(Kind.VALUE, v);
            } catch (RuntimeException e) {
                return new Outcome(Kind.THROW, null);
            }
        }

        /** Marker used in the sample CSV. */
        String marker() {
            return kind == Kind.VALUE ? value : kind == Kind.NULL ? "NULL" : "ERROR";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Outcome && ((Outcome) o).kind == kind && Objects.equals(((Outcome) o).value, value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, value);
        }

        @Override
        public String toString() {
            return kind == Kind.VALUE ? "V:" + value : kind.name();
        }
    }

    private static Text[] texts(String[] args) {
        Text[] r = new Text[args.length];
        for (int i = 0; i < args.length; i++) {
            r[i] = args[i] == null ? null : new Text(args[i]);
        }
        return r;
    }

    private static String str(Text t) {
        return t == null ? null : t.toString();
    }

    /** The original Hive UDF. Callers pin Locale.US and the JVM time zone. */
    static Outcome hive(String fn, String[] args) {
        return Outcome.of(() -> {
            switch (fn) {
                case "gl_date_delta":
                    return str(new gl_date_delta().evaluate(texts(args)));
                case "gl_date_diff":
                    return str(new gl_date_diff().evaluate(texts(args)));
                case "gl_date_format":
                    return str(new gl_date_format().evaluate(texts(args)));
                case "gl_now":
                    return str(new gl_now().evaluate(texts(args)));
                case "gl_how_many_day":
                    return str(new gl_how_many_day().evaluate(texts(args)));
                case "gl_date_first":
                    return str(new gl_date_first().evaluate(texts(args)));
                case "gl_date_last":
                    return str(new gl_date_last().evaluate(texts(args)));
                default:
                    throw new IllegalArgumentException(fn);
            }
        });
    }

    /** The corrected StarRocks UDF. */
    static Outcome fixed(String fn, String[] args) {
        return Outcome.of(() -> {
            switch (fn) {
                case "gl_date_delta":
                    return new GlDateDelta().evaluate(args);
                case "gl_date_diff":
                    return new GlDateDiff().evaluate(args);
                case "gl_date_format":
                    return new GlDateFormat().evaluate(args);
                case "gl_now":
                    return new GlNow().evaluate(args);
                case "gl_how_many_day":
                    return new GlHowManyDay().evaluate(args);
                case "gl_date_first":
                    return new GlDateFirst().evaluate(args);
                case "gl_date_last":
                    return new GlDateLast().evaluate(args);
                default:
                    throw new IllegalArgumentException(fn);
            }
        });
    }

    /** DateUtils.parseByFormat prints a stack trace for every unparsable input; hide it. */
    static PrintStream silenceStderr() {
        PrintStream saved = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
            }
        }));
        return saved;
    }
}
