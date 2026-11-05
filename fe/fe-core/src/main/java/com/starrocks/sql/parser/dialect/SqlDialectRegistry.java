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

import com.starrocks.connector.parser.spark.SparkDialect;
import com.starrocks.connector.parser.trino.TrinoDialect;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the dialects selectable through {@code sql_dialect}. Names are case-insensitive. An unknown name
 * resolves to {@link StarRocksDialect}, which keeps the historical behaviour of the free-form session variable.
 */
public final class SqlDialectRegistry {
    private static final Map<String, SqlDialect> DIALECTS = new ConcurrentHashMap<>();

    static {
        register(StarRocksDialect.INSTANCE);
        register(TrinoDialect.INSTANCE);
        register(SparkDialect.INSTANCE);
    }

    private SqlDialectRegistry() {
    }

    public static void register(SqlDialect dialect) {
        DIALECTS.put(dialect.name().toLowerCase(Locale.ROOT), dialect);
    }

    /**
     * @param name value of {@code sql_dialect}, may be null
     * @return the dialect registered for the name, or the StarRocks dialect when the name is unknown
     */
    public static SqlDialect get(String name) {
        if (name == null) {
            return StarRocksDialect.INSTANCE;
        }
        return DIALECTS.getOrDefault(name.toLowerCase(Locale.ROOT), StarRocksDialect.INSTANCE);
    }

    public static boolean contains(String name) {
        return name != null && DIALECTS.containsKey(name.toLowerCase(Locale.ROOT));
    }
}
