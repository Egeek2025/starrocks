StarRocks 4.0.17-818b93c, dataset=date (table t, 9,000 distinct values), rows=10,000,000, pipeline_dop=0, runs=5 (median), udf_thread_pool_size=1, session sets=['cbo_enable_low_cardinality_optimize=false']

Baseline `SELECT max(d) FROM t` (scan only): 0.052s

| case | Java UDF (JNI) s | built-in s | UDF / built-in | UDF rows/s | built-in rows/s | mismatches |
|---|---|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.682 | 0.410 | 4.1x | 5,945,738 | 24,366,542 | 0 |
| `gl_date_first(d,'month')` | 0.984 | 0.481 | 2.0x | 10,161,076 | 20,802,247 | 0 |
| `gl_date_last(d,'month')` | 0.942 | 0.463 | 2.0x | 10,620,376 | 21,581,830 | 0 |
| `gl_date_delta(d,'day=+3')` | 2.242 | 0.384 | 5.8x | 4,459,771 | 26,056,457 | 0 |
| `gl_date_diff(d,'20000101','day')` | 0.920 | 0.351 | 2.6x | 10,873,841 | 28,476,454 | 0 |
| `gl_how_many_day(d,'year')` | 0.553 | 0.345 | 1.6x | 18,070,019 | 28,963,350 | 0 |
