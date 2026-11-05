#!/usr/bin/env python3
"""Benchmark: glbk Java UDFs (BE JNI) vs equivalent StarRocks built-in expressions.

Usage (cluster up and functions registered, e.g. after ./run_e2e.sh smoke.csv):
    python3 bench.py [--rows 10000000] [--runs 5] [--out bench_result.md]

For every case the script first checks that the UDF and the built-in expression return the same values on
the whole table (otherwise the comparison would be meaningless), then runs each query once to warm up and
--runs more times, and reports the median wall time and rows/s. Queries aggregate with max() so only one
row comes back and the time is dominated by evaluating the expression.
"""
import argparse
import re
import statistics
import time

import pymysql

DB = "glbk_bench"

# name, UDF expression, built-in expression with the same result on well-formed yyyyMMdd input
CASES_DATE = [
    ("gl_date_format(d,'yyyy-MM-dd')",
     "gl_date_format(d,'yyyy-MM-dd')",
     "date_format(str_to_date(d,'%Y%m%d'),'%Y-%m-%d')"),
    ("gl_date_first(d,'month')",
     "gl_date_first(d,'month')",
     "date_format(date_trunc('month', str_to_date(d,'%Y%m%d')),'%Y%m%d')"),
    ("gl_date_last(d,'month')",
     "gl_date_last(d,'month')",
     "date_format(last_day(str_to_date(d,'%Y%m%d')),'%Y%m%d')"),
    ("gl_date_delta(d,'day=+3')",
     "gl_date_delta(d,'day=+3')",
     "date_format(days_add(str_to_date(d,'%Y%m%d'),3),'%Y%m%d')"),
    ("gl_date_diff(d,'20000101','day')",
     "gl_date_diff(d,'20000101','day')",
     "cast(datediff(str_to_date(d,'%Y%m%d'),'2000-01-01') as string)"),
    ("gl_how_many_day(d,'year')",
     "gl_how_many_day(d,'year')",
     "cast(dayofyear(str_to_date(d,'%Y%m%d')) as string)"),
]


# Same functions on 14-digit yyyyMMddHHmmss strings that are distinct on every row (no dictionary reuse).
CASES_DATETIME = [
    ("gl_date_format(d,'yyyy-MM-dd')",
     "gl_date_format(d,'yyyy-MM-dd')",
     "date_format(str_to_date(d,'%Y%m%d%H%i%s'),'%Y-%m-%d')"),
    ("gl_date_first(d,'month')",
     "gl_date_first(d,'month')",
     "date_format(date_trunc('month', str_to_date(d,'%Y%m%d%H%i%s')),'%Y%m%d')"),
    ("gl_date_last(d,'month')",
     "gl_date_last(d,'month')",
     "date_format(last_day(str_to_date(d,'%Y%m%d%H%i%s')),'%Y%m%d')"),
    ("gl_date_delta(d,'day=+3')",
     "gl_date_delta(d,'day=+3')",
     "date_format(days_add(str_to_date(d,'%Y%m%d%H%i%s'),3),'%Y%m%d%H%i%s')"),
    ("gl_date_diff(d,'20000101','day')",
     "gl_date_diff(d,'20000101','day')",
     "cast(datediff(str_to_date(d,'%Y%m%d%H%i%s'),'2000-01-01') as string)"),
    ("gl_how_many_day(d,'year')",
     "gl_how_many_day(d,'year')",
     "cast(dayofyear(str_to_date(d,'%Y%m%d%H%i%s')) as string)"),
]


def connect():
    return pymysql.connect(host="127.0.0.1", port=9030, user="root", autocommit=True)


def run(cur, sql):
    t0 = time.perf_counter()
    cur.execute(sql)
    rows = cur.fetchall()
    return time.perf_counter() - t0, rows


def prepare(cur, n, table, value_expr):
    cur.execute(f"CREATE DATABASE IF NOT EXISTS {DB}")
    cur.execute(f"USE {DB}")
    cur.execute(f"SHOW TABLES LIKE '{table}'")
    if cur.fetchall():
        cur.execute(f"SELECT count(*) FROM {table}")
        if cur.fetchone()[0] == n:
            return
        cur.execute(f"DROP TABLE {table}")
    cur.execute(f"""CREATE TABLE {table} (id BIGINT NOT NULL, d STRING NOT NULL)
                   DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 8
                   PROPERTIES ("replication_num" = "1")""")
    cur.execute(f"""INSERT INTO {table} SELECT generate_series, {value_expr}
                    FROM TABLE(generate_series(1, {n}))""")


