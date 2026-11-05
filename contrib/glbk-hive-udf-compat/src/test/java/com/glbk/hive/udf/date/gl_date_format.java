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

import java.util.Date;

import com.glbk.utils.date.DateUtils;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.Text;
import org.apache.hive.pdk.HivePdkUnitTest;
import org.apache.hive.pdk.HivePdkUnitTests;

/**
 * <code>gl_date_format(date,offsets)</code>.
 * 仿python relativedelta实现日期相加操作,返回日期String
 */

@Description(name = "gl_date_format",
        value = "_FUNC_(date,[format]) - , date format, return String value",
        extended = "Params:\n"
                + "    date: String value,  support: yyyyMMdd, yyyyMMddHHmmss, yyyy-MM-dd, yyyy-MM-dd HH:mm:ss\n"
                + "    format:  [optional] out put format, support: yyyyMMdd, yyyyMMddHHmmss, yyyy-MM-dd, yyyy-MM-dd HH:mm:ss\n"
                + "Example:\n"
                + "    SELECT gl_date_format('20180102030405','yyyyMMdd') FROM src LIMIT 1;\n" + "  '20180102'\n"
                + "    SELECT gl_date_format('20180102030405','yyyy-MM-dd') FROM src LIMIT 1;\n" + "  '2018-01-02'\n"
                + "    SELECT gl_date_format('2018-01-02 03:04:05','yyyyMMdd') FROM src LIMIT 1;\n" + "  '20180102'\n"
)
@HivePdkUnitTests(
        setup = "",
        cleanup = "",
        cases = {
                @HivePdkUnitTest(query = "SELECT gl_date_format('20180102030405','yyyy-MM-dd') FROM onerow;", result = "2018-01-02")
        }
)


public class gl_date_format extends UDF {
    private Text result = new Text();

    /**
     * 接受1个或2个参数
     *
     * @param args
     * @return
     */
    public Text evaluate(Text... args) {
        if (args.length == 1) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String format = "yyyyMMdd";
            Date date = DateUtils.parse(dt);
            String re = DateUtils.getFormatDate(date, format);
            result.set(re);
            return result;
        } else if (args.length == 2) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String format = args[1].toString();
            Date date = DateUtils.parse(dt);
            String re = DateUtils.getFormatDate(date, format);
            result.set(re);
            return result;
        } else {
            return null;
        }

    }
}
