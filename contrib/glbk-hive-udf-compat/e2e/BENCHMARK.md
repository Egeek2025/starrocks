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

## 方案二：SQL UDF 展开成内置函数组合（实验，StarRocks 4.1+）

[sql/register_sqludf.sql](../sql/register_sqludf.sql) 把正式版 `gl_*` 注册成 SQL UDF（4.1 起支持）。SQL UDF 在 FE 分析阶段内联展开成内置函数表达式，执行时不走 JNI。具体做法：
- 参数是常量、写法常见时，FE 把路由条件折叠掉，只留下内置函数。常见写法指：4 种标准输出格式，已知的 dtype / interval，单项 offsets（`+3`、`day=+3`、`month=-1` 等）。
- 其它情况（多项 offsets、设置值、自定义格式，以及要报错的坏参数）退回 Java 版，Java 版另外注册为 `gl_*_jni`。参数来自列时也会带上这条兜底分支。
- SQL UDF 只能声明固定个数的参数。参数个数不对的调用，用 1 到 5 个参数的 SQL UDF 返回 NULL；5 个以上参数，以及除 `gl_now` 外的零参数调用，会报“找不到函数”。
- `gl_now` 保留 Java 版：它必须用 BE JVM 的时区，而内置的 `now()` 用的是会话的 `time_zone`。
- 注册方法：`python3 e2e/register_sqludf.py <jar url>`。

**正确性**（4.1.7 集群）：正式版样本 214/214、对账版 214/214、冒烟用例 16/16、已知问题 2/2，动态参数用例逐行一致。执行计划已确认：常量参数时没有 JNI 调用，参数来自列时会带上 `gl_*_jni` 兜底。

**写法上踩过的坑**（都实测过）：
- SQL UDF 只能声明固定个数的参数；如果和同名的 Java 变参版共存，FE 会按注册顺序选第一个匹配到的版本，结果不确定。所以最终没有让两者同名共存。
- `CASE` / `if` 会把每个分支都在所有行上算一遍，“快路径 + 慢路径”的写法反而更慢。
- 3 次 `replace` 去分隔符要 0.48s，`translate` 只要 0.10s；用 `translate` 判断全是数字，比 `regexp` 便宜。
- `str_to_date` 是严格解析，不需要再做往返校验；`cast` 会宽松接受 `2019-4-7`、24 点等写法，不能代替。
- 两个 `str_to_date` 分支比“一次 `str_to_date`、格式串按长度选”慢，后者更快，最终采用后者。

**性能**（同一 4.1.7 集群，1000 万行，5 次中位数；“内置”是手写的最简表达式，只能处理一种输入格式，也不做校验）：

| 函数 | date：Java / SQL UDF / 内置 | datetime：Java / SQL UDF / 内置 |
|---|---|---|
| `gl_date_format(d,'yyyy-MM-dd')` | 1.38 / **0.60** / 0.51 | 1.34 / **0.82** / 0.57 |
| `gl_date_first(d,'month')` | **0.71** / 0.93 / 0.37 | **0.76** / 0.88 / 0.51 |
| `gl_date_last(d,'month')` | 0.72 / **0.58** / 0.44 | 0.76 / **0.74** / 0.57 |
| `gl_date_delta(d,'day=+3')` | 1.79 / **0.71** / 0.39 | 1.59 / **1.37** / 0.88 |
| `gl_date_diff(d,'20000101','day')` | 0.87 / **0.83** / 0.31 | **0.94** / 1.01 / 0.45 |
| `gl_how_many_day(d,'year')` | 0.60 / **0.48** / 0.35 | **0.61** / 0.68 / 0.49 |

（单位：秒，粗体为两种 gl_* 实现中较快者。原始数据：`bench_result_sqludf_date.md`、`bench_result_sqludf_datetime.md`。）

**结论**：
- SQL UDF 版比手写的最简内置表达式慢 **1.2 到 2.7 倍**。多出来的时间花在严格校验和兼容 4 种输入格式上，这是语义要求，省不掉。
- 和 Java 版相比，结果因函数而异：`gl_date_format` 快 1.6 到 2.3 倍，`gl_date_delta` 快 1.2 到 2.5 倍；其它 4 个函数基本持平（差别在 ±25% 以内，单机容器的波动也在这个范围）。
- 原因：经过前面的优化，Java 版的解析是一段很紧凑的手写循环，JNI 开销对这些轻量函数已经不算大；而内置函数组合要对整列做好几遍向量化计算，包括去分隔符、数字检查、解析、取年份校验、格式化。
- SQL UDF 的其它好处：不占 BE 的 JVM 和 UDF 线程池，常见调用不会因为 Java 异常导致查询失败。

**还没解决**：参数全是常量的调用（例如 `WHERE dt = gl_date_delta('20190417','day=-1')`）没有被 FE 折叠成常量，因为 `translate`、`length` 不在 FE 能提前计算的函数列表里，所以不能触发分区裁剪。在 fork 的 FE 里让这两个函数也能提前计算（`ScalarOperatorFunctions`），就能整体折叠，这是一处小的 FE 改动。

**建议**：
- 生产环境如果是 4.1 及以上，可以只对 `gl_date_format` 和 `gl_date_delta` 用 SQL UDF 版，其它函数留在 Java 版。
- 或者全部用 SQL UDF 版，换取不依赖 JVM。
- 不建议为了性能单独升级到 4.1：收益只集中在 2 个函数上。

## 没测的部分

- **并发**：多条查询同时调用 Java UDF（`udf_thread_pool_size` 默认 1）、BE JVM 的内存和 GC 压力；
- **多 BE 节点的大集群**；
- **源列本身是 DATE/DATETIME 类型**：这时内置函数不需要 `str_to_date`，差距会更大；
- **对账版 `gl_*_hive`**：它沿用原代码，每次调用都新建 `Calendar` 和 `SimpleDateFormat`，预计不会比正式版快。

## 优化方向（按投入从小到大）

1. **UDF 内部优化**（已完成，见“优化后（v2）”）：手写常用格式、缓存 offsets 和自定义格式。
2. **改写成内置函数**：对常量参数的调用点，在 SQL 里或在未来 Spark 方言的函数改写层（设计文档里的 L2），改写成内置函数组合。正式版“非法日期返回 NULL”的规则和 `str_to_date` 遇到非法输入返回 NULL 一致，所以改写的语义风险比对账版小。但每个改写仍要用 `samples.csv` 逐条核对。
3. **C++ 原生函数**：设计文档里的 L3。只在某个函数确实是全局热点时才值得做。