DATASETS = {
    # ~9000 distinct yyyyMMdd values over ~25 years (low cardinality, typical partition-date column)
    "date": ("t", "date_format(days_add('2000-01-01', generate_series % 9000), '%Y%m%d')", CASES_DATE),
    # one distinct yyyyMMddHHmmss value per row (high cardinality, typical event-time column)
    "datetime": ("t_hc", "date_format(seconds_add('2000-01-01 00:00:00', generate_series * 37), '%Y%m%d%H%i%s')",
                 CASES_DATETIME),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=10_000_000)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--out", default="bench_result.md")
    ap.add_argument("--dataset", choices=sorted(DATASETS), default="date")
    ap.add_argument("--jni-suffix", default="",
                    help="also time the Java UDF under gl_*<suffix> (e.g. _jni when gl_* are SQL UDFs, "
                         "see register_sqludf.py); the UDF column then measures the SQL UDF")
    ap.add_argument("--set", action="append", default=[], metavar="VAR=VAL",
                    help="session variable, e.g. cbo_enable_low_cardinality_optimize=false")
    a = ap.parse_args()

    con = connect()
    cur = con.cursor()
    table, value_expr, cases = DATASETS[a.dataset]
    prepare(cur, a.rows, table, value_expr)
    cur.execute("SET enable_query_cache = false")
    for kv in a.set:
        k, v = kv.split("=", 1)
        cur.execute(f"SET {k} = {v}")
    cur.execute(f"SELECT count(DISTINCT d) FROM {table}")
    distinct = cur.fetchone()[0]
    cur.execute("SHOW VARIABLES LIKE 'pipeline_dop'")
    dop = cur.fetchone()[1]
    cur.execute("SELECT current_version()")
    version = cur.fetchone()[0]
    cur.execute("SELECT value FROM information_schema.be_configs WHERE name = 'udf_thread_pool_size'")
    udf_pool = ",".join(str(r[0]) for r in cur.fetchall())

    results = []
    for name, udf, builtin in cases:
        # correctness: both expressions must agree on every row
        cur.execute(f"SELECT count(*) FROM {table} WHERE {udf} <> {builtin}")
        mismatches = cur.fetchone()[0]
        times = {}
        variants = [("udf", udf), ("builtin", builtin)]
        if a.jni_suffix:
            jni = re.sub(r"\b(gl_[a-z_]+)\(", lambda m: m.group(1) + a.jni_suffix + "(", udf, count=1)
            variants.append(("jni", jni))
        for label, expr in variants:
            sql = f"SELECT max({expr}) FROM {table}"
            run(cur, sql)  # warm-up
            samples = [run(cur, sql)[0] for _ in range(a.runs)]
            times[label] = statistics.median(samples)
        ratio = times["udf"] / times["builtin"]
        results.append((name, times["udf"], times["builtin"], ratio, mismatches, times.get("jni")))
        extra = f"  jni {times['jni']:7.3f}s" if "jni" in times else ""
        print(f"{name:38s} udf {times['udf']:7.3f}s  builtin {times['builtin']:7.3f}s  "
              f"x{ratio:6.1f}  mismatches={mismatches}{extra}", flush=True)

    # baseline: scanning the column without any function
    run(cur, f"SELECT max(d) FROM {table}")
    scan = statistics.median([run(cur, f"SELECT max(d) FROM {table}")[0] for _ in range(a.runs)])

    with open(a.out, "w", encoding="utf-8") as f:
        f.write(f"StarRocks {version}, dataset={a.dataset} (table {table}, {distinct:,} distinct values), "
                f"rows={a.rows:,}, pipeline_dop={dop}, runs={a.runs} (median), "
                f"udf_thread_pool_size={udf_pool}, session sets={a.set or 'none'}\n\n")
        f.write(f"Baseline `SELECT max(d) FROM {table}` (scan only): {scan:.3f}s\n\n")
        if a.jni_suffix:
            f.write("| case | Java UDF (JNI) s | SQL UDF s | built-in s | SQL UDF / built-in | JNI / built-in | mismatches |\n")
            f.write("|---|---|---|---|---|---|---|\n")
            for name, tu, tb, r, mm, tj in results:
                f.write(f"| `{name}` | {tj:.3f} | {tu:.3f} | {tb:.3f} | {r:.2f}x | {tj / tb:.1f}x | {mm} |\n")
        else:
            f.write("| case | Java UDF (JNI) s | built-in s | UDF / built-in | UDF rows/s | built-in rows/s | mismatches |\n")
            f.write("|---|---|---|---|---|---|---|\n")
            for name, tu, tb, r, mm, _ in results:
                f.write(f"| `{name}` | {tu:.3f} | {tb:.3f} | {r:.1f}x | {a.rows / tu:,.0f} | {a.rows / tb:,.0f} | {mm} |\n")
    print(f"scan baseline {scan:.3f}s; wrote {a.out}")


if __name__ == "__main__":
    main()
