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

// Differential test: replays native/oracle.tsv (written by the Java NativeOracleGenerator from the reference
// implementation) against the native C++ port. Exit code 1 on any mismatch.
// Usage: oracle_test <oracle.tsv> [max_reported_mismatches]

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#include "exprs/glbk_date_core.h"

using namespace starrocks::glbk;

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

// Inverse of NativeOracleGenerator.esc(). Returns false for the NULL marker "\N".
static bool unescape(const std::string& f, std::string* out) {
    if (f == "\\N") return false;
    out->clear();
    for (size_t i = 0; i < f.size(); i++) {
        if (f[i] != '\\' || i + 1 >= f.size()) {
            out->push_back(f[i]);
            continue;
        }
        char c = f[++i];
        if (c == '\\') {
            out->push_back('\\');
        } else if (c == 't') {
            out->push_back('\t');
        } else if (c == 'n') {
            out->push_back('\n');
        } else if (c == 'r') {
            out->push_back('\r');
        } else if (c == 'x' && i + 2 < f.size() + 0 && hexval(f[i + 1]) >= 0 && hexval(f[i + 2]) >= 0) {
            out->push_back(static_cast<char>(hexval(f[i + 1]) * 16 + hexval(f[i + 2])));
            i += 2;
        } else {
            out->push_back('\\');
            out->push_back(c);
        }
    }
    return true;
}

static std::string show(const std::string& s) {
    std::string r;
    for (unsigned char c : s) {
        if (c == '\\') {
            r += "\\\\";
        } else if (c < 0x20 || c == 0x7f) {
            char b[8];
            snprintf(b, sizeof b, "\\x%02x", c);
            r += b;
        } else {
            r += static_cast<char>(c);
        }
    }
    return r.size() > 200 ? r.substr(0, 200) + "...(" + std::to_string(r.size()) + " bytes)" : r;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <oracle.tsv> [max_reported]\n", argv[0]);
        return 2;
    }
    const long max_reported = argc > 2 ? atol(argv[2]) : 30;
    std::ifstream in(argv[1], std::ios::binary);
    if (!in) {
        fprintf(stderr, "cannot open %s\n", argv[1]);
        return 2;
    }
    std::map<std::string, std::pair<long, long>> per_fn; // lines, mismatches
    long lines = 0, bad = 0;
    std::string line;
    while (std::getline(in, line)) {
        lines++;
        std::vector<std::string> cols;
        size_t pos = 0;
        while (true) {
            size_t t = line.find('\t', pos);
            if (t == std::string::npos) {
                cols.push_back(line.substr(pos));
                break;
            }
            cols.push_back(line.substr(pos, t - pos));
            pos = t + 1;
        }
        if (cols.size() < 3) {
            fprintf(stderr, "line %ld: malformed\n", lines);
            return 2;
        }
        const std::string& fn = cols[0];
        const size_t arity = static_cast<size_t>(atol(cols[1].c_str()));
        if (cols.size() != arity + 3) {
            fprintf(stderr, "line %ld: expected %zu columns, got %zu\n", lines, arity + 3, cols.size());
            return 2;
        }
        std::vector<std::string> store(arity);
        std::vector<Arg> args(arity);
        for (size_t i = 0; i < arity; i++) {
            if (unescape(cols[2 + i], &store[i])) args[i] = std::string_view(store[i]);
        }
        const std::string& exp = cols[arity + 2];

        std::string got;
        try {
            std::optional<std::string> r;
            if (fn == "gl_date_delta") {
                r = gl_date_delta(args);
            } else if (fn == "gl_date_diff") {
                r = gl_date_diff(args);
            } else if (fn == "gl_date_format") {
                r = gl_date_format(args);
            } else if (fn == "gl_how_many_day") {
                r = gl_how_many_day(args);
            } else if (fn == "gl_date_first") {
                r = gl_date_first(args);
            } else if (fn == "gl_date_last") {
                r = gl_date_last(args);
            } else {
                fprintf(stderr, "line %ld: unknown function %s\n", lines, fn.c_str());
                return 2;
            }
            if (!r) {
                got = "N";
            } else {
                // compare against the escaped form of the expected value
                std::string esc;
                for (unsigned char c : *r) {
                    if (c == '\\') {
                        esc += "\\\\";
                    } else if (c == '\t') {
                        esc += "\\t";
                    } else if (c == '\n') {
                        esc += "\\n";
                    } else if (c == '\r') {
                        esc += "\\r";
                    } else if (c < 0x20 || c == 0x7f) {
                        char b[8];
                        snprintf(b, sizeof b, "\\x%02x", c);
                        esc += b;
                    } else {
                        esc += static_cast<char>(c);
                    }
                }
                got = "V" + esc;
            }
        } catch (const GlParamError&) {
            got = "E";
        } catch (const std::exception& e) {
            got = std::string("UNEXPECTED(") + e.what() + ")";
        }
        auto& st = per_fn[fn];
        st.first++;
        if (got != exp) {
            st.second++;
            if (++bad <= max_reported) {
                std::string a;
                for (size_t i = 0; i < arity; i++) a += (i ? ", " : "") + (args[i] ? "'" + show(store[i]) + "'" : std::string("NULL"));
                fprintf(stderr, "MISMATCH line %ld: %s(%s)\n    expected %s\n    got      %s\n", lines, fn.c_str(),
                        a.c_str(), show(exp).c_str(), show(got).c_str());
            }
        }
    }
    for (auto& [fn, st] : per_fn) {
        printf("%-16s %9ld lines %9ld mismatches\n", fn.c_str(), st.first, st.second);
    }
    printf("TOTAL %ld lines, %ld mismatches\n", lines, bad);
    return bad == 0 ? 0 : 1;
}
