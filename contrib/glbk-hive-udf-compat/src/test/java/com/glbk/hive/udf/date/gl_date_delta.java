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

import org.apache.hive.pdk.HivePdkUnitTest;
import org.apache.hive.pdk.HivePdkUnitTests;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.Text;
import com.glbk.utils.date.DateUtils;

/**
 * <code>gl_date_delta(date,offsets)</code>.
 * 仿python relativedelta实现日期相加操作,返回日期String
 */

@Description(name = "gl_date_delta",
        value = "_FUNC_(date,offsets, [format]) -  As python relativedelta function, get the date/datetime delta calculation in a easy way! \n" +
                "    With this function, you can get any **date/datetime** wanted,  ex. the end day of this month, three months ago, 90 days ago..., \n" +
                "    Include year, month, day , hour, minute, second calculation(add, sub, and set)\n" +
                "    return String value",
        extended = "Params:\n"
                + "    date: String value,  support: yyyyMMdd, yyyyMMddHHmmss, yyyy-MM-dd, yyyy-MM-dd HH:mm:ss\n"
                + "    offsets: delta The unit and length of a date, separated by commas and equal signs\n"
                + "        The units support:  year,month,day,hour,minute,second(y,m,d,h,n,s)\n"
                + "        The length support:  +N,-N,N\n"
                + "        example:  offsetsStr: 'year=+2,month=8,day=-16'  year+2,month=8,day-16\n"
                + "    format:  [optional] out put format, support: yyyyMMdd, yyyyMMddHHmmss, yyyy-MM-dd, yyyy-MM-dd HH:mm:ss\n"
                + "Example:\n"
                + "    SELECT gl_date_delta('20180102030405','day=+3') FROM src LIMIT 1;\n" + "  '20180105030405'\n"
                + "    SELECT gl_date_delta('2018-01-02 03:04:05','day=+3') FROM src LIMIT 1;\n" + "  '2018-01-05 03:04:05'\n"
                + "    SELECT gl_date_delta('20180102','day=+3') FROM src LIMIT 1;\n" + "  '20180105'\n"
                + "    SELECT gl_date_delta('2018-01-02','day=+3') FROM src LIMIT 1;\n" + "  '2018-01-05'\n"
                + "    SELECT gl_date_delta('2018-01-02','day=+3' , 'yyyyMMdd') FROM src LIMIT 1;\n" + "  '20180105'\n"
)
@HivePdkUnitTests(
        setup = "",
        cleanup = "",
        cases = {
                @HivePdkUnitTest(query = "SELECT gl_date_delta('20180102030405','day=+3') FROM onerow;", result = "20180105030405")
        }
)


public class gl_date_delta extends UDF {
    private Text result = new Text();

    /**
     * 接受2个或3个参数
     *
     * @param args
     * @return
     */
    public Text evaluate(Text... args) {
        if (args.length == 1) {
            return args[0]; // 原值返回
        } else if (args.length == 2) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String offsets = args[1] == null ? "" : args[1].toString();
            String format = null;
            String re = DateUtils.dateDeltaStr(dt, offsets, format);
            result.set(re);
            return result;
        } else if (args.length == 3) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String offsets = args[1] == null ? "" : args[1].toString();
            String format = args[2].toString();
            String re = DateUtils.dateDeltaStr(dt, offsets, format);
            result.set(re);
            return result;
        } else {
            return null;
        }

    }
}
