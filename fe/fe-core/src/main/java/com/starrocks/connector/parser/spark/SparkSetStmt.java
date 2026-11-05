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

package com.starrocks.connector.parser.spark;

import com.starrocks.common.DdlException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryWarning;
import com.starrocks.qe.SparkConfMapper;
import com.starrocks.sql.ast.SetListItem;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.parser.NodePosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link SetStmt} produced by the Spark dialect for session commands: {@code SET k=v}, {@code RESET}, and the
 * statements that Spark accepts but StarRocks does not need (CACHE TABLE, ADD JAR, ...), which become a SET without
 * any variable.
 *
 * <p>The list items of the statement only contain real StarRocks variables, so the statement can be forwarded to the
 * leader FE as plain SQL (see {@code ConnectContext#modifySystemVariable}). What cannot be expressed in the item
 * list is carried by this class and applied by {@code SetExecutor} at execution time:
 * <ul>
 *   <li>notices: warnings for ignored configurations, readable with SHOW WARNINGS. A notice for an unknown
 *   configuration is an error when {@code spark_strict_conf} is on. They cannot be added while parsing because a
 *   statement replaces the warnings of the previous one when it starts executing.</li>
 *   <li>ansi mode: the sql_mode bits that follow {@code spark.sql.ansi.enabled}.</li>
 * </ul>
 */
public class SparkSetStmt extends SetStmt {
    /**
     * A message for the client. {@code unknownKey} marks messages about a configuration key StarRocks knows
     * nothing about, which {@code spark_strict_conf} turns into an error.
     */
    public static final class Notice {
        private final String message;
        private final boolean unknownKey;

        public Notice(String message, boolean unknownKey) {
            this.message = message;
            this.unknownKey = unknownKey;
        }

        public String getMessage() {
            return message;
        }

        public boolean isUnknownKey() {
            return unknownKey;
        }
    }

    public static final String WARNING_CODE = "SPARK_CONF_IGNORED";

    private final List<Notice> notices;
    private final Boolean ansiMode;

    public SparkSetStmt(List<SetListItem> setListItems, List<Notice> notices, Boolean ansiMode, NodePosition pos) {
        super(setListItems, pos);
        this.notices = notices == null ? Collections.emptyList() : new ArrayList<>(notices);
        this.ansiMode = ansiMode;
    }

    /**
     * A statement that does nothing but leaves a warning.
     */
    public static SparkSetStmt noop(String message, NodePosition pos) {
        return new SparkSetStmt(new ArrayList<>(), List.of(new Notice(message, false)), null, pos);
    }

    public List<Notice> getNotices() {
        return notices;
    }

    public Boolean getAnsiMode() {
        return ansiMode;
    }

    /**
     * Called by SetExecutor before any variable is changed.
     *
     * @throws DdlException for an unknown configuration key in strict mode
     */
    public void beforeExecute(ConnectContext ctx) throws DdlException {
        boolean strict = ctx.getSessionVariable().isSparkStrictConf();
        for (Notice notice : notices) {
            if (strict && notice.isUnknownKey()) {
                throw new DdlException(notice.getMessage() + " (spark_strict_conf is on)");
            }
        }
        for (Notice notice : notices) {
            ctx.addWarning(new QueryWarning("Warning", WARNING_CODE, notice.getMessage()));
        }
    }

    /**
     * Called by SetExecutor after the variables were changed.
     */
    public void afterExecute(ConnectContext ctx) throws DdlException {
        if (ansiMode != null) {
            SparkConfMapper.applyAnsiSqlMode(ctx, ansiMode);
        }
    }
}
