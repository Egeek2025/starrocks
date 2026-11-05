#!/usr/bin/env python3
# Copyright 2021-present StarRocks, Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Generates SparkFunctionCatalog.java, the Spark functions that the Spark dialect rejects.

usage: gen_function_catalog.py <func_mapping.csv> <spark function adapter source dir> > SparkFunctionCatalog.java

A Spark function is rejected when func_mapping.csv classifies it D (missing in StarRocks) or E (Spark runtime
concept), or when it is one of EXTRA, and the adapter has no rule for it (the rules are found in the adapter sources).
"""

import csv
import glob
import os
import re
import sys

# classes B whose difference cannot be repaired by a rewrite: the result would silently differ
EXTRA = {
    "hash": "hash() is Murmur3 with seed 42 in Spark, the StarRocks hash functions use other seeds and "
            "type rules, so the values differ",
    "xxhash64": "xxhash64() uses seed 42 and Spark's type rules, the StarRocks xx_hash3_64 / xxhash64 "
                "values differ",
    "aes_encrypt": "aes_encrypt defaults to AES-GCM in Spark, StarRocks to AES-128-ECB",
    "aes_decrypt": "aes_decrypt defaults to AES-GCM in Spark, StarRocks to AES-128-ECB",
    "bitmap_count": "the Spark bitmap is a fixed size bit array, not a StarRocks BITMAP",
    "approx_top_k": "the result struct of approx_top_k differs",
    "parse_json": "parse_json returns VARIANT in Spark",
    "variant_get": "VARIANT is not supported",
    "from_json": "from_json needs a schema string converted to a struct type",
    "to_json": "to_json formats JSON text differently from the StarRocks JSON type",
    "schema_of_json": "schema_of_json is not supported",
    "sha": "sha1 is not available in StarRocks",
    "sha1": "sha1 is not available in StarRocks",
    "months_between": "months_between uses a 31 day month and rounding that StarRocks does not have",
    "try_add": "try_add (NULL on overflow) is not supported",
    "try_subtract": "try_subtract (NULL on overflow) is not supported",
    "try_multiply": "try_multiply (NULL on overflow) is not supported",
    "try_sum": "try_sum (NULL on overflow) is not supported",
    "zip_with": "zip_with pads the shorter array with NULL, array_map of two arrays does not",
    "monotonically_increasing_id": "monotonically_increasing_id is not supported",
}

# generators are translated by the query builder, never by a function rewrite
GENERATORS = {"explode", "explode_outer", "posexplode", "posexplode_outer", "inline", "inline_outer", "stack",
              "json_tuple"}


def adapter_names(source_dir):
    names = set()
    for path in glob.glob(os.path.join(source_dir, "*.java")):
        if path.endswith("SparkFunctionCatalog.java"):
            continue
        text = open(path).read()
        names.update(re.findall(r'(?:register|alias)\("([a-z_0-9]+)"', text))
        for group in re.findall(r"new String\[\] \{([^}]*)\}", text):
            names.update(re.findall(r'"([a-z_0-9]+)"', group))
    return names


def java_string(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    csv_path, source_dir = sys.argv[1], sys.argv[2]
    implemented = adapter_names(source_dir)
    entries = {}
    with open(csv_path) as f:
        for row in csv.DictReader(f):
            name = row["spark_name"].strip().lower()
            if not re.fullmatch(r"[a-z_0-9]+", name) or name in GENERATORS or name in implemented:
                continue
            if row["category"] in ("operator", "scalar/cast"):
                continue
            if row["class"] in ("D", "E") or name in EXTRA:
                reason = EXTRA.get(name)
                if reason is None:
                    detail = (row["strategy"] or row["difference"]).strip()
                    reason = "no StarRocks equivalent yet" + (" (" + detail + ")" if detail else "")
                entries[name] = reason
    for name, reason in EXTRA.items():
        if name not in implemented:
            entries.setdefault(name, reason)
    print(HEADER)
    for name in sorted(entries):
        print("        put(map, %s,\n                %s);" % (java_string(name), java_string(entries[name])))
    print(FOOTER)


HEADER = """// Copyright 2021-present StarRocks, Inc. All rights reserved.
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

package com.starrocks.connector.parser.spark.function;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Spark functions that the Spark dialect rejects instead of running them with different semantics.
 *
 * <p>GENERATED by tools/spark-compat/gen_function_catalog.py from func_mapping.csv, do not edit by hand. A function
 * that has a rule in {@link Spark2SRFunctionCallTransformer} is not listed here.
 */
public final class SparkFunctionCatalog {
    private static final Map<String, String> UNSUPPORTED = build();

    private SparkFunctionCatalog() {
    }

    /** The reason why the function is not supported, null when it is not on the list. */
    public static String unsupportedReason(String sparkName) {
        return UNSUPPORTED.get(sparkName);
    }

    public static boolean isUnsupported(String sparkName) {
        return UNSUPPORTED.containsKey(sparkName);
    }

    public static Map<String, String> unsupportedFunctions() {
        return Collections.unmodifiableMap(UNSUPPORTED);
    }

    private static void put(Map<String, String> map, String name, String reason) {
        map.put(name, reason);
    }

    private static Map<String, String> build() {
        Map<String, String> map = new TreeMap<>();"""

FOOTER = """        return map;
    }
}"""


if __name__ == "__main__":
    main()
