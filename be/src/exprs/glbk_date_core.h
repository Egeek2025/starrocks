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

#pragma once

// Native implementation of the glbk date functions (gl_date_delta, gl_date_diff, gl_date_format,
// gl_how_many_day, gl_date_first, gl_date_last). The semantics are exactly those of the Java production
// UDFs in contrib/glbk-hive-udf-compat (com.glbk.starrocks.udf.core.GlDates); that code and
// contrib/glbk-hive-udf-compat/samples/samples.csv are the reference.
//
// This header and glbk_date_core.cpp depend on the C++ standard library only, so the algorithm can be
// built and tested outside the BE (see contrib/glbk-hive-udf-compat/native/). The StarRocks binding
// (columns, NULLs, FunctionContext, error reporting) lives in glbk_functions.{h,cpp}.
//
// Error policy (same as the Java UDFs):
//  - invalid DATA (unparsable date, a set-value that does not exist for that date, result year outside
//    1..9999) -> std::nullopt (SQL NULL);
//  - wrong PARAMETERS (unknown dtype/interval/offset unit, malformed offsets, out-of-range set values,
//    NULL/blank dtype or interval, NULL format where the Java UDF throws, invalid output pattern)
//    -> throw GlParamError with an English message naming the function and the bad value.
// gl_now is not implemented natively: it must use the BE JVM time zone like the Java UDF.

#include <cstdint>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace starrocks::glbk {

class GlParamError : public std::invalid_argument {
public:
    using std::invalid_argument::invalid_argument;
};

// Wall-clock date-time, proleptic Gregorian, no time zone. year is 1..9999 for every value produced by
// parse_input(); intermediate arithmetic may leave that range, which callers turn into NULL.
struct DateTime {
    int32_t year = 1;
    int32_t month = 1;  // 1..12
    int32_t day = 1;    // 1..31
    int32_t hour = 0;   // 0..23
    int32_t minute = 0; // 0..59
    int32_t second = 0; // 0..59
    bool operator==(const DateTime&) const = default;
};

// Strict input parsing: remove every ' ', '-', ':'; the rest must be exactly 8 (yyyyMMdd) or 14
// (yyyyMMddHHmmss) ASCII digits forming a real date/time with year >= 1. Otherwise nullopt.
std::optional<DateTime> parse_input(std::string_view s);

// A compiled output pattern (SimpleDateFormat pattern language, Locale.US, Gregorian, Sunday-first week,
// minimalDaysInFirstWeek=1, rendering the wall-clock fields; time zone letters render as UTC).
class Pattern {
public:
    // Throws GlParamError for an invalid pattern. fn is used in error messages.
    static Pattern compile(std::string_view fn, std::string_view pattern);
    // Output style used by gl_date_delta when no format is given: same shape as the input text.
    static Pattern default_style(std::string_view input);
    std::string format(const DateTime& t) const;

private:
    struct Token;
    std::vector<Token> _tokens;
    friend struct PatternAccess;
};

// Compiled gl_date_delta offsets ('+3', 'day=+3', 'y=+1,m=1,d=1,d=-1', ...). NULL or blank offsets mean
// no change. Throws GlParamError for malformed offsets / unknown units / out-of-range set values.
class Offsets {
public:
    static Offsets compile(std::string_view fn, std::optional<std::string_view> offsets);
    // nullopt when a set-value does not exist for the date or the result year leaves 1..9999.
    std::optional<DateTime> apply(const DateTime& t) const;

private:
    struct Op;
    std::vector<Op> _ops;
};

// Whole-function entry points; args[i] == nullopt means SQL NULL. They implement every arity the Java UDF
// accepts (wrong arities return nullopt, like the Java UDF) and throw GlParamError for wrong parameters.
// The BE binding pre-compiles constant parameters with Pattern/Offsets for speed but must produce exactly
// the same results as these functions.
using Arg = std::optional<std::string_view>;
std::optional<std::string> gl_date_delta(std::span<const Arg> args);
std::optional<std::string> gl_date_diff(std::span<const Arg> args);
std::optional<std::string> gl_date_format(std::span<const Arg> args);
std::optional<std::string> gl_how_many_day(std::span<const Arg> args);
std::optional<std::string> gl_date_first(std::span<const Arg> args);
std::optional<std::string> gl_date_last(std::span<const Arg> args);

} // namespace starrocks::glbk
