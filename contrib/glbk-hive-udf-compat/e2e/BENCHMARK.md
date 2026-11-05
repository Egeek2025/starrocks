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

## 优化后（v2）

做了第 1 项优化（只改正式版 `gl_*`，语义不变，117 个单元测试和全部端到端样本照常通过）：
- 4 种常用日期格式手写拼接；
- `gl_date_delta` 的 offsets 按字符串缓存解析结果（解析出错的不缓存）；
- 自定义格式按线程缓存编译好的 `SimpleDateFormat`。

JVM 内单次调用耗时（单线程微基准）：`gl_date_delta` 219ns → 98ns，`gl_date_format` 常用格式 114ns → 77ns、自定义格式 1004ns → 448ns，`gl_date_first` 87ns → 54ns，`gl_date_last` 91ns → 65ns，`gl_date_diff` 73ns → 60ns，`gl_how_many_day` 不变（约 33ns）。

集群实测（同一环境、同样方法，1000 万行，5 次中位数）：

| 函数 | date：优化前 UDF → 优化后 UDF（内置） | 倍数变化 | datetime：优化前 UDF → 优化后 UDF（内置） | 倍数变化 |
|---|---|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.55s → 1.31s（0.40s） | 3.8x → 3.3x | 1.49s → 1.61s（0.52s） | 2.8x → 3.1x |
| `gl_date_first(d,'month')` | 0.93s → 0.74s（0.40s） | 2.3x → 1.8x | 1.04s → 0.77s（0.57s） | 2.0x → 1.4x |
| `gl_date_last(d,'month')` | 1.01s → 0.72s（0.40s） | 2.3x → 1.8x | 1.04s → 0.79s（0.57s） | 2.0x → 1.4x |
| `gl_date_delta(d,'day=+3')` | 2.14s → 1.34s（0.39s） | 5.3x → 3.5x | 2.52s → 1.77s（0.82s） | 2.9x → 2.1x |
| `gl_date_diff(d,'20000101','day')` | 0.87s → 0.78s（0.32s） | 2.6x → 2.4x | 0.90s → 0.89s（0.47s） | 2.0x → 1.9x |
| `gl_how_many_day(d,'year')` | 0.54s → 0.52s（0.35s） | 1.6x → 1.5x | 0.65s → 0.62s（0.49s） | 1.2x → 1.3x |

原始数据：`bench_result_date_v2.md`、`bench_result_datetime_v2.md`。

**结论**：
- 优化后 Java UDF 比内置函数慢 **1.3 到 3.5 倍**（优化前 1.2 到 5.8 倍）。
- 最慢的 `gl_date_delta` 从 2.14s 降到 1.34s（快约 37%），`gl_date_first`/`gl_date_last` 快约 20% 到 28%。
- `gl_date_format` 在 datetime 数据上没有变快（1.49s → 1.61s，在单机容器的波动范围内）。它在 JVM 里的计算本来就只有约 80ns，每行的耗时主要花在 JNI 调用和字符串在 C++/Java 之间来回拷贝上（字符串越长越明显），这部分 UDF 内部优化不了。
- 剩下的差距主要是 JNI 本身的固定开销。要再明显提速，只能用第 2 项（改写成内置函数）或第 3 项（C++ 原生函数）。

## 没测的部分

- **并发**：多条查询同时调用 Java UDF（`udf_thread_pool_size` 默认 1）、BE JVM 的内存和 GC 压力；
- **多 BE 节点的大集群**；
- **源列本身是 DATE/DATETIME 类型**：这时内置函数不需要 `str_to_date`，差距会更大；
- **对账版 `gl_*_hive`**：它沿用原代码，每次调用都新建 `Calendar` 和 `SimpleDateFormat`，预计不会比正式版快。

## 优化方向（按投入从小到大）

1. **UDF 内部优化**（已完成，见“优化后（v2）”）：手写常用格式、缓存 offsets 和自定义格式。
2. **改写成内置函数**：对常量参数的调用点，在 SQL 里或在未来 Spark 方言的函数改写层（设计文档里的 L2），改写成内置函数组合。正式版“非法日期返回 NULL”的规则和 `str_to_date` 遇到非法输入返回 NULL 一致，所以改写的语义风险比对账版小。但每个改写仍要用 `samples.csv` 逐条核对。
3. **C++ 原生函数**：设计文档里的 L3。只在某个函数确实是全局热点时才值得做。
