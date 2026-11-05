StarRocks 4.1.7-b81ff9c, dataset=date (table t, 9,000 distinct values), rows=10,000,000, pipeline_dop=0, runs=5 (median), udf_thread_pool_size=1, session sets=none

Baseline `SELECT max(d) FROM t` (scan only): 0.048s

| case | Java UDF (JNI) s | SQL UDF s | built-in s | SQL UDF / built-in | JNI / built-in | mismatches |
|---|---|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.384 | 0.604 | 0.510 | 1.18x | 2.7x | 0 |
| `gl_date_first(d,'month')` | 0.713 | 0.932 | 0.374 | 2.49x | 1.9x | 0 |
| `gl_date_last(d,'month')` | 0.723 | 0.577 | 0.442 | 1.31x | 1.6x | 0 |
| `gl_date_delta(d,'day=+3')` | 1.791 | 0.712 | 0.385 | 1.85x | 4.6x | 0 |
| `gl_date_diff(d,'20000101','day')` | 0.873 | 0.834 | 0.312 | 2.68x | 2.8x | 0 |
| `gl_how_many_day(d,'year')` | 0.598 | 0.475 | 0.352 | 1.35x | 1.7x | 0 |
