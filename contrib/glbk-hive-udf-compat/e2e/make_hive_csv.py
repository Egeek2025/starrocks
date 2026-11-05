#!/usr/bin/env python3
"""Derive samples_hive.csv from ../samples/samples.csv for the reconciliation set (gl_*_hive).

Each sample SQL is rewritten to call gl_xxx_hive(...) and checked against the original Hive result
(hive_result). Zero-argument calls of varargs functions cannot be resolved by StarRocks at all
(SR_NO_ZERO_ARG_SIGNATURE), so they are expected to fail there too.
Usage: python3 make_hive_csv.py [../samples/samples.csv] [samples_hive.csv]
"""
import csv
import re
import sys

src = sys.argv[1] if len(sys.argv) > 1 else "../samples/samples.csv"
dst = sys.argv[2] if len(sys.argv) > 2 else "samples_hive.csv"
with open(src, newline="", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))
with open(dst, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["sql", "expected"])
    for r in rows:
        sql = re.sub(r"\b(gl_[a-z_]+?)\(", lambda m: m.group(1) + "_hive(", r["sql"], count=1)
        expected = "ERROR" if r["diff_category"] == "SR_NO_ZERO_ARG_SIGNATURE" else r["hive_result"]
        w.writerow([sql, expected])
print(f"wrote {dst}: {len(rows)} rows")
