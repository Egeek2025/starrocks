StarRocks 4.0.17-818b93c, dataset=date (table t, 9,000 distinct values), rows=10,000,000, pipeline_dop=0, runs=5 (median), udf_thread_pool_size=1, session sets=none

Baseline `SELECT max(d) FROM t` (scan only): 0.057s

| case | Java UDF (JNI) s | built-in s | UDF / built-in | UDF rows/s | built-in rows/s | mismatches |
|---|---|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.307 | 0.398 | 3.3x | 7,648,908 | 25,133,822 | 0 |
| `gl_date_first(d,'month')` | 0.743 | 0.404 | 1.8x | 13,460,989 | 24,753,861 | 0 |
| `gl_date_last(d,'month')` | 0.721 | 0.402 | 1.8x | 13,866,581 | 24,893,588 | 0 |
| `gl_date_delta(d,'day=+3')` | 1.342 | 0.385 | 3.5x | 7,451,835 | 25,994,986 | 0 |
| `gl_date_diff(d,'20000101','day')` | 0.777 | 0.321 | 2.4x | 12,869,489 | 31,164,637 | 0 |
| `gl_how_many_day(d,'year')` | 0.520 | 0.351 | 1.5x | 19,223,177 | 28,517,853 | 0 |
