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

// std-only port of com.glbk.starrocks.udf.core.GlDates / DateFormats (contrib/glbk-hive-udf-compat).
// Every function below mirrors the Java method of the same name; keep the order of checks identical.

#include "exprs/glbk_date_core.h"

#include <algorithm>
#include <cstring>
#include <initializer_list>

namespace starrocks::glbk {

namespace {

// ------------------------------------------------------------------ units

enum Unit : uint8_t { YEAR = 0, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND };

struct UnitName {
    const char* long_name;
    const char* short_name;
};
constexpr UnitName kUnitNames[] = {{"year", "y"}, {"quarter", "q"}, {"month", "m"}, {"week", "w"},
                                   {"day", "d"},  {"hour", "h"},    {"minute", "n"}, {"second", "s"}};

constexpr unsigned bit(Unit u) {
    return 1u << u;
}
constexpr unsigned kDiffUnits = 0xFF;
constexpr unsigned kDtypeUnits = bit(YEAR) | bit(QUARTER) | bit(MONTH) | bit(WEEK);
constexpr unsigned kOffsetUnits = bit(YEAR) | bit(MONTH) | bit(DAY) | bit(HOUR) | bit(MINUTE) | bit(SECOND);

// ------------------------------------------------------------------ civil date arithmetic (java.time semantics)

constexpr int64_t kMaxYear = 999999999; // java.time.Year.MAX_VALUE; MIN_VALUE is the negation

inline int64_t floor_div(int64_t a, int64_t b) {
    int64_t q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}
inline int64_t floor_mod(int64_t a, int64_t b) {
    return a - floor_div(a, b) * b;
}

inline bool is_leap(int64_t y) {
    return (y & 3) == 0 && (y % 100 != 0 || y % 400 == 0);
}

inline int month_length(int64_t y, int m) {
    static constexpr int kLen[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    return (m == 2 && is_leap(y)) ? 29 : kLen[m - 1];
}

// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm, valid for all years).
int64_t days_from_civil(int64_t y, int m, int d) {
    y -= m <= 2;
    const int64_t era = floor_div(y, 400);
    const int64_t yoe = y - era * 400;
    const int64_t doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

void civil_from_days(int64_t z, int64_t* y, int* m, int* d) {
    z += 719468;
    const int64_t era = floor_div(z, 146097);
    const int64_t doe = z - era * 146097;
    const int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    const int64_t yy = yoe + era * 400;
    const int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const int64_t mp = (5 * doy + 2) / 153;
    *d = static_cast<int>(doy - (153 * mp + 2) / 5 + 1);
    *m = static_cast<int>(mp < 10 ? mp + 3 : mp - 9);
    *y = yy + (*m <= 2);
}

inline int64_t epoch_day(const DateTime& t) {
    return days_from_civil(t.year, t.month, t.day);
}

// 0 = Sunday .. 6 = Saturday (1970-01-01 was a Thursday).
inline int day_of_week_sun0(int64_t epoch_day) {
    return static_cast<int>(floor_mod(epoch_day + 4, 7));
}

// ------------------------------------------------------------------ Java string helpers

// java.lang.Character.isWhitespace for one BMP code point.
bool java_whitespace(uint32_t c) {
    if (c <= 0x20) return c == 0x20 || (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x1F);
    if (c < 0x1680) return false;
    return c == 0x1680 || (c >= 0x2000 && c <= 0x2006) || (c >= 0x2008 && c <= 0x200A) || c == 0x2028 ||
           c == 0x2029 || c == 0x205F || c == 0x3000;
}

// GlDates.isBlank over UTF-8 text (invalid sequences and non-BMP characters are never whitespace).
bool is_blank(std::string_view s) {
    size_t i = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        if (c < 0x80) {
            if (!java_whitespace(c)) return false;
            i++;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < s.size() && (static_cast<unsigned char>(s[i + 1]) & 0xC0) == 0x80) {
            // two-byte sequences are U+0080..U+07FF: none of them is whitespace
            return false;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < s.size() && (static_cast<unsigned char>(s[i + 1]) & 0xC0) == 0x80 &&
                   (static_cast<unsigned char>(s[i + 2]) & 0xC0) == 0x80) {
            const uint32_t cp = ((c & 0x0Fu) << 12) | ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 6) |
                                (static_cast<unsigned char>(s[i + 2]) & 0x3Fu);
            if (!java_whitespace(cp)) return false;
            i += 3;
        } else {
            return false;
        }
    }
    return true;
}

// String.trim(): strips every leading/trailing char <= U+0020.
std::string_view java_trim(std::string_view s) {
    size_t b = 0;
    size_t e = s.size();
    while (b < e && static_cast<unsigned char>(s[b]) <= 0x20) b++;
    while (e > b && static_cast<unsigned char>(s[e - 1]) <= 0x20) e--;
    return s.substr(b, e - b);
}

std::string cat(std::initializer_list<std::string_view> parts) {
    std::string r;
    for (auto p : parts) r.append(p);
    return r;
}

Unit lookup_unit(std::string_view fn, const char* what, std::optional<std::string_view> name, unsigned allowed) {
    if (!name.has_value() || is_blank(*name)) {
        throw GlParamError(cat({fn, ": ", what, " must not be null or blank"}));
    }
    for (int i = 0; i < 8; i++) {
        if ((allowed & (1u << i)) && (*name == kUnitNames[i].long_name || *name == kUnitNames[i].short_name)) {
            return static_cast<Unit>(i);
        }
    }
    std::string sb;
    for (int i = 0; i < 8; i++) {
        if (!(allowed & (1u << i))) continue;
        if (!sb.empty()) sb += ", ";
        sb += kUnitNames[i].long_name;
        sb += '|';
        sb += kUnitNames[i].short_name;
    }
    throw GlParamError(cat({fn, ": unknown ", what, " '", *name, "' (case-sensitive, expected one of ", sb, ")"}));
}

// ------------------------------------------------------------------ number / text output

inline void put_digits(char* b, int v, int width) {
    for (int k = width - 1; k >= 0; k--) {
        b[k] = static_cast<char>('0' + v % 10);
        v /= 10;
    }
}

// The four standard shapes. year must be 0..9999 (callers guarantee 1..9999).
std::string format_fast(int fast, const DateTime& t) {
    const bool dashed = fast == 2 || fast == 4;
    const bool time = fast == 3 || fast == 4;
    char b[19];
    int i = 0;
    put_digits(b + i, t.year, 4);
    i += 4;
    if (dashed) b[i++] = '-';
    put_digits(b + i, t.month, 2);
    i += 2;
    if (dashed) b[i++] = '-';
    put_digits(b + i, t.day, 2);
    i += 2;
    if (time) {
        if (dashed) b[i++] = ' ';
        put_digits(b + i, t.hour, 2);
        i += 2;
        if (dashed) b[i++] = ':';
        put_digits(b + i, t.minute, 2);
        i += 2;
        if (dashed) b[i++] = ':';
        put_digits(b + i, t.second, 2);
        i += 2;
    }
    return std::string(b, i);
}

constexpr int kMaxMinDigits = 309; // DecimalFormat.DOUBLE_INTEGER_DIGITS

// SimpleDateFormat.zeroPaddingNumber(value, min_digits, unlimited) for value >= 0.
void put_number(std::string* out, int64_t value, int min_digits) {
    if (min_digits > kMaxMinDigits) min_digits = kMaxMinDigits;
    char tmp[24];
    int n = 0;
    do {
        tmp[n++] = static_cast<char>('0' + value % 10);
        value /= 10;
    } while (value > 0);
    for (int i = n; i < min_digits; i++) out->push_back('0');
    while (n > 0) out->push_back(tmp[--n]);
}

// zeroPaddingNumber(value, 2, 2): the last two digits.
void put_two_digit_year(std::string* out, int64_t value) {
    const int v = static_cast<int>(value % 100);
    out->push_back(static_cast<char>('0' + v / 10));
    out->push_back(static_cast<char>('0' + v % 10));
}

constexpr const char* kMonthLong[] = {"January", "February", "March",     "April",   "May",      "June",
                                      "July",    "August",   "September", "October", "November", "December"};
constexpr const char* kMonthShort[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                       "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
constexpr const char* kDayLong[] = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
constexpr const char* kDayShort[] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

constexpr std::string_view kPatternChars = "GyMdkHmsSEDFwWahKzZYuXL";

// Sunday-first weeks, minimalDaysInFirstWeek = 1 (the calendar the Java code configures).
struct WeekInfo {
    int week_of_year;
    int64_t week_year;
};
WeekInfo week_of_year(const DateTime& t, int64_t ed, int dow) {
    const int64_t week_start = ed - dow; // the Sunday on or before
    const int64_t next_jan1 = days_from_civil(static_cast<int64_t>(t.year) + 1, 1, 1);
    if (week_start + 6 >= next_jan1) return {1, static_cast<int64_t>(t.year) + 1}; // week holds next Jan 1
    const int64_t jan1 = days_from_civil(t.year, 1, 1);
    const int64_t first_week_start = jan1 - day_of_week_sun0(jan1);
    return {static_cast<int>((week_start - first_week_start) / 7 + 1), t.year};
}

} // namespace

// ------------------------------------------------------------------ parsing

std::optional<DateTime> parse_input(std::string_view s) {
    char d[14];
    int len = 0;
    for (char c : s) {
        if (c == ' ' || c == '-' || c == ':') continue;
        if (c < '0' || c > '9' || len == 14) return std::nullopt;
        d[len++] = c;
    }
    if (len != 8 && len != 14) return std::nullopt;
    auto num = [&](int from, int count) {
        int v = 0;
        for (int i = from; i < from + count; i++) v = v * 10 + (d[i] - '0');
        return v;
    };
    DateTime t;
    t.year = num(0, 4);
    t.month = num(4, 2);
    t.day = num(6, 2);
    if (t.year < 1 || t.month < 1 || t.month > 12 || t.day < 1 || t.day > month_length(t.year, t.month)) {
        return std::nullopt;
    }
    if (len == 14) {
        t.hour = num(8, 2);
        t.minute = num(10, 2);
        t.second = num(12, 2);
        if (t.hour > 23 || t.minute > 59 || t.second > 59) return std::nullopt;
    }
    return t;
}

// ------------------------------------------------------------------ Pattern

Pattern Pattern::default_style(std::string_view input) {
    bool dashed = false;
    int digits = 0;
    for (char c : input) {
        if (c == ' ' || c == '-' || c == ':') {
            dashed = true;
        } else {
            digits++;
        }
    }
    Pattern p;
    if (digits == 8) {
        p._fast = dashed ? 2 : 1;
    } else {
        p._fast = dashed ? 4 : 3;
    }
    return p;
}

Pattern Pattern::compile(std::string_view fn, std::string_view pattern) {
    Pattern p;
    if (pattern == "yyyyMMdd") {
        p._fast = 1;
        return p;
    }
    if (pattern == "yyyy-MM-dd") {
        p._fast = 2;
        return p;
    }
    if (pattern == "yyyyMMddHHmmss") {
        p._fast = 3;
        return p;
    }
    if (pattern == "yyyy-MM-dd HH:mm:ss") {
        p._fast = 4;
        return p;
    }
    auto bad = [&](const std::string& cause) {
        return GlParamError(cat({fn, ": invalid format pattern '", pattern, "': ", cause}));
    };
    auto add_literal = [&](std::string_view text) {
        if (!p._tokens.empty() && p._tokens.back().kind == 0) {
            p._tokens.back().text.append(text);
        } else {
            Token t;
            t.text.assign(text);
            p._tokens.push_back(std::move(t));
        }
    };
    char last_tag = 0;
    int count = 0;
    auto flush = [&]() {
        if (count == 0) return;
        if (last_tag == 'X' && count >= 4) throw bad("invalid ISO 8601 format: length=" + std::to_string(count));
        Token t;
        t.kind = last_tag;
        t.count = count;
        p._tokens.push_back(std::move(t));
        last_tag = 0;
        count = 0;
    };
    bool in_quote = false;
    std::string quoted;
    const size_t n = pattern.size();
    for (size_t i = 0; i < n; i++) {
        char c = pattern[i];
        if (c == '\'') {
            if (i + 1 < n && pattern[i + 1] == '\'') { // '' is a single quote, also inside a quoted section
                i++;
                flush();
                if (in_quote) {
                    quoted.push_back('\'');
                } else {
                    add_literal("'");
                }
                continue;
            }
            if (!in_quote) {
                flush();
                quoted.clear();
                in_quote = true;
            } else {
                add_literal(quoted);
                in_quote = false;
            }
            continue;
        }
        if (in_quote) {
            quoted.push_back(c);
            continue;
        }
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
            flush();
            add_literal(std::string_view(&c, 1));
            continue;
        }
        if (kPatternChars.find(c) == std::string_view::npos) {
            throw bad(cat({"Illegal pattern character '", std::string_view(&c, 1), "'"}));
        }
        if (count == 0 || last_tag == c) {
            last_tag = c;
            count++;
            continue;
        }
        flush();
        last_tag = c;
        count = 1;
    }
    if (in_quote) throw bad("Unterminated quote");
    flush();
    return p;
}

std::string Pattern::format(const DateTime& t) const {
    if (_fast != 0) return format_fast(_fast, t);
    std::string out;
    out.reserve(_tokens.size() * 4 + 16);
    const int64_t ed = epoch_day(t);
    const int dow = day_of_week_sun0(ed);
    for (const Token& tok : _tokens) {
        const int count = tok.count;
        switch (tok.kind) {
        case 0:
            out.append(tok.text);
            break;
        case 'G':
            out.append("AD");
            break;
        case 'y':
            if (count == 2) {
                put_two_digit_year(&out, t.year);
            } else {
                put_number(&out, t.year, count);
            }
            break;
        case 'Y': {
            const int64_t wy = week_of_year(t, ed, dow).week_year;
            if (count == 2) {
                put_two_digit_year(&out, wy);
            } else {
                put_number(&out, wy, count);
            }
            break;
        }
        case 'M':
        case 'L':
            if (count >= 4) {
                out.append(kMonthLong[t.month - 1]);
            } else if (count == 3) {
                out.append(kMonthShort[t.month - 1]);
            } else {
                put_number(&out, t.month, count);
            }
            break;
        case 'd':
            put_number(&out, t.day, count);
            break;
        case 'k':
            put_number(&out, t.hour == 0 ? 24 : t.hour, count);
            break;
        case 'H':
            put_number(&out, t.hour, count);
            break;
        case 'm':
            put_number(&out, t.minute, count);
            break;
        case 's':
            put_number(&out, t.second, count);
            break;
        case 'S':
            put_number(&out, 0, count);
            break;
        case 'E':
            out.append(count >= 4 ? kDayLong[dow] : kDayShort[dow]);
            break;
        case 'D':
            put_number(&out, ed - days_from_civil(t.year, 1, 1) + 1, count);
            break;
        case 'F':
            put_number(&out, (t.day - 1) / 7 + 1, count);
            break;
        case 'w':
            put_number(&out, week_of_year(t, ed, dow).week_of_year, count);
            break;
        case 'W': {
            const int first_dow = day_of_week_sun0(ed - (t.day - 1));
            put_number(&out, (t.day - 1 + first_dow) / 7 + 1, count);
            break;
        }
        case 'a':
            out.append(t.hour >= 12 ? "PM" : "AM");
            break;
        case 'h':
            put_number(&out, t.hour % 12 == 0 ? 12 : t.hour % 12, count);
            break;
        case 'K':
            put_number(&out, t.hour % 12, count);
            break;
        case 'z':
            out.append(count >= 4 ? "Coordinated Universal Time" : "UTC");
            break;
        case 'Z':
            out.append("+0000");
            break;
        case 'u':
            put_number(&out, dow == 0 ? 7 : dow, count);
            break;
        case 'X':
            out.push_back('Z');
            break;
        default:
            break;
        }
    }
    return out;
}

// ------------------------------------------------------------------ Offsets

Offsets Offsets::compile(std::string_view fn, std::optional<std::string_view> offsets) {
    Offsets r;
    if (!offsets.has_value() || is_blank(*offsets)) return r;
    const std::string_view off = *offsets;
    std::string prefixed;
    std::string_view text = off;
    if (off.find(',') == std::string_view::npos && off.find('=') == std::string_view::npos) {
        prefixed = "day=";
        prefixed.append(off);
        text = prefixed;
    }
    // text.split(","): trailing empty pieces are dropped
    std::vector<std::string_view> pieces;
    size_t pos = 0;
    while (true) {
        size_t comma = text.find(',', pos);
        if (comma == std::string_view::npos) {
            pieces.push_back(text.substr(pos));
            break;
        }
        pieces.push_back(text.substr(pos, comma - pos));
        pos = comma + 1;
    }
    while (!pieces.empty() && pieces.back().empty()) pieces.pop_back();

    for (std::string_view raw : pieces) {
        std::string_view item = java_trim(raw);
        if (item.empty()) throw GlParamError(cat({fn, ": empty item in offsets '", off, "'"}));
        const size_t eq = item.find('=');
        if (eq == std::string_view::npos) {
            throw GlParamError(
                    cat({fn, ": offset item '", item, "' must look like unit=value (offsets '", off, "')"}));
        }
        const Unit u = lookup_unit(fn, "offset unit", java_trim(item.substr(0, eq)), kOffsetUnits);
        const std::string_view value = java_trim(item.substr(eq + 1));

        const bool signed_item = !value.empty() && (value[0] == '+' || value[0] == '-');
        const size_t digits_from = signed_item ? 1 : 0;
        bool ok = value.size() > digits_from;
        for (size_t i = digits_from; ok && i < value.size(); i++) ok = value[i] >= '0' && value[i] <= '9';
        int64_t v = 0;
        if (ok) { // Integer.parseInt
            const bool neg = value[0] == '-';
            const int64_t limit = neg ? 2147483648LL : 2147483647LL;
            for (size_t i = digits_from; i < value.size(); i++) {
                v = v * 10 + (value[i] - '0');
                if (v > limit) {
                    ok = false;
                    break;
                }
            }
            if (ok && neg) v = -v;
        }
        if (!ok) {
            throw GlParamError(cat({fn, ": offset value '", value, "' in '", item, "' is not an integer"}));
        }
        if (!signed_item) {
            int min;
            int max;
            switch (u) {
            case YEAR:
                min = 1;
                max = 9999;
                break;
            case MONTH:
                min = 1;
                max = 12;
                break;
            case DAY:
                min = 1;
                max = 31;
                break;
            case HOUR:
                min = 0;
                max = 23;
                break;
            default:
                min = 0;
                max = 59;
                break;
            }
            if (v < min || v > max) {
                throw GlParamError(cat({fn, ": value ", std::to_string(v), " of '", item, "' is out of range ",
                                        std::to_string(min), "..", std::to_string(max), " for ",
                                        kUnitNames[u].long_name, " (use +N/-N to add or subtract)"}));
            }
        }
        Op op;
        op.unit = u;
        op.add = signed_item;
        op.value = static_cast<int32_t>(v);
        r._ops.push_back(op);
    }
    return r;
}

namespace {

// All fields as 64-bit so intermediate results can leave 1..9999 (java.time allows +-999999999 years).
struct Wall {
    int64_t year;
    int month;
    int day;
    int hour;
    int minute;
    int second;
};

bool set_date_from_epoch_day(Wall* w, int64_t ed) {
    int64_t y;
    int m;
    int d;
    civil_from_days(ed, &y, &m, &d);
    if (y < -kMaxYear || y > kMaxYear) return false;
    w->year = y;
    w->month = m;
    w->day = d;
    return true;
}

bool plus_days(Wall* w, int64_t n) {
    if (n == 0) return true;
    return set_date_from_epoch_day(w, days_from_civil(w->year, w->month, w->day) + n);
}

bool plus_seconds_of_day(Wall* w, int64_t seconds) { // seconds already scaled to the unit
    if (seconds == 0) return true;
    const int64_t total = static_cast<int64_t>(w->hour) * 3600 + w->minute * 60 + w->second + seconds;
    const int64_t days = floor_div(total, 86400);
    const int64_t sod = floor_mod(total, 86400);
    w->hour = static_cast<int>(sod / 3600);
    w->minute = static_cast<int>(sod % 3600 / 60);
    w->second = static_cast<int>(sod % 60);
    return plus_days(w, days);
}

} // namespace

std::optional<DateTime> Offsets::apply(const DateTime& start) const {
    Wall w{start.year, start.month, start.day, start.hour, start.minute, start.second};
    int py = 0; // pending set values; 0 = none (valid set values are all >= 1)
    int pm = 0;
    int pd = 0;
    auto resolve = [&]() {
        if (py == 0 && pm == 0 && pd == 0) return true;
        const int64_t y = py != 0 ? py : w.year;
        const int m = pm != 0 ? pm : w.month;
        const int d = pd != 0 ? pd : w.day;
        if (d > month_length(y, m)) return false;
        w.year = y;
        w.month = m;
        w.day = d;
        py = pm = pd = 0;
        return true;
    };
    for (const Op& op : _ops) {
        if (!op.add) {
            switch (op.unit) {
            case YEAR:
                py = op.value;
                break;
            case MONTH:
                pm = op.value;
                break;
            case DAY:
                pd = op.value;
                break;
            case HOUR:
                w.hour = op.value;
                break;
            case MINUTE:
                w.minute = op.value;
                break;
            default:
                w.second = op.value;
                break;
            }
            continue;
        }
        if (!resolve()) return std::nullopt;
        bool ok = true;
        switch (op.unit) {
        case YEAR: {
            if (op.value == 0) break;
            const int64_t ny = w.year + op.value;
            if (ny < -kMaxYear || ny > kMaxYear) return std::nullopt;
            w.year = ny;
            w.day = std::min(w.day, month_length(ny, w.month));
            break;
        }
        case MONTH: {
            if (op.value == 0) break;
            const int64_t mc = w.year * 12 + (w.month - 1) + op.value;
            const int64_t ny = floor_div(mc, 12);
            if (ny < -kMaxYear || ny > kMaxYear) return std::nullopt;
            w.year = ny;
            w.month = static_cast<int>(floor_mod(mc, 12)) + 1;
            w.day = std::min(w.day, month_length(ny, w.month));
            break;
        }
        case DAY:
            ok = plus_days(&w, op.value);
            break;
        case HOUR:
            ok = plus_seconds_of_day(&w, static_cast<int64_t>(op.value) * 3600);
            break;
        case MINUTE:
            ok = plus_seconds_of_day(&w, static_cast<int64_t>(op.value) * 60);
            break;
        default:
            ok = plus_seconds_of_day(&w, op.value);
            break;
        }
        if (!ok) return std::nullopt;
    }
    if (!resolve()) return std::nullopt;
    if (w.year < 1 || w.year > 9999) return std::nullopt;
    DateTime r;
    r.year = static_cast<int32_t>(w.year);
    r.month = w.month;
    r.day = w.day;
    r.hour = w.hour;
    r.minute = w.minute;
    r.second = w.second;
    return r;
}

// ------------------------------------------------------------------ entry points

namespace {

int64_t week_first(int64_t ed) {
    return ed - day_of_week_sun0(ed);
}

int64_t quarter_first_epoch_day(const DateTime& t) {
    return days_from_civil(t.year, (t.month - 1) / 3 * 3 + 1, 1);
}

std::string yyyymmdd_from_epoch_day(int64_t ed, bool* ok) {
    int64_t y;
    int m;
    int d;
    civil_from_days(ed, &y, &m, &d);
    if (y < 1 || y > 9999) {
        *ok = false;
        return {};
    }
    DateTime t;
    t.year = static_cast<int32_t>(y);
    t.month = m;
    t.day = d;
    *ok = true;
    return format_fast(1, t);
}

std::optional<std::string> first_or_last(const char* fn, bool last, std::span<const Arg> args) {
    if (args.size() != 2 || !args[0].has_value()) return std::nullopt;
    const Unit u = lookup_unit(fn, "dtype", args[1], kDtypeUnits);
    auto t = parse_input(*args[0]);
    if (!t) return std::nullopt;
    int64_t r;
    switch (u) {
    case YEAR:
        r = last ? days_from_civil(t->year, 12, 31) : days_from_civil(t->year, 1, 1);
        break;
    case QUARTER: {
        const int first_month = (t->month - 1) / 3 * 3 + 1;
        r = last ? days_from_civil(t->year, first_month + 2, month_length(t->year, first_month + 2))
                 : days_from_civil(t->year, first_month, 1);
        break;
    }
    case MONTH:
        r = last ? days_from_civil(t->year, t->month, month_length(t->year, t->month))
                 : days_from_civil(t->year, t->month, 1);
        break;
    default:
        r = week_first(epoch_day(*t));
        if (last) r += 6;
        break;
    }
    bool ok;
    std::string s = yyyymmdd_from_epoch_day(r, &ok);
    if (!ok) return std::nullopt; // e.g. the week of 9999-12-31 ends in year 10000
    return s;
}

} // namespace

std::optional<std::string> gl_date_delta(std::span<const Arg> args) {
    static constexpr std::string_view fn = "gl_date_delta";
    if (args.empty() || args.size() > 3) return std::nullopt;
    if (args.size() == 1) {
        if (!args[0].has_value()) return std::nullopt;
        return std::string(*args[0]);
    }
    if (!args[0].has_value()) return std::nullopt;
    const std::string_view date = *args[0];
    Offsets offsets = Offsets::compile(fn, args[1]);
    std::optional<std::string_view> pattern;
    if (args.size() == 3) {
        if (!args[2].has_value()) throw GlParamError(cat({fn, ": format must not be null"}));
        if (!is_blank(*args[2])) pattern = *args[2];
    }
    Pattern fmt = pattern.has_value() ? Pattern::compile(fn, *pattern) : Pattern::default_style(date);
    auto t = parse_input(date);
    if (!t) return std::nullopt;
    auto r = offsets.apply(*t);
    if (!r) return std::nullopt;
    return fmt.format(*r);
}

std::optional<std::string> gl_date_diff(std::span<const Arg> args) {
    static constexpr std::string_view fn = "gl_date_diff";
    if (args.size() < 2 || args.size() > 3) return std::nullopt;
    if (!args[0].has_value() || !args[1].has_value()) return std::nullopt;
    const Unit u = args.size() == 3 ? lookup_unit(fn, "interval", args[2], kDiffUnits) : DAY;
    auto a = parse_input(*args[0]);
    auto b = parse_input(*args[1]);
    if (!a || !b) return std::nullopt;
    const int64_t da = epoch_day(*a);
    const int64_t db = epoch_day(*b);
    int64_t r;
    switch (u) {
    case YEAR:
        r = a->year - b->year;
        break;
    case QUARTER:
        r = (a->year * 4LL + (a->month - 1) / 3) - (b->year * 4LL + (b->month - 1) / 3);
        break;
    case MONTH:
        r = (a->year * 12LL + a->month) - (b->year * 12LL + b->month);
        break;
    case WEEK:
        r = (week_first(da) - week_first(db)) / 7;
        break;
    case DAY:
        r = da - db;
        break;
    case HOUR:
        r = (da - db) * 24 + (a->hour - b->hour);
        break;
    case MINUTE:
        r = (da - db) * 1440 + (a->hour * 60 + a->minute) - (b->hour * 60 + b->minute);
        break;
    default:
        r = (da - db) * 86400 + (a->hour * 3600 + a->minute * 60 + a->second) -
            (b->hour * 3600 + b->minute * 60 + b->second);
        break;
    }
    return std::to_string(r);
}

std::optional<std::string> gl_date_format(std::span<const Arg> args) {
    static constexpr std::string_view fn = "gl_date_format";
    if (args.empty() || args.size() > 2) return std::nullopt;
    if (!args[0].has_value()) return std::nullopt;
    std::string_view pattern = "yyyyMMdd";
    if (args.size() == 2) {
        if (!args[1].has_value()) throw GlParamError(cat({fn, ": format must not be null"}));
        pattern = *args[1];
    }
    Pattern fmt = Pattern::compile(fn, pattern);
    auto t = parse_input(*args[0]);
    if (!t) return std::nullopt;
    return fmt.format(*t);
}

std::optional<std::string> gl_how_many_day(std::span<const Arg> args) {
    static constexpr std::string_view fn = "gl_how_many_day";
    if (args.size() != 2 || !args[0].has_value()) return std::nullopt;
    const Unit u = lookup_unit(fn, "dtype", args[1], kDtypeUnits);
    auto t = parse_input(*args[0]);
    if (!t) return std::nullopt;
    const int64_t ed = epoch_day(*t);
    switch (u) {
    case YEAR:
        return std::to_string(ed - days_from_civil(t->year, 1, 1) + 1);
    case QUARTER:
        return std::to_string(ed - quarter_first_epoch_day(*t) + 1);
    case MONTH:
        return std::to_string(t->day);
    default:
        return std::to_string(day_of_week_sun0(ed) + 1);
    }
}

std::optional<std::string> gl_date_first(std::span<const Arg> args) {
    return first_or_last("gl_date_first", false, args);
}

std::optional<std::string> gl_date_last(std::span<const Arg> args) {
    return first_or_last("gl_date_last", true, args);
}

} // namespace starrocks::glbk
