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

/**
 * StarRocks port of {@code com.glbk.hive.udf.date.gl_now} for one or more arguments: gl_now(format).
 * The zero-argument form gl_now() is {@link GlNowNoArg}, because a StarRocks varargs UDF needs at
 * least one fixed argument and a UDF class may declare only one {@code evaluate} method.
 */
public class GlNow {
    public String evaluate(String... args) {
        if (args.length == 0) {
            return DateUtils.getFormatDate(new Date(), "yyyyMMdd");
        } else if (args.length == 1) {
            String format = args[0].toString();
            return HiveTextCompat.set(DateUtils.getFormatDate(new Date(), format));
        } else {
            return null;
        }
    }
}
