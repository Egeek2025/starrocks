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
import statistics
import time

import pymysql

DB = "glbk_bench"

# name, UDF expression, built-in expression with the same result on well-formed yyyyMMdd input
CASES = [
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


def connect():
    return pymysql.connect(host="127.0.0.1", port=9030, user="root", autocommit=True)


def run(cur, sql):
    t0 = time.perf_counter()
    cur.execute(sql)
    rows = cur.fetchall()
    return time.perf_counter() - t0, rows


def prepare(cur, n):
    cur.execute(f"CREATE DATABASE IF NOT EXISTS {DB}")
    cur.execute(f"USE {DB}")
    cur.execute("SHOW TABLES LIKE 't'")
    if cur.fetchall():
        cur.execute("SELECT count(*) FROM t")
        if cur.fetchone()[0] == n:
            return
        cur.execute("DROP TABLE t")
    cur.execute("""CREATE TABLE t (id BIGINT NOT NULL, d STRING NOT NULL)
                   DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 8
                   PROPERTIES ("replication_num" = "1")""")
    # dates spread over ~25 years, yyyyMMdd strings
    cur.execute(f"""INSERT INTO t SELECT generate_series,
                    date_format(days_add('2000-01-01', generate_series % 9000), '%Y%m%d')
                    FROM TABLE(generate_series(1, {n}))""")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=10_000_000)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--out", default="bench_result.md")
    a = ap.parse_args()

    con = connect()
    cur = con.cursor()
    prepare(cur, a.rows)
    cur.execute("SET enable_query_cache = false")
    cur.execute("SHOW VARIABLES LIKE 'pipeline_dop'")
    dop = cur.fetchone()[1]
    cur.execute("SELECT current_version()")
    version = cur.fetchone()[0]
    cur.execute("SELECT value FROM information_schema.be_configs WHERE name = 'udf_thread_pool_size'")
    udf_pool = ",".join(str(r[0]) for r in cur.fetchall())

    results = []
    for name, udf, builtin in CASES:
        # correctness: both expressions must agree on every row
        cur.execute(f"SELECT count(*) FROM t WHERE {udf} <> {builtin}")
        mismatches = cur.fetchone()[0]
        times = {}
        for label, expr in (("udf", udf), ("builtin", builtin)):
            sql = f"SELECT max({expr}) FROM t"
            run(cur, sql)  # warm-up
            samples = [run(cur, sql)[0] for _ in range(a.runs)]
            times[label] = statistics.median(samples)
        ratio = times["udf"] / times["builtin"]
        results.append((name, times["udf"], times["builtin"], ratio, mismatches))
        print(f"{name:38s} udf {times['udf']:7.3f}s  builtin {times['builtin']:7.3f}s  "
              f"x{ratio:6.1f}  mismatches={mismatches}", flush=True)

    # baseline: scanning the column without any function
    run(cur, "SELECT max(d) FROM t")
    scan = statistics.median([run(cur, "SELECT max(d) FROM t")[0] for _ in range(a.runs)])

    with open(a.out, "w", encoding="utf-8") as f:
        f.write(f"StarRocks {version}, rows={a.rows:,}, pipeline_dop={dop}, runs={a.runs} (median), "
                f"udf_thread_pool_size={udf_pool}\n\n")
        f.write(f"Baseline `SELECT max(d) FROM t` (scan only): {scan:.3f}s\n\n")
        f.write("| case | Java UDF (JNI) s | built-in s | UDF / built-in | UDF rows/s | built-in rows/s | mismatches |\n")
        f.write("|---|---|---|---|---|---|---|\n")
        for name, tu, tb, r, mm in results:
            f.write(f"| `{name}` | {tu:.3f} | {tb:.3f} | {r:.1f}x | {a.rows / tu:,.0f} | {a.rows / tb:,.0f} | {mm} |\n")
    print(f"scan baseline {scan:.3f}s; wrote {a.out}")


if __name__ == "__main__":
    main()
