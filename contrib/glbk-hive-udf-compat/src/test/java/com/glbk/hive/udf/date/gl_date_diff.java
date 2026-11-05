/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.glbk.hive.udf.date;


import com.glbk.utils.date.DateUtils;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.Text;
import org.apache.hive.pdk.HivePdkUnitTest;
import org.apache.hive.pdk.HivePdkUnitTests;

import java.util.Date;


/**
 * <code>gl_date_diff(date,offsets)</code>.
 * 仿python relativedelta实现日期相加操作,返回日期String
 */

@Description(name = "gl_date_diff",
        value = "_FUNC_(date1,date2,[interval]) - , date diff by interval, return String value",
        extended = "Params:\n"
                + "    date1: String value,  support: yyyyMMdd, yyyyMMddHHmmss, yyyy-MM-dd, yyyy-MM-dd HH:mm:ss\n"
                + "    date2: String value,  support: yyyyMMdd, yyyyMMddHHmmss, yyyy-MM-dd, yyyy-MM-dd HH:mm:ss\n"
                + "    interval:  [optional] year  quarter  month  week  day  hour  minute  second\n"
                + "Example:\n"
                + "    SELECT gl_date_diff('20180101','20180201') FROM src LIMIT 1;\n" + "  '31'\n"
                + "    SELECT gl_date_diff('20180101','20180201','day') FROM src LIMIT 1;\n" + "  '31'\n"
                + "    SELECT gl_date_diff('2018-01-01','2018-02-01','day') FROM src LIMIT 1;\n" + "  '31'\n"
)
@HivePdkUnitTests(
        setup = "",
        cleanup = "",
        cases = {
                @HivePdkUnitTest(query = "SELECT gl_date_diff('2018-01-01','2018-02-01','day') FROM onerow;", result = "31")
        }
)


public class gl_date_diff extends UDF {
    private Text result = new Text();

    /**
     * 接受2个或3个参数
     *
     * @param args
     * @return
     */
    public Text evaluate(Text... args) {
        if (args.length == 1) {
            return null;
        } else if (args.length == 2) {
            if (args[0] == null || args[1] == null) {
                return null;
            }
            String dt1 = args[0].toString();
            String dt2 = args[1].toString();
            Date date1 = DateUtils.parse(dt1);
            Date date2 = DateUtils.parse(dt2);
            long re = DateUtils.dateDiff(date1, date2, "day");
            result.set(String.valueOf(re));
            return result;
        } else if (args.length == 3) {
            if (args[0] == null || args[1] == null) {
                return null;
            }
            String dt1 = args[0].toString();
            String dt2 = args[1].toString();
            String interval = args[2].toString();
            Date date1 = DateUtils.parse(dt1);
            Date date2 = DateUtils.parse(dt2);
            long re = DateUtils.dateDiff(date1, date2, interval);
            result.set(String.valueOf(re));
            return result;
        } else {
            return null;
        }

    }
}
