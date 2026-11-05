StarRocks 4.1.7-b81ff9c, dataset=datetime (table t_hc, 10,000,000 distinct values), rows=10,000,000, pipeline_dop=0, runs=5 (median), udf_thread_pool_size=1, session sets=none

Baseline `SELECT max(d) FROM t_hc` (scan only): 0.059s

| case | Java UDF (JNI) s | SQL UDF s | built-in s | SQL UDF / built-in | JNI / built-in | mismatches |
|---|---|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.341 | 0.816 | 0.571 | 1.43x | 2.3x | 0 |
| `gl_date_first(d,'month')` | 0.764 | 0.883 | 0.514 | 1.72x | 1.5x | 0 |
| `gl_date_last(d,'month')` | 0.760 | 0.741 | 0.573 | 1.29x | 1.3x | 0 |
| `gl_date_delta(d,'day=+3')` | 1.593 | 1.365 | 0.879 | 1.55x | 1.8x | 0 |
| `gl_date_diff(d,'20000101','day')` | 0.937 | 1.011 | 0.450 | 2.25x | 2.1x | 0 |
| `gl_how_many_day(d,'year')` | 0.609 | 0.682 | 0.486 | 1.40x | 1.3x | 0 |
