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

#include "column/column_builder.h"
#include "column/vectorized_fwd.h"
#include "exprs/builtin_functions.h"
#include "exprs/function_context.h"
#include "exprs/function_helper.h"

namespace starrocks {

// Native builtin implementation of the glbk date functions (gl_date_delta, gl_date_diff, gl_date_format,
// gl_how_many_day, gl_date_first, gl_date_last). The algorithms live in exprs/glbk_date_core.{h,cpp}; this class
// is the StarRocks binding: columns, NULL handling, constant-argument pre-compilation and error reporting.
//
// All functions are variadic (VARCHAR, ...) like the Java UDFs they replace, so a call with an unsupported number
// of arguments returns NULL (as the Java UDFs do) while a zero-argument call is rejected by the analyzer.
//
// NULL handling: builtin scalar functions receive their arguments unchanged, NULLs included (there is no FE/BE
// null propagation unless a function asks for it), so SQL NULL reaches the core as std::nullopt and the core
// decides between "NULL result" and "parameter error". Result columns are always nullable.
//
// Errors: wrong PARAMETERS make the core throw glbk::GlParamError, which is returned as Status::InvalidArgument
// (the query fails with the core's message). Invalid DATA gives a NULL result.
//
// Constant parameters: gl_date_format / gl_date_delta pre-compile a constant format / offsets in the
// FRAGMENT_LOCAL prepare step and free the state in close. A constant that fails to compile does NOT fail the
// prepare step: the state stays empty and every row falls back to the per-row core entry point, which raises the
// same error when (and only when) a row reaches parameter validation. That keeps the result identical to the
// per-row path, where a NULL date returns NULL before any parameter is validated.
//
// Same-named Java UDFs: the analyzer resolves a function name to a builtin before looking for a UDF
// (FunctionAnalyzer.getAnalyzedFunctionImpl), so once these builtins exist a UDF with the same name is never
// called by an unqualified call. Drop the old UDFs (DROP FUNCTION gl_date_delta(VARCHAR, ...), ...) to avoid
// confusion. gl_now stays a Java UDF.
class GlbkFunctions {
public:
    DEFINE_VECTORIZED_FN(gl_date_delta);
    static Status gl_date_delta_prepare(FunctionContext* context, FunctionContext::FunctionStateScope scope);
    static Status gl_date_delta_close(FunctionContext* context, FunctionContext::FunctionStateScope scope);

    DEFINE_VECTORIZED_FN(gl_date_diff);

    DEFINE_VECTORIZED_FN(gl_date_format);
    static Status gl_date_format_prepare(FunctionContext* context, FunctionContext::FunctionStateScope scope);
    static Status gl_date_format_close(FunctionContext* context, FunctionContext::FunctionStateScope scope);

    DEFINE_VECTORIZED_FN(gl_how_many_day);
    DEFINE_VECTORIZED_FN(gl_date_first);
    DEFINE_VECTORIZED_FN(gl_date_last);
};

} // namespace starrocks
