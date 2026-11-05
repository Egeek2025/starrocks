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

import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;

/**
 * Thrown when the Spark dialect cannot translate a statement or a syntax element yet. The dialect catches it and
 * hands the statement over to {@link com.starrocks.sql.parser.dialect.DialectDowngradePolicy}.
 */
public class SparkUnsupportedException extends StarRocksPlannerException {
    public SparkUnsupportedException(String message) {
        super(message, ErrorType.UNSUPPORTED);
    }
}
