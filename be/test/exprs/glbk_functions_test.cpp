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

#include "exprs/glbk_functions.h"

#include <gtest/gtest.h>

#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "base/testutil/assert.h"
#include "column/column_builder.h"
#include "column/column_helper.h"
#include "column/column_viewer.h"
#include "exprs/builtin_functions.h"
#include "exprs/function_context.h"
#include "exprs/glbk_date_core.h"
#include "types/type_descriptor.h"

namespace starrocks {

namespace {

using Cell = std::optional<std::string>;
using Rows = std::vector<Cell>;

// One argument of a call: a constant (possibly NULL) or one value per row.
struct Arg {
    bool is_const = false;
    Cell constant;
    Rows rows;

    static Arg Const(Cell v) { return Arg{true, std::move(v), {}}; }
    static Arg Null() { return Arg{true, std::nullopt, {}}; }
    static Arg Column(Rows r) { return Arg{false, std::nullopt, std::move(r)}; }
};

struct FnUnderTest {
    const char* name;
    // The registered builtin descriptor (id from functions.py), so the test also covers the generated wiring.
    uint64_t fn_id;
};

constexpr FnUnderTest kDelta{"gl_date_delta", 400001};
constexpr FnUnderTest kDiff{"gl_date_diff", 400002};
constexpr FnUnderTest kFormat{"gl_date_format", 400003};
constexpr FnUnderTest kHowManyDay{"gl_how_many_day", 400004};
constexpr FnUnderTest kFirst{"gl_date_first", 400005};
constexpr FnUnderTest kLast{"gl_date_last", 400006};

ColumnPtr make_column(const Arg& arg, size_t num_rows) {
    if (arg.is_const) {
        if (!arg.constant.has_value()) {
            return ColumnHelper::create_const_null_column(num_rows);
        }
        return ColumnHelper::create_const_column<TYPE_VARCHAR>(Slice(*arg.constant), num_rows);
    }
    ColumnBuilder<TYPE_VARCHAR> builder(arg.rows.size());
    for (const auto& cell : arg.rows) {
        if (cell.has_value()) {
            Slice s(*cell);
            builder.append(s);
        } else {
            builder.append_null();
        }
    }
    return builder.build(false);
}

// Evaluates the registered builtin the way VectorizedFunctionCallExpr does. With `with_prepare` the constant
// arguments are handed to the prepare function (constant pre-compilation); without it the function runs on its
// per-row path only.
StatusOr<Rows> eval(const FnUnderTest& fn, const std::vector<Arg>& args, bool with_prepare) {
    const FunctionDescriptor* desc = BuiltinFunctions::find_builtin_function(fn.fn_id);
    EXPECT_NE(nullptr, desc) << fn.name;
    EXPECT_EQ(fn.name, desc->name);

    size_t num_rows = 1;
    for (const auto& a : args) {
        if (!a.is_const) num_rows = a.rows.size();
    }
    Columns columns;
    Columns constants;
    std::vector<FunctionContext::TypeDesc> arg_types;
    for (const auto& a : args) {
        columns.emplace_back(make_column(a, num_rows));
        constants.emplace_back(a.is_const ? make_column(a, 1) : nullptr);
        arg_types.emplace_back(TYPE_VARCHAR_DESC);
    }
    std::unique_ptr<FunctionContext> ctx(
            FunctionContext::create_test_context(std::move(arg_types), TypeDescriptor(TYPE_VARCHAR_DESC)));
    ctx->set_constant_columns(std::move(constants));

    const bool has_prepare = with_prepare && desc->prepare_function != nullptr;
    if (has_prepare) {
        RETURN_IF_ERROR(desc->prepare_function(ctx.get(), FunctionContext::FRAGMENT_LOCAL));
    }
    auto result = desc->scalar_function(ctx.get(), columns);
    if (has_prepare && desc->close_function != nullptr) {
        EXPECT_TRUE(desc->close_function(ctx.get(), FunctionContext::FRAGMENT_LOCAL).ok());
    }
    RETURN_IF_ERROR(result);

    ColumnViewer<TYPE_VARCHAR> viewer(result.value());
    Rows out;
    for (size_t i = 0; i < num_rows; ++i) {
        if (viewer.is_null(i)) {
            out.emplace_back(std::nullopt);
        } else {
            out.emplace_back(viewer.value(i).to_string());
        }
    }
    return out;
}

// Runs the call with and without prepare and the core entry point directly, and requires the same result
// (or the same error text) from all of them.
using CoreFn = std::optional<std::string> (*)(std::span<const glbk::Arg>);

void expect_same(const FnUnderTest& fn, CoreFn core, const std::vector<Arg>& args, const Rows& expected) {
    auto fast = eval(fn, args, true);
    auto slow = eval(fn, args, false);
    ASSERT_OK(fast.status());
    ASSERT_OK(slow.status());
    ASSERT_EQ(expected.size(), fast.value().size());
    for (size_t row = 0; row < expected.size(); ++row) {
        std::vector<glbk::Arg> core_args;
        for (const auto& a : args) {
            const Cell& cell = a.is_const ? a.constant : a.rows[row];
            core_args.emplace_back(cell.has_value() ? glbk::Arg(std::string_view(*cell)) : glbk::Arg());
        }
        std::optional<std::string> from_core = core(std::span<const glbk::Arg>(core_args));
        EXPECT_EQ(expected[row], from_core) << fn.name << " core, row " << row;
        EXPECT_EQ(expected[row], fast.value()[row]) << fn.name << " prepared, row " << row;
        EXPECT_EQ(expected[row], slow.value()[row]) << fn.name << " per-row, row " << row;
    }
}

// An error must be the same (and name the function) with and without prepare.
void expect_error(const FnUnderTest& fn, const std::vector<Arg>& args, const std::string& message_part) {
    for (bool with_prepare : {true, false}) {
        auto result = eval(fn, args, with_prepare);
        ASSERT_FALSE(result.ok()) << fn.name << " with_prepare=" << with_prepare;
        EXPECT_NE(std::string::npos, result.status().message().find(fn.name)) << result.status().message();
        EXPECT_NE(std::string::npos, result.status().message().find(message_part)) << result.status().message();
    }
}

const Rows kDates = {Cell("20190417"),
                     Cell("2019-04-17 03:04:05"),
                     Cell("20190417030405"),
                     Cell("2019-1-7"),
                     Cell("20191332"),
                     Cell(""),
                     Cell("abc"),
                     Cell("99991231"),
                     Cell("20200229"),
                     std::nullopt};

} // namespace

TEST(GlbkFunctionsTest, registered_builtins) {
    for (const auto* fn : {&kDelta, &kDiff, &kFormat, &kHowManyDay, &kFirst, &kLast}) {
        const FunctionDescriptor* desc = BuiltinFunctions::find_builtin_function(fn->fn_id);
        ASSERT_NE(nullptr, desc) << fn->name;
        EXPECT_EQ(fn->name, desc->name);
        EXPECT_EQ(1, desc->args_nums); // variadic (VARCHAR, ...)
    }
    // Only the functions that pre-compile constants have prepare/close.
    EXPECT_NE(nullptr, BuiltinFunctions::find_builtin_function(kDelta.fn_id)->prepare_function);
    EXPECT_NE(nullptr, BuiltinFunctions::find_builtin_function(kFormat.fn_id)->close_function);
    EXPECT_EQ(nullptr, BuiltinFunctions::find_builtin_function(kDiff.fn_id)->prepare_function);
}

TEST(GlbkFunctionsTest, format_constant_pattern_matches_per_row) {
    const std::vector<Cell> formats = {std::nullopt,
                                       Cell("yyyy-MM-dd"),
                                       Cell("yyyyMMddHHmmss"),
                                       Cell("yyyy-MM-dd HH:mm:ss"),
                                       Cell("EEEE, MMMM d, yyyy"),
                                       Cell("hh:mm a"),
                                       Cell("yyyy-ww"),
                                       Cell(""),
                                       Cell("yyyy'年'MM'月'")};
    for (const auto& format : formats) {
        if (!format.has_value()) continue; // NULL format is an error, tested below
        Rows expected;
        for (const auto& date : kDates) {
            std::vector<glbk::Arg> a = {date.has_value() ? glbk::Arg(std::string_view(*date)) : glbk::Arg(),
                                        glbk::Arg(std::string_view(*format))};
            expected.emplace_back(glbk::gl_date_format(a));
        }
        expect_same(kFormat, glbk::gl_date_format, {Arg::Column(kDates), Arg::Const(format)}, expected);
        // the same format given per row
        Rows formats_per_row(kDates.size(), format);
        expect_same(kFormat, glbk::gl_date_format, {Arg::Column(kDates), Arg::Column(formats_per_row)}, expected);
    }
    // one-argument form uses yyyyMMdd
    Rows expected;
    for (const auto& date : kDates) {
        std::vector<glbk::Arg> a = {date.has_value() ? glbk::Arg(std::string_view(*date)) : glbk::Arg()};
        expected.emplace_back(glbk::gl_date_format(a));
    }
    expect_same(kFormat, glbk::gl_date_format, {Arg::Column(kDates)}, expected);
    EXPECT_EQ(Cell("20190417"), expected[0]);
    EXPECT_EQ(Cell("20190417"), expected[1]);
}

TEST(GlbkFunctionsTest, format_known_values) {
    auto r = eval(kFormat, {Arg::Column({Cell("20190417"), Cell("2019-1-7"), std::nullopt}), Arg::Const("yyyy-MM-dd")},
                  true);
    ASSERT_OK(r.status());
    EXPECT_EQ((Rows{Cell("2019-04-17"), std::nullopt, std::nullopt}), r.value());
}

TEST(GlbkFunctionsTest, format_errors) {
    // NULL format raises an error: NULL arguments must reach the core.
    expect_error(kFormat, {Arg::Column({Cell("20190417")}), Arg::Null()}, "format must not be null");
    expect_error(kFormat, {Arg::Const("20190417"), Arg::Null()}, "format must not be null");
    // invalid constant pattern
    expect_error(kFormat, {Arg::Column({Cell("20190417")}), Arg::Const("bad'")}, "");
    expect_error(kFormat, {Arg::Column({Cell("20190417")}), Arg::Const("yyyy-MM-dd bbb")}, "");
    // invalid per-row pattern
    expect_error(kFormat,
                 {Arg::Column({Cell("20190417"), Cell("20190418")}), Arg::Column({Cell("yyyy"), Cell("bad'")})}, "");
    // data NULL wins over the parameter check, with and without a pre-compiled constant
    for (bool with_prepare : {true, false}) {
        auto r = eval(kFormat, {Arg::Column({std::nullopt, std::nullopt}), Arg::Const("bad'")}, with_prepare);
        ASSERT_OK(r.status());
        EXPECT_EQ((Rows{std::nullopt, std::nullopt}), r.value());
        r = eval(kFormat, {Arg::Null(), Arg::Null()}, with_prepare);
        ASSERT_OK(r.status());
        EXPECT_EQ(Rows{std::nullopt}, r.value());
    }
}

TEST(GlbkFunctionsTest, delta_constant_offsets_match_per_row) {
    const std::vector<Cell> offsets = {std::nullopt,
                                       Cell(""),
                                       Cell("   "),
                                       Cell("+1"),
                                       Cell("-1"),
                                       Cell("5"),
                                       Cell("y=+1,m=1,d=1,d=-1"),
                                       Cell("day=+3"),
                                       Cell(" day = +3 "),
                                       Cell("h=13"),
                                       Cell("n=+90"),
                                       Cell("m=2"),
                                       Cell("d=31"),
                                       Cell("m=+1")};
    const std::vector<Cell> formats = {
            Cell("yyyyMMdd"), Cell("yyyy-MM-dd"), Cell("yyyy-MM-dd HH:mm"), Cell("EEE, d MMM yyyy"), Cell(""),
            Cell(" "),        Cell("\t \n"),      Cell("yyyy'年'MM'月'dd"), Cell("\xE3\x80\x80")};
    for (const auto& offs : offsets) {
        Rows expected2;
        for (const auto& date : kDates) {
            std::vector<glbk::Arg> a = {date.has_value() ? glbk::Arg(std::string_view(*date)) : glbk::Arg(),
                                        offs.has_value() ? glbk::Arg(std::string_view(*offs)) : glbk::Arg()};
            expected2.emplace_back(glbk::gl_date_delta(a));
        }
        expect_same(kDelta, glbk::gl_date_delta, {Arg::Column(kDates), Arg::Const(offs)}, expected2);
        expect_same(kDelta, glbk::gl_date_delta, {Arg::Column(kDates), Arg::Column(Rows(kDates.size(), offs))},
                    expected2);

        for (const auto& format : formats) {
            Rows expected3;
            for (const auto& date : kDates) {
                std::vector<glbk::Arg> a = {date.has_value() ? glbk::Arg(std::string_view(*date)) : glbk::Arg(),
                                            offs.has_value() ? glbk::Arg(std::string_view(*offs)) : glbk::Arg(),
                                            glbk::Arg(std::string_view(*format))};
                expected3.emplace_back(glbk::gl_date_delta(a));
            }
            expect_same(kDelta, glbk::gl_date_delta, {Arg::Column(kDates), Arg::Const(offs), Arg::Const(format)},
                        expected3);
            // constant offsets, per-row format
            expect_same(kDelta, glbk::gl_date_delta,
                        {Arg::Column(kDates), Arg::Const(offs), Arg::Column(Rows(kDates.size(), format))}, expected3);
        }
    }
}

TEST(GlbkFunctionsTest, delta_known_values) {
    auto r = eval(
            kDelta,
            {Arg::Column({Cell("20190417"), Cell("2019-04-17 03:04:05"), Cell("abc"), std::nullopt}), Arg::Const("+1")},
            true);
    ASSERT_OK(r.status());
    EXPECT_EQ((Rows{Cell("20190418"), Cell("2019-04-18 03:04:05"), std::nullopt, std::nullopt}), r.value());

    // one argument returns the input unchanged, even when it is not a valid date
    r = eval(kDelta, {Arg::Column({Cell("2019-1-7"), std::nullopt})}, true);
    ASSERT_OK(r.status());
    EXPECT_EQ((Rows{Cell("2019-1-7"), std::nullopt}), r.value());

    // year overflow gives NULL
    r = eval(kDelta, {Arg::Column({Cell("99991231")}), Arg::Const("+1")}, true);
    ASSERT_OK(r.status());
    EXPECT_EQ(Rows{std::nullopt}, r.value());
}

TEST(GlbkFunctionsTest, delta_errors) {
    expect_error(kDelta, {Arg::Column({Cell("20190417")}), Arg::Const("x=1")}, "x");
    expect_error(kDelta, {Arg::Column({Cell("20190417")}), Arg::Const("day=abc")}, "");
    expect_error(kDelta, {Arg::Column({Cell("20190417")}), Arg::Const("m=13")}, "");
    expect_error(kDelta, {Arg::Column({Cell("20190417")}), Arg::Const("+1"), Arg::Null()}, "format must not be null");
    expect_error(kDelta, {Arg::Column({Cell("20190417")}), Arg::Const("+1"), Arg::Const("bad'")}, "");
    // per-row offsets: the bad row fails the whole query
    expect_error(kDelta, {Arg::Column({Cell("20190417"), Cell("20190418")}), Arg::Column({Cell("+1"), Cell("x=1")})},
                 "");
    // the offsets error comes first when the format is also wrong
    expect_error(kDelta, {Arg::Column({Cell("20190417")}), Arg::Const("x=1"), Arg::Const("bad'")}, "offset");
    // NULL date wins over bad parameters
    for (bool with_prepare : {true, false}) {
        auto r = eval(kDelta, {Arg::Column({std::nullopt}), Arg::Const("x=1"), Arg::Null()}, with_prepare);
        ASSERT_OK(r.status());
        EXPECT_EQ(Rows{std::nullopt}, r.value());
    }
}

TEST(GlbkFunctionsTest, diff_values_and_errors) {
    const Rows a = {Cell("20190417"), Cell("2019-04-01"), Cell("20190101"),
                    Cell("abc"),      std::nullopt,       Cell("20190417")};
    const Rows b = {Cell("20190416"), Cell("2019-03-31"), Cell("20181229"),
                    Cell("20190416"), Cell("20190417"),   std::nullopt};
    for (const char* interval : {"day", "d", "week", "w", "month", "m", "quarter", "q", "year", "y", "hour", "h",
                                 "minute", "n", "second", "s"}) {
        Rows expected;
        for (size_t i = 0; i < a.size(); ++i) {
            std::vector<glbk::Arg> args = {a[i] ? glbk::Arg(std::string_view(*a[i])) : glbk::Arg(),
                                           b[i] ? glbk::Arg(std::string_view(*b[i])) : glbk::Arg(),
                                           glbk::Arg(std::string_view(interval))};
            expected.emplace_back(glbk::gl_date_diff(args));
        }
        expect_same(kDiff, glbk::gl_date_diff, {Arg::Column(a), Arg::Column(b), Arg::Const(interval)}, expected);
        expect_same(kDiff, glbk::gl_date_diff,
                    {Arg::Column(a), Arg::Column(b), Arg::Column(Rows(a.size(), Cell(interval)))}, expected);
    }
    auto r = eval(
            kDiff,
            {Arg::Column({Cell("20180101"), Cell("2018-02-01")}), Arg::Column({Cell("20180201"), Cell("2018-01-01")})},
            true);
    ASSERT_OK(r.status());
    EXPECT_EQ((Rows{Cell("-31"), Cell("31")}), r.value());

    expect_error(kDiff, {Arg::Const("20190417"), Arg::Const("20190416"), Arg::Const("xx")}, "xx");
    expect_error(kDiff, {Arg::Const("20190417"), Arg::Const("20190416"), Arg::Const("DAY")}, "DAY");
    expect_error(kDiff, {Arg::Const("20190417"), Arg::Const("20190416"), Arg::Null()}, "interval");
    expect_error(kDiff, {Arg::Column({Cell("20190417")}), Arg::Column({Cell("20190416")}), Arg::Column({std::nullopt})},
                 "interval");
    // NULL dates win over a bad interval
    r = eval(kDiff, {Arg::Null(), Arg::Const("20190417"), Arg::Const("xx")}, true);
    ASSERT_OK(r.status());
    EXPECT_EQ(Rows{std::nullopt}, r.value());
}

TEST(GlbkFunctionsTest, dtype_functions) {
    struct Case {
        FnUnderTest fn;
        CoreFn core;
    };
    const Case cases[] = {
            {kHowManyDay, glbk::gl_how_many_day}, {kFirst, glbk::gl_date_first}, {kLast, glbk::gl_date_last}};
    for (const auto& c : cases) {
        for (const char* dtype : {"year", "y", "quarter", "q", "month", "m", "week", "w"}) {
            Rows expected;
            for (const auto& date : kDates) {
                std::vector<glbk::Arg> args = {date ? glbk::Arg(std::string_view(*date)) : glbk::Arg(),
                                               glbk::Arg(std::string_view(dtype))};
                expected.emplace_back(c.core(args));
            }
            expect_same(c.fn, c.core, {Arg::Column(kDates), Arg::Const(dtype)}, expected);
            expect_same(c.fn, c.core, {Arg::Column(kDates), Arg::Column(Rows(kDates.size(), Cell(dtype)))}, expected);
        }
        expect_error(c.fn, {Arg::Const("20190417"), Arg::Const("day")}, "day");
        expect_error(c.fn, {Arg::Const("20190417"), Arg::Const("Month")}, "Month");
        expect_error(c.fn, {Arg::Const("20190417"), Arg::Const("")}, "dtype");
        expect_error(c.fn, {Arg::Const("20190417"), Arg::Null()}, "dtype");
        expect_error(c.fn, {Arg::Column({Cell("20190417")}), Arg::Column({std::nullopt})}, "dtype");
        auto r = eval(c.fn, {Arg::Null(), Arg::Const("year")}, true);
        ASSERT_OK(r.status());
        EXPECT_EQ(Rows{std::nullopt}, r.value());
    }

    auto r = eval(kHowManyDay, {Arg::Column({Cell("2019-04-17")}), Arg::Const("year")}, true);
    ASSERT_OK(r.status());
    EXPECT_EQ(Rows{Cell("107")}, r.value());
    r = eval(kFirst, {Arg::Column({Cell("2019-04-17 12:00:00")}), Arg::Const("week")}, true);
    ASSERT_OK(r.status());
    EXPECT_EQ(Rows{Cell("20190414")}, r.value());
    r = eval(kLast, {Arg::Column({Cell("20190215")}), Arg::Const("m")}, true);
    ASSERT_OK(r.status());
    EXPECT_EQ(Rows{Cell("20190228")}, r.value());
    r = eval(kLast, {Arg::Column({Cell("99991231")}), Arg::Const("week")}, true);
    ASSERT_OK(r.status());
    EXPECT_EQ(Rows{std::nullopt}, r.value());
}

TEST(GlbkFunctionsTest, unsupported_arity_returns_null) {
    // Same as the Java UDFs: a wrong argument count is NULL, not an error.
    const std::vector<std::pair<FnUnderTest, std::vector<Arg>>> cases = {
            {kDelta, {Arg::Const("20190417"), Arg::Const("+1"), Arg::Const("yyyyMMdd"), Arg::Const("extra")}},
            {kDiff, {Arg::Const("20190417")}},
            {kDiff, {Arg::Const("20190417"), Arg::Const("20190416"), Arg::Const("day"), Arg::Const("x")}},
            {kFormat, {Arg::Const("20190417"), Arg::Const("yyyy"), Arg::Const("x")}},
            {kHowManyDay, {Arg::Const("20190417")}},
            {kHowManyDay, {Arg::Const("20190417"), Arg::Const("year"), Arg::Const("x")}},
            {kFirst, {Arg::Const("20190417")}},
            {kLast, {Arg::Const("20190417"), Arg::Const("year"), Arg::Const("x")}}};
    for (const auto& [fn, args] : cases) {
        for (bool with_prepare : {true, false}) {
            auto r = eval(fn, args, with_prepare);
            ASSERT_OK(r.status());
            EXPECT_EQ(Rows{std::nullopt}, r.value()) << fn.name;
        }
    }
}

TEST(GlbkFunctionsTest, all_constant_arguments_give_constant_result) {
    const FunctionDescriptor* desc = BuiltinFunctions::find_builtin_function(kDelta.fn_id);
    std::vector<FunctionContext::TypeDesc> arg_types(2, TYPE_VARCHAR_DESC);
    std::unique_ptr<FunctionContext> ctx(
            FunctionContext::create_test_context(std::move(arg_types), TypeDescriptor(TYPE_VARCHAR_DESC)));
    ctx->set_constant_columns({ColumnHelper::create_const_column<TYPE_VARCHAR>(Slice("20190417"), 1),
                               ColumnHelper::create_const_column<TYPE_VARCHAR>(Slice("+1"), 1)});
    ASSERT_OK(desc->prepare_function(ctx.get(), FunctionContext::FRAGMENT_LOCAL));
    Columns columns = {ColumnHelper::create_const_column<TYPE_VARCHAR>(Slice("20190417"), 5),
                       ColumnHelper::create_const_column<TYPE_VARCHAR>(Slice("+1"), 5)};
    auto result = desc->scalar_function(ctx.get(), columns);
    ASSERT_OK(result.status());
    ASSERT_TRUE(result.value()->is_constant());
    ColumnViewer<TYPE_VARCHAR> viewer(result.value());
    EXPECT_EQ("20190418", viewer.value(0).to_string());
    ASSERT_OK(desc->close_function(ctx.get(), FunctionContext::FRAGMENT_LOCAL));
}

} // namespace starrocks
