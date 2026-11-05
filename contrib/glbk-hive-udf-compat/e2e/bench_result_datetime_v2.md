StarRocks 4.0.17-818b93c, dataset=datetime (table t_hc, 10,000,000 distinct values), rows=10,000,000, pipeline_dop=0, runs=5 (median), udf_thread_pool_size=1, session sets=none

Baseline `SELECT max(d) FROM t_hc` (scan only): 0.060s

| case | Java UDF (JNI) s | built-in s | UDF / built-in | UDF rows/s | built-in rows/s | mismatches |
|---|---|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.612 | 0.515 | 3.1x | 6,202,046 | 19,401,799 | 0 |
| `gl_date_first(d,'month')` | 0.772 | 0.566 | 1.4x | 12,954,860 | 17,679,725 | 0 |
| `gl_date_last(d,'month')` | 0.788 | 0.570 | 1.4x | 12,686,261 | 17,541,612 | 0 |
| `gl_date_delta(d,'day=+3')` | 1.765 | 0.824 | 2.1x | 5,666,309 | 12,136,678 | 0 |
| `gl_date_diff(d,'20000101','day')` | 0.889 | 0.468 | 1.9x | 11,252,650 | 21,351,003 | 0 |
| `gl_how_many_day(d,'year')` | 0.619 | 0.489 | 1.3x | 16,165,881 | 20,443,214 | 0 |
