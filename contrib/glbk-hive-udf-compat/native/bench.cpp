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

// ns/call of the native glbk date core: pre-compiled Pattern/Offsets (what the BE binding does for constant
// parameters) versus the whole-function entry points (parse offsets/pattern on every call).
// Usage: bench [rounds]   (each case runs rounds * 1024 calls, default 2000)

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <string>
#include <vector>

#include "exprs/glbk_date_core.h"

using namespace starrocks::glbk;

static volatile size_t g_sink = 0;

static void run(const char* name, long calls, const std::function<size_t(size_t)>& body) {
    size_t acc = 0;
    for (long i = 0; i < 1024; i++) acc += body(i); // warm-up
    auto t0 = std::chrono::steady_clock::now();
    for (long i = 0; i < calls; i++) acc += body(i);
    auto t1 = std::chrono::steady_clock::now();
    g_sink = g_sink + acc;
    double ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / static_cast<double>(calls);
    printf("%-52s %9.1f ns/call\n", name, ns);
}

int main(int argc, char** argv) {
    const long rounds = argc > 1 ? atol(argv[1]) : 2000;
    const long calls = rounds * 1024;

    std::vector<std::string> d8, d14, dd8, dd14;
    unsigned x = 12345;
    for (int i = 0; i < 1024; i++) {
        x = x * 1664525u + 1013904223u;
        char b[64];
        int y = 1 + (x >> 8) % 9999, m = 1 + (x >> 4) % 12, d = 1 + (x >> 12) % 28;
        int hh = (x >> 3) % 24, mi = (x >> 9) % 60, ss = (x >> 15) % 60;
        snprintf(b, sizeof b, "%04d%02d%02d", y, m, d);
        d8.push_back(b);
        snprintf(b, sizeof b, "%04d%02d%02d%02d%02d%02d", y, m, d, hh, mi, ss);
        d14.push_back(b);
        snprintf(b, sizeof b, "%04d-%02d-%02d", y, m, d);
        dd8.push_back(b);
        snprintf(b, sizeof b, "%04d-%02d-%02d %02d:%02d:%02d", y, m, d, hh, mi, ss);
        dd14.push_back(b);
    }
    auto at = [](const std::vector<std::string>& v, size_t i) -> std::string_view { return v[i & 1023]; };

    run("parse_input 8 digits", calls, [&](size_t i) { return parse_input(at(d8, i))->day; });
    run("parse_input 14 digits dashed", calls, [&](size_t i) { return parse_input(at(dd14, i))->second; });

    DateTime t = *parse_input("20190417123456");
    const char* fast_patterns[] = {"yyyyMMdd", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyy-MM-dd HH:mm:ss"};
    for (const char* p : fast_patterns) {
        Pattern pat = Pattern::compile("bench", p);
        run((std::string("Pattern::format ") + p).c_str(), calls, [&](size_t i) {
            t.day = 1 + static_cast<int>(i % 28);
            return pat.format(t).size();
        });
    }
    const char* slow_patterns[] = {"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "EEE, d MMM yyyy HH:mm:ss z", "YYYY-ww-u G a hh"};
    for (const char* p : slow_patterns) {
        Pattern pat = Pattern::compile("bench", p);
        run((std::string("Pattern::format ") + p).c_str(), calls, [&](size_t i) {
            t.day = 1 + static_cast<int>(i % 28);
            return pat.format(t).size();
        });
    }
    run("Pattern::compile yyyy-MM-dd'T'HH:mm:ss.SSSZ", calls / 10, [&](size_t) {
        return Pattern::compile("bench", "yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(t).size();
    });

    const char* offsets[] = {"+1", "m=+1", "y=+1,m=1,d=1,d=-1", "h=+30,n=-5,s=+7"};
    for (const char* o : offsets) {
        Offsets off = Offsets::compile("bench", std::string_view(o));
        run((std::string("Offsets::apply ") + o).c_str(), calls, [&](size_t i) {
            return off.apply(*parse_input(at(d14, i)))->day;
        });
        run((std::string("Offsets::compile ") + o).c_str(), calls / 10, [&](size_t) {
            return Offsets::compile("bench", std::string_view(o)).apply(t)->day;
        });
    }

    // precompiled path: parse + apply + format, as the BE binding does for constant offsets/format
    {
        Offsets off = Offsets::compile("bench", std::string_view("y=+1,m=1,d=1,d=-1"));
        run("precompiled delta (parse+apply+default fmt)", calls, [&](size_t i) {
            std::string_view s = at(dd14, i);
            auto p = parse_input(s);
            auto r = off.apply(*p);
            return Pattern::default_style(s).format(*r).size();
        });
    }

    auto entry = [&](const char* name, const std::function<std::optional<std::string>(size_t)>& f) {
        run(name, calls, [&](size_t i) {
            auto r = f(i);
            return r ? r->size() : 0;
        });
    };
    entry("entry gl_date_delta(d, '+1')", [&](size_t i) {
        Arg a[] = {at(d8, i), "+1"};
        return gl_date_delta(a);
    });
    entry("entry gl_date_delta(d, 'y=+1,m=1,d=1,d=-1')", [&](size_t i) {
        Arg a[] = {at(dd14, i), "y=+1,m=1,d=1,d=-1"};
        return gl_date_delta(a);
    });
    entry("entry gl_date_delta(d, '+1', 'yyyy-MM-dd')", [&](size_t i) {
        Arg a[] = {at(d14, i), "+1", "yyyy-MM-dd"};
        return gl_date_delta(a);
    });
    entry("entry gl_date_delta(d, '+1', 'EEE, d MMM yyyy')", [&](size_t i) {
        Arg a[] = {at(d14, i), "+1", "EEE, d MMM yyyy"};
        return gl_date_delta(a);
    });
    entry("entry gl_date_diff(a, b)", [&](size_t i) {
        Arg a[] = {at(d8, i), at(d8, i + 1)};
        return gl_date_diff(a);
    });
    entry("entry gl_date_diff(a, b, 'week')", [&](size_t i) {
        Arg a[] = {at(d14, i), at(d14, i + 1), "week"};
        return gl_date_diff(a);
    });
    entry("entry gl_date_diff(a, b, 'second')", [&](size_t i) {
        Arg a[] = {at(d14, i), at(d14, i + 1), "second"};
        return gl_date_diff(a);
    });
    entry("entry gl_date_format(d)", [&](size_t i) {
        Arg a[] = {at(d8, i)};
        return gl_date_format(a);
    });
    entry("entry gl_date_format(d, 'yyyy-MM-dd HH:mm:ss')", [&](size_t i) {
        Arg a[] = {at(d14, i), "yyyy-MM-dd HH:mm:ss"};
        return gl_date_format(a);
    });
    entry("entry gl_date_format(d, 'EEE, d MMM yyyy HH:mm:ss z')", [&](size_t i) {
        Arg a[] = {at(d14, i), "EEE, d MMM yyyy HH:mm:ss z"};
        return gl_date_format(a);
    });
    entry("entry gl_how_many_day(d, 'week')", [&](size_t i) {
        Arg a[] = {at(d8, i), "week"};
        return gl_how_many_day(a);
    });
    entry("entry gl_date_first(d, 'quarter')", [&](size_t i) {
        Arg a[] = {at(d8, i), "quarter"};
        return gl_date_first(a);
    });
    entry("entry gl_date_last(d, 'week')", [&](size_t i) {
        Arg a[] = {at(d8, i), "week"};
        return gl_date_last(a);
    });
    return g_sink == 1 ? 1 : 0;
}
