StarRocks 4.0.17-818b93c, rows=10,000,000, pipeline_dop=0, runs=5 (median), udf_thread_pool_size=1

Baseline `SELECT max(d) FROM t` (scan only): 0.056s

| case | Java UDF (JNI) s | built-in s | UDF / built-in | UDF rows/s | built-in rows/s | mismatches |
|---|---|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.548 | 0.409 | 3.8x | 6,459,338 | 24,473,272 | 0 |
| `gl_date_first(d,'month')` | 0.932 | 0.403 | 2.3x | 10,726,349 | 24,813,141 | 0 |
| `gl_date_last(d,'month')` | 1.010 | 0.438 | 2.3x | 9,898,158 | 22,852,931 | 0 |
| `gl_date_delta(d,'day=+3')` | 2.137 | 0.403 | 5.3x | 4,678,892 | 24,815,446 | 0 |
| `gl_date_diff(d,'20000101','day')` | 0.865 | 0.331 | 2.6x | 11,555,832 | 30,214,338 | 0 |
| `gl_how_many_day(d,'year')` | 0.542 | 0.344 | 1.6x | 18,454,047 | 29,037,445 | 0 |
