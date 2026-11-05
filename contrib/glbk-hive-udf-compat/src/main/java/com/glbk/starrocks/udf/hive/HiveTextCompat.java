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

package com.glbk.starrocks.udf.hive;

/**
 * Mirrors the observable behaviour of Hive's {@code Text.set(String)} on the result path:
 * the original UDFs call {@code result.set(re)}, which throws a NullPointerException when
 * {@code re} is null (for example an unknown dtype). Hive then fails the query, so we fail too
 * instead of silently returning NULL.
 */
final class HiveTextCompat {
    private HiveTextCompat() {
    }

    static String set(String re) {
        if (re == null) {
            throw new NullPointerException("result is null (Hive Text.set(null) would fail the query)");
        }
        return re;
    }
}
