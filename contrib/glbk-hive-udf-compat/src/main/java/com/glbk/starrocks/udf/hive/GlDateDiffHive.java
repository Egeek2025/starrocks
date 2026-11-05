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

import java.util.Date;

/** StarRocks port of {@code com.glbk.hive.udf.date.gl_date_diff}: gl_date_diff(date1, date2[, interval]). */
public class GlDateDiffHive {
    public String evaluate(String... args) {
        if (args.length == 1) {
            return null;
        } else if (args.length == 2) {
            if (args[0] == null || args[1] == null) {
                return null;
            }
            Date date1 = DateUtils.parse(args[0].toString());
            Date date2 = DateUtils.parse(args[1].toString());
            return String.valueOf(DateUtils.dateDiff(date1, date2, "day"));
        } else if (args.length == 3) {
            if (args[0] == null || args[1] == null) {
                return null;
            }
            String interval = args[2].toString();
            Date date1 = DateUtils.parse(args[0].toString());
            Date date2 = DateUtils.parse(args[1].toString());
            return String.valueOf(DateUtils.dateDiff(date1, date2, interval));
        } else {
            return null;
        }
    }
}
