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

#include <cstddef>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "column/column_builder.h"
#include "column/column_helper.h"
#include "column/column_viewer.h"
#include "exprs/glbk_date_core.h"

namespace starrocks {

namespace {

constexpr std::string_view kDateDelta = "gl_date_delta";
constexpr std::string_view kDateFormat = "gl_date_format";
constexpr std::string_view kDefaultFormat = "yyyyMMdd";

// Pre-compiled constant parameters. Immutable after prepare, shared by all driver threads of the fragment.
struct GlbkFormatState {
    std::unique_ptr<glbk::Pattern> pattern; // empty: use the per-row core entry point
};

struct GlbkDeltaState {
    std::unique_ptr<glbk::Offsets> offsets; // empty: use the per-row core entry point
    std::unique_ptr<glbk::Pattern> pattern; // empty with offsets set: default style derived from each input
};

std::string_view to_view(const Slice& s) {
    return {s.get_data(), s.get_size()};
}

// Calls `core` for every row with the arguments as std::optional<string_view> (nullopt == SQL NULL).
// A GlParamError from the core fails the query. Always returns a nullable (or const NULL) column.
template <typename Core>
StatusOr<ColumnPtr> eval_per_row(const Columns& columns, Core core) {
    if (columns.empty()) {
        return Status::InvalidArgument("glbk function requires at least one argument");
    }
    const size_t num_args = columns.size();
    std::vector<ColumnViewer<TYPE_VARCHAR>> viewers;
    viewers.reserve(num_args);
    for (const auto& column : columns) {
        viewers.emplace_back(column);
    }

    const bool all_const = ColumnHelper::is_all_const(columns);
    const size_t num_rows = all_const ? 1 : columns[0]->size();
    ColumnBuilder<TYPE_VARCHAR> builder(num_rows);
    std::vector<glbk::Arg> args(num_args);

    for (size_t row = 0; row < num_rows; ++row) {
        for (size_t i = 0; i < num_args; ++i) {
            if (viewers[i].is_null(row)) {
                args[i] = std::nullopt;
            } else {
                args[i] = to_view(viewers[i].value(row));
            }
        }
        try {
            std::optional<std::string> result = core(std::span<const glbk::Arg>(args));
            if (result.has_value()) {
                Slice value(result->data(), result->size());
                builder.append(value);
            } else {
                builder.append_null();
            }
        } catch (const glbk::GlParamError& e) {
            return Status::InvalidArgument(e.what());
        }
    }
    return builder.build(all_const);
}

// Java's Character.isWhitespace restricted to ASCII: \t \n \v \f \r, 0x1C..0x1F and ' '.
bool is_ascii_whitespace(unsigned char c) {
    return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20);
}

enum class FormatKind { BLANK, NOT_BLANK, UNSURE };

// Classifies a constant output format of gl_date_delta the way the core does (blank format == default style).
// A format with non-ASCII bytes and no ASCII non-whitespace byte might be blank under Unicode rules: UNSURE.
FormatKind classify_format(std::string_view fmt) {
    bool has_non_ascii = false;
    for (unsigned char c : fmt) {
        if (c >= 0x80) {
            has_non_ascii = true;
        } else if (!is_ascii_whitespace(c)) {
            return FormatKind::NOT_BLANK;
        }
    }
    return has_non_ascii ? FormatKind::UNSURE : FormatKind::BLANK;
}

template <typename State>
State* get_state(FunctionContext* context) {
    return reinterpret_cast<State*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
}

} // namespace

// ---------------------------------------------------------------------------------------------- gl_date_format

Status GlbkFunctions::gl_date_format_prepare(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }
    auto* state = new GlbkFormatState();
    context->set_function_state(scope, state);

    try {
        if (context->get_num_args() == 1) {
            state->pattern = std::make_unique<glbk::Pattern>(glbk::Pattern::compile(kDateFormat, kDefaultFormat));
        } else if (context->get_num_args() == 2 && context->is_notnull_constant_column(1)) {
            auto format = ColumnHelper::get_const_value<TYPE_VARCHAR>(context->get_constant_column(1));
            state->pattern = std::make_unique<glbk::Pattern>(glbk::Pattern::compile(kDateFormat, to_view(format)));
        }
    } catch (const glbk::GlParamError&) {
        // Invalid constant pattern: keep the state empty so that the per-row core entry point reports the error
        // for the first row that reaches parameter validation (a NULL date still returns NULL, as in the core).
        state->pattern.reset();
    }
    return Status::OK();
}

Status GlbkFunctions::gl_date_format_close(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::FRAGMENT_LOCAL) {
        delete get_state<GlbkFormatState>(context);
        context->set_function_state(scope, nullptr);
    }
    return Status::OK();
}

StatusOr<ColumnPtr> GlbkFunctions::gl_date_format(FunctionContext* context, const Columns& columns) {
    const auto* state = get_state<GlbkFormatState>(context);
    if (state != nullptr && state->pattern != nullptr && !columns.empty()) {
        // Fast path: constant, valid format. Only the date column is read.
        const glbk::Pattern& pattern = *state->pattern;
        ColumnViewer<TYPE_VARCHAR> dates(columns[0]);
        const bool all_const = ColumnHelper::is_all_const(columns);
        const size_t num_rows = all_const ? 1 : columns[0]->size();
        ColumnBuilder<TYPE_VARCHAR> builder(num_rows);
        for (size_t row = 0; row < num_rows; ++row) {
            if (dates.is_null(row)) {
                builder.append_null();
                continue;
            }
            auto parsed = glbk::parse_input(to_view(dates.value(row)));
            if (!parsed.has_value()) {
                builder.append_null();
                continue;
            }
            std::string text = pattern.format(*parsed);
            Slice value(text.data(), text.size());
            builder.append(value);
        }
        return builder.build(all_const);
    }
    return eval_per_row(columns, [](std::span<const glbk::Arg> args) { return glbk::gl_date_format(args); });
}

