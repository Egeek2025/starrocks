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

import com.starrocks.catalog.InternalCatalog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.CatalogMgr;
import com.starrocks.server.GlobalStateMgr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Catalog handling of the names in Spark statements. Spark's session catalog is {@code spark_catalog}, which maps to
 * the catalog of the session variable {@code spark_default_catalog}.
 *
 * <p>Statements are translated while parsing, so the session is read as it is at that time (best effort; a statement
 * that comes after a {@code SET spark_default_catalog} in the same script sees the old value).
 */
final class SparkCatalogNames {
    static final String SPARK_CATALOG = "spark_catalog";

    private final SessionVariable sessionVariable;

    SparkCatalogNames(SessionVariable sessionVariable) {
        this.sessionVariable = sessionVariable;
    }

    /**
     * The catalog that {@code spark_catalog} stands for.
     */
    String defaultCatalog() {
        if (sessionVariable == null) {
            return InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME;
        }
        String catalog = sessionVariable.getSparkDefaultCatalog();
        return catalog == null || catalog.isEmpty() ? InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME : catalog;
    }

    private boolean hasCustomDefaultCatalog() {
        return !CatalogMgr.isInternalCatalog(defaultCatalog());
    }

    /**
     * Maps a one to three part name: {@code spark_catalog.db.t} gets the default catalog of Spark. A {@code db.t}
     * name gets it too when {@code spark_default_catalog} is not the internal catalog, because Spark has no
     * catalog-less names. In the default setup, names are left for the analyzer to complete.
     */
    List<String> qualify(List<String> parts) {
        List<String> result = new ArrayList<>(parts);
        if (parts.size() == 3 && parts.get(0).equalsIgnoreCase(SPARK_CATALOG)) {
            result.set(0, defaultCatalog());
        } else if (parts.size() == 2 && hasCustomDefaultCatalog()) {
            result.add(0, defaultCatalog());
        }
        return result;
    }

    /**
     * Maps the catalog name of a USE or SHOW statement.
     */
    String mapCatalog(String catalog) {
        return catalog != null && catalog.equalsIgnoreCase(SPARK_CATALOG) ? defaultCatalog() : catalog;
    }

    /**
     * The catalog a table of the (already mapped) name is created in, or null if that is not known while parsing.
     */
    String catalogOf(List<String> qualifiedParts) {
        if (qualifiedParts.size() == 3) {
            return qualifiedParts.get(0);
        }
        ConnectContext ctx = ConnectContext.get();
        String current = ctx == null ? null : ctx.getCurrentCatalog();
        return current == null || current.isEmpty() ? InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME : current;
    }

    /**
     * The connector type of an external catalog in lower case (hive, iceberg, ...), or null for the internal
     * catalog and for a catalog that does not exist (the analyzer reports it).
     */
    static String externalCatalogType(String catalog) {
        if (catalog == null || CatalogMgr.isInternalCatalog(catalog)) {
            return null;
        }
        try {
            GlobalStateMgr gsm = GlobalStateMgr.getCurrentState();
            if (gsm == null || !gsm.getCatalogMgr().catalogExists(catalog)) {
                return null;
            }
            String type = gsm.getCatalogMgr().getCatalogType(catalog);
            return type == null ? null : type.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
