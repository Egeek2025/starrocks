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
 * <code>gl_how_many_day(date,offsets)</code>.
 * 从年初，季度出，月初到现在有多少天
 */

@Description(name = "gl_how_many_day",
        value = "_FUNC_(date, dtype) - , get how many day from begin dtype, return String value",
        extended = "Params:\n"
                + "    date: String value,  support: yyyyMMdd, yyyyMMddHHmmss, yyyy-MM-dd, yyyy-MM-dd HH:mm:ss\n"
                + "    dtype:  support: year  quarter  month  week\n"
                + "Example:\n"
                + "    SELECT gl_how_many_day('20190417','year');\n" + "  '107'\n"
                + "    SELECT gl_how_many_day('2019-04-17','year');\n" + "  '107'\n"
                + "    SELECT gl_how_many_day('20190417','quarter');\n" + "  '17'\n"
                + "    SELECT gl_how_many_day('2019-04-17','quarter');\n" + "  '17'\n"
                + "    SELECT gl_how_many_day('20190417','month');\n" + "  '17'\n"
                + "    SELECT gl_how_many_day('2019-04-17','month');\n" + "  '17'\n"
                + "    SELECT gl_how_many_day('20190417','week');\n" + "  '4'\n"
                + "    SELECT gl_how_many_day('2019-04-17','week');\n" + "  '4'\n"
)
@HivePdkUnitTests(
        setup = "",
        cleanup = "",
        cases = {
                @HivePdkUnitTest(query = "SELECT gl_how_many_day('20190417','year') FROM onerow;", result = "107"),
                @HivePdkUnitTest(query = "SELECT gl_how_many_day('20190417','quarter') FROM onerow;", result = "17")
        }
)


public class gl_how_many_day extends UDF {
    private Text result = new Text();

    /**
     * 接受1个或2个参数
     *
     * @param args
     * @return
     */
    public Text evaluate(Text... args) {
        if (args.length == 2) {
            if (args[0] == null) {
                return null;
            }
            String dt = args[0].toString();
            String dtype = args[1].toString();
            Date date = DateUtils.parse(dt);
            int re = DateUtils.getHowManyDay(date, dtype);
            result.set(String.valueOf(re));
            return result;
        } else {
            return null;
        }

    }
}
