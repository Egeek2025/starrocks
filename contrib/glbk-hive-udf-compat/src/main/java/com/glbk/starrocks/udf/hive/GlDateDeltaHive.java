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

import com.glbk.utils.date.DateUtils;

/** StarRocks port of {@code com.glbk.hive.udf.date.gl_date_delta}: gl_date_delta(date, offsets[, format]). */
public class GlDateDeltaHive {
    public String evaluate(String... args) {
        if (args.length == 1) {
            return args[0];
        } else if (args.length == 2) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String offsets = args[1] == null ? "" : args[1].toString();
            String format = null;
            return HiveTextCompat.set(DateUtils.dateDeltaStr(dt, offsets, format));
        } else if (args.length == 3) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String offsets = args[1] == null ? "" : args[1].toString();
            String format = args[2].toString();
            return HiveTextCompat.set(DateUtils.dateDeltaStr(dt, offsets, format));
        } else {
            return null;
        }
    }
}
