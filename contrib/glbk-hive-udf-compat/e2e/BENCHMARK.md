# Java UDF（BE 通过 JNI 调用）与内置函数的性能对比

## 测试方法

- **环境**：本仓库 docker 环境里的 StarRocks 4.0.17 单机集群（FE 和 BE 同机），4 vCPU、15 GB 内存，BE 内嵌 JDK 17。`pipeline_dop=0`（自动），`udf_thread_pool_size=1`（默认），关闭 query cache。
- **数据**：1000 万行，一列 STRING 日期，两种分布：
  - `date`：`yyyyMMdd`，约 9000 个不同值，类似分区日期列；
  - `datetime`：`yyyyMMddHHmmss`，每行都不同，类似事件时间列。
- **对比对象**：同一个 `gl_*` 函数（正式版 Java UDF）和一组结果相同的内置函数表达式，例如 `gl_date_first(d,'month')` 对应 `date_format(date_trunc('month', str_to_date(d,'%Y%m%d')),'%Y%m%d')`。
- **校验**：每组先在全表上核对 UDF 和内置写法逐行结果一致，6 组的不一致行数全部为 0。
- **计时**：`SELECT max(<表达式>) FROM t`，只返回一行，时间主要花在表达式计算上。每条查询先预热 1 次，再跑 5 次取中位数。
- **复测命令**：

  ```bash
  python3 e2e/bench.py --dataset date|datetime --rows 10000000 --runs 5 [--set var=value]
  ```

## 结果

基线：只扫描这一列（`SELECT max(d)`）约 0.05 到 0.07 秒。

| 函数 | date（低基数）UDF / 内置 | date，关闭低基数优化 | datetime（高基数）UDF / 内置 |
|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.55s / 0.41s = **3.8x** | 1.68s / 0.41s = 4.1x | 1.49s / 0.54s = **2.8x** |
| `gl_date_first(d,'month')` | 0.93s / 0.40s = 2.3x | 0.98s / 0.48s = 2.0x | 1.04s / 0.53s = 2.0x |
| `gl_date_last(d,'month')` | 1.01s / 0.44s = 2.3x | 0.94s / 0.46s = 2.0x | 1.04s / 0.52s = 2.0x |
| `gl_date_delta(d,'day=+3')` | 2.14s / 0.40s = **5.3x** | 2.24s / 0.38s = 5.8x | 2.52s / 0.86s = **2.9x** |
| `gl_date_diff(d,'20000101','day')` | 0.87s / 0.33s = 2.6x | 0.92s / 0.35s = 2.6x | 0.90s / 0.46s = 2.0x |
| `gl_how_many_day(d,'year')` | 0.54s / 0.34s = 1.6x | 0.55s / 0.35s = 1.6x | 0.65s / 0.57s = 1.2x |

吞吐：Java UDF 约每秒 400 万到 1800 万行，内置函数约每秒 1200 万到 3000 万行（4 vCPU 单机）。原始数据见 `bench_result_date.md`、`bench_result_date_nolowcard.md`、`bench_result_datetime.md`。

## 结论

1. **在这个测试里，Java UDF 比等价的内置函数慢 1.2 到 5.8 倍**，多数在 2 到 3 倍。差距最大的是 `gl_date_delta` 和 `gl_date_format`：UDF 每行要解析日期、解析 offsets，并新建一个 `SimpleDateFormat` 来格式化输出。
2. **低基数字典优化不影响这组结论**：关闭它后的数字基本不变。
3. **高基数数据上差距更小**：内置写法也要用 `str_to_date` 逐行解析字符串，这部分成本两边都有。
4. **放到整条查询里看，影响通常小于上表**：上表的查询几乎只做函数计算。真实查询还有扫描、过滤、Join、聚合，函数计算只占一部分。例如函数只在 SELECT 里对结果集算一次时，几乎感觉不到差别；在 WHERE 或 Join 条件里对全表每行都算时，影响最大。

## 没测的部分

- **并发**：多条查询同时调用 Java UDF（`udf_thread_pool_size` 默认 1）、BE JVM 的内存和 GC 压力；
- **多 BE 节点的大集群**；
- **源列本身是 DATE/DATETIME 类型**：这时内置函数不需要 `str_to_date`，差距会更大；
- **对账版 `gl_*_hive`**：它沿用原代码，每次调用都新建 `Calendar` 和 `SimpleDateFormat`，预计不会比正式版快。

## 优化方向（按投入从小到大）

1. **UDF 内部优化**：按格式串缓存 `DateTimeFormatter`（线程安全、可复用），替代每行新建 `SimpleDateFormat`。预计对 `gl_date_format` 和 `gl_date_delta` 帮助最大，不改变语义，可以用现有测试保证。
2. **改写成内置函数**：对常量参数的调用点，在 SQL 里或在未来 Spark 方言的函数改写层（设计文档里的 L2），改写成内置函数组合。正式版“非法日期返回 NULL”的规则和 `str_to_date` 遇到非法输入返回 NULL 一致，所以改写的语义风险比对账版小。但每个改写仍要用 `samples.csv` 逐条核对。
3. **C++ 原生函数**：设计文档里的 L3。只在某个函数确实是全局热点时才值得做。
