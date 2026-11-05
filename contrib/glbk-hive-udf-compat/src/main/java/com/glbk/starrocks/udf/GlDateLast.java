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

package com.glbk.starrocks.udf;

import com.glbk.utils.date.DateUtils;

import java.util.Date;

/** StarRocks port of {@code com.glbk.hive.udf.date.gl_date_last}: gl_date_last(date, dtype). */
public class GlDateLast {
    public String evaluate(String... args) {
        if (args.length == 2) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String dtype = args[1].toString();
            Date date = DateUtils.parse(dt);
            return HiveTextCompat.set(DateUtils.getLastDay(date, dtype));
        } else {
            return null;
        }
    }
}
