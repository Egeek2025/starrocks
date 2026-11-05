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

package com.starrocks.sql.parser.dialect;

import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.SqlParser;

import java.util.List;

/**
 * The native StarRocks dialect, which is also the fallback of every other dialect.
 */
public class StarRocksDialect implements SqlDialect {
    public static final String NAME = "starrocks";

    public static final StarRocksDialect INSTANCE = new StarRocksDialect();

    private StarRocksDialect() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "StarRocks";
    }

    @Override
    public List<StatementBase> parse(String sql, SessionVariable sessionVariable) {
        return SqlParser.parseWithStarRocksDialect(sql, sessionVariable);
    }
}
