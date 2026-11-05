StarRocks 4.0.17-818b93c, dataset=datetime (table t_hc, 10,000,000 distinct values), rows=10,000,000, pipeline_dop=0, runs=5 (median), udf_thread_pool_size=1, session sets=none

Baseline `SELECT max(d) FROM t_hc` (scan only): 0.065s

| case | Java UDF (JNI) s | built-in s | UDF / built-in | UDF rows/s | built-in rows/s | mismatches |
|---|---|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.492 | 0.537 | 2.8x | 6,701,298 | 18,629,008 | 0 |
| `gl_date_first(d,'month')` | 1.038 | 0.528 | 2.0x | 9,637,434 | 18,944,070 | 0 |
| `gl_date_last(d,'month')` | 1.042 | 0.521 | 2.0x | 9,600,805 | 19,211,181 | 0 |
| `gl_date_delta(d,'day=+3')` | 2.523 | 0.858 | 2.9x | 3,963,303 | 11,654,909 | 0 |
| `gl_date_diff(d,'20000101','day')` | 0.901 | 0.457 | 2.0x | 11,101,410 | 21,901,994 | 0 |
| `gl_how_many_day(d,'year')` | 0.652 | 0.565 | 1.2x | 15,331,569 | 17,700,803 | 0 |
