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

import com.starrocks.catalog.Table;
import com.starrocks.catalog.TableName;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.ParsedDialect;
import com.starrocks.sql.ast.StatementBase;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Database and table names of a Spark statement are case-insensitive ({@code spark.sql.caseSensitive=false}).
 *
 * <p>The policy, applied in {@code MetadataMgr.getDb/getTable} so that every lookup of the analyzer and of the
 * planner lock collection sees the same object:
 * <ol>
 *   <li>the exact name wins, so objects that differ only by case keep working as they do in the default dialect;</li>
 *   <li>otherwise the names that are equal ignoring case are looked for: one match is the object, several matches
 *   are an ambiguity error, never a silent choice;</li>
 *   <li>the analyzer writes the real names back into the {@link TableName} of the relation, so the privilege check,
 *   the audit log and the dependencies of views use the names of the metadata and not the spelling of the query.</li>
 * </ol>
 *
 * <p>The fallback is active only while a statement parsed by the Spark dialect is analyzed or planned, see
 * {@link #bind}; the parser never changes a name, so objects created with mixed case stay reachable.
 */
public final class SparkCaseInsensitiveNames {
    private SparkCaseInsensitiveNames() {
    }

    /** Restores the session when closed. */
    public static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope(null, false);

        private final ConnectContext context;
        private final boolean previous;

        private Scope(ConnectContext context, boolean previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (context != null) {
                context.setMetadataNameCaseInsensitive(previous);
            }
        }
    }

    /**
     * Turns the case-insensitive lookup on for a statement parsed by the Spark dialect, until the scope is closed.
     * Other statements, a session with {@code spark_case_sensitive = true} and a missing session leave things alone.
     */
    public static Scope bind(ConnectContext context, StatementBase statement) {
        if (context == null || statement == null || statement.getParsedDialect() != ParsedDialect.SPARK
                || context.getSessionVariable().isSparkCaseSensitive()) {
            return Scope.NOOP;
        }
        boolean previous = context.isMetadataNameCaseInsensitive();
        context.setMetadataNameCaseInsensitive(true);
        return new Scope(context, previous);
    }

    /** Whether lookups for the session (the given one, or the one of the thread) fall back to other cases. */
    public static boolean isEnabled(ConnectContext context) {
        ConnectContext session = context != null ? context : ConnectContext.get();
        return session != null && session.isMetadataNameCaseInsensitive();
    }

    /**
     * The name among the candidates that equals the name ignoring case: null if there is none, an error if there are
     * several. The caller has tried the exact name already.
     *
     * @param kind "database" or "table", for the message
     * @param container the database of a table, for the message; may be null
     */
    public static String pick(String kind, String name, Collection<String> candidates, String container) {
        Set<String> matches = new TreeSet<>();
        for (String candidate : candidates) {
            if (candidate != null && candidate.equalsIgnoreCase(name)) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new SemanticException("Ambiguous %s name '%s'%s: it matches %s, use the exact spelling", kind, name,
                    container == null ? "" : " in database '" + container + "'", matches);
        }
        return matches.iterator().next();
    }

    /**
     * Writes the names of the resolved objects into the name of the relation. Only a spelling that differs by case
     * is replaced; the database is the real one of the metadata when known.
     *
     * @param database the name of the database in the metadata, null to ask the table
     */
    public static void writeBack(TableName name, String database, Table table) {
        if (name == null || table == null) {
            return;
        }
        String realDb = database;
        if (realDb == null) {
            try {
                realDb = table.getCatalogDBName();
            } catch (RuntimeException e) {
                // a table of the internal catalog does not know its database; the name is already real
                realDb = null;
            }
        }
        if (realDb != null && name.getDb() != null && realDb.equalsIgnoreCase(name.getDb())
                && !realDb.equals(name.getDb())) {
            name.setDb(realDb);
        }
        String realTable = table.getName();
        if (realTable != null && name.getTbl() != null && realTable.equalsIgnoreCase(name.getTbl())
                && !realTable.equals(name.getTbl())) {
            name.setTbl(realTable);
        }
    }
}