// ----------------------------------------------------------------------------------------------- gl_date_delta

Status GlbkFunctions::gl_date_delta_prepare(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }
    auto* state = new GlbkDeltaState();
    context->set_function_state(scope, state);

    const int num_args = context->get_num_args();
    // Only 2 or 3 arguments with constant offsets (and a constant, non-NULL format) can be pre-compiled.
    if ((num_args != 2 && num_args != 3) || !context->is_constant_column(1)) {
        return Status::OK();
    }
    try {
        std::optional<std::string_view> offsets_text;
        if (context->is_notnull_constant_column(1)) {
            auto offsets = ColumnHelper::get_const_value<TYPE_VARCHAR>(context->get_constant_column(1));
            offsets_text = to_view(offsets);
        }
        auto offsets = std::make_unique<glbk::Offsets>(glbk::Offsets::compile(kDateDelta, offsets_text));

        std::unique_ptr<glbk::Pattern> pattern;
        if (num_args == 3) {
            if (!context->is_notnull_constant_column(2)) {
                return Status::OK(); // non-constant or NULL format: per-row core (NULL format raises the error)
            }
            auto format = ColumnHelper::get_const_value<TYPE_VARCHAR>(context->get_constant_column(2));
            switch (classify_format(to_view(format))) {
            case FormatKind::BLANK:
                break; // blank format == default style, derived from each input
            case FormatKind::NOT_BLANK:
                pattern = std::make_unique<glbk::Pattern>(glbk::Pattern::compile(kDateDelta, to_view(format)));
                break;
            case FormatKind::UNSURE:
                return Status::OK();
            }
        }
        state->offsets = std::move(offsets);
        state->pattern = std::move(pattern);
    } catch (const glbk::GlParamError&) {
        // See gl_date_format_prepare: the per-row core entry point reports the error when a row reaches it.
        state->offsets.reset();
        state->pattern.reset();
    }
    return Status::OK();
}

Status GlbkFunctions::gl_date_delta_close(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::FRAGMENT_LOCAL) {
        delete get_state<GlbkDeltaState>(context);
        context->set_function_state(scope, nullptr);
    }
    return Status::OK();
}

StatusOr<ColumnPtr> GlbkFunctions::gl_date_delta(FunctionContext* context, const Columns& columns) {
    if (columns.size() == 1) {
        return columns[0]; // gl_date_delta(date) returns its argument unchanged (NULL stays NULL)
    }
    const auto* state = get_state<GlbkDeltaState>(context);
    if (state != nullptr && state->offsets != nullptr && !columns.empty()) {
        // Fast path: constant, valid offsets and format. Only the date column is read.
        const glbk::Offsets& offsets = *state->offsets;
        const glbk::Pattern* fixed_pattern = state->pattern.get();
        ColumnViewer<TYPE_VARCHAR> dates(columns[0]);
        const bool all_const = ColumnHelper::is_all_const(columns);
        const size_t num_rows = all_const ? 1 : columns[0]->size();
        ColumnBuilder<TYPE_VARCHAR> builder(num_rows);
        for (size_t row = 0; row < num_rows; ++row) {
            if (dates.is_null(row)) {
                builder.append_null();
                continue;
            }
            const std::string_view input = to_view(dates.value(row));
            auto parsed = glbk::parse_input(input);
            if (!parsed.has_value()) {
                builder.append_null();
                continue;
            }
            auto shifted = offsets.apply(*parsed);
            if (!shifted.has_value()) {
                builder.append_null();
                continue;
            }
            std::string text = fixed_pattern != nullptr ? fixed_pattern->format(*shifted)
                                                        : glbk::Pattern::default_style(input).format(*shifted);
            Slice value(text.data(), text.size());
            builder.append(value);
        }
        return builder.build(all_const);
    }
    return eval_per_row(columns, [](std::span<const glbk::Arg> args) { return glbk::gl_date_delta(args); });
}

// ------------------------------------------------------------------- gl_date_diff / dtype functions (per row)
// The unit/dtype argument is a short literal compared against a handful of names, so there is nothing worth
// pre-compiling: these call the core entry point for every row (it validates the unit, then parses the dates).

StatusOr<ColumnPtr> GlbkFunctions::gl_date_diff(FunctionContext* context, const Columns& columns) {
    return eval_per_row(columns, [](std::span<const glbk::Arg> args) { return glbk::gl_date_diff(args); });
}

StatusOr<ColumnPtr> GlbkFunctions::gl_how_many_day(FunctionContext* context, const Columns& columns) {
    return eval_per_row(columns, [](std::span<const glbk::Arg> args) { return glbk::gl_how_many_day(args); });
}

StatusOr<ColumnPtr> GlbkFunctions::gl_date_first(FunctionContext* context, const Columns& columns) {
    return eval_per_row(columns, [](std::span<const glbk::Arg> args) { return glbk::gl_date_first(args); });
}

StatusOr<ColumnPtr> GlbkFunctions::gl_date_last(FunctionContext* context, const Columns& columns) {
    return eval_per_row(columns, [](std::span<const glbk::Arg> args) { return glbk::gl_date_last(args); });
}

} // namespace starrocks

#include "gen_cpp/opcode/GlbkFunctions.inc"
