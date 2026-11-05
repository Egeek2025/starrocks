# glbk Hive 日期 UDF 的 StarRocks 对齐版

把 `glbk-hive-udf-1.0.0.jar`（源自 https://github.com/jet2007/HiveDateUDF ，Apache-2.0）中的 7 个 Hive UDF 移植为 StarRocks Java UDF，**函数名和入参与 Hive 一致**，已有 SQL 不用改写调用方式。提供两套函数：

| 函数集 | 用途 | 行为 |
|---|---|---|
| **正式版 `gl_*`** | 生产使用 | 修复了原 jar 的 bug（见“与 Hive 的差异”），非法数据返回 NULL，参数写错报错 |
| **对账版 `gl_*_hive`** | 仅迁移期核对 Hive 历史结果，迁移完成后删除 | 与原 Hive jar 逐条一致（包括 bug），只把 Locale 固定为 US |

面向业务方的变更说明：[CHANGES.md](CHANGES.md)。实现路径、经验和下一步（C++ 原生函数）：[IMPLEMENTATION-LOG.md](IMPLEMENTATION-LOG.md)。

## 函数一览

| Hive 函数 | 正式版（类） | 对账版（类） |
|---|---|---|
| `gl_date_delta(date, offsets[, format])` | `gl_date_delta(string, ...)`（`com.glbk.starrocks.udf.GlDateDelta`） | `gl_date_delta_hive(string, ...)`（`com.glbk.starrocks.udf.hive.GlDateDeltaHive`） |
| `gl_date_diff(date1, date2[, interval])` | `gl_date_diff`（`GlDateDiff`） | `gl_date_diff_hive`（`GlDateDiffHive`） |
| `gl_date_format(date[, format])` | `gl_date_format`（`GlDateFormat`） | `gl_date_format_hive`（`GlDateFormatHive`） |
| `gl_now([format])` | `gl_now()`（`GlNowNoArg`）和 `gl_now(string, ...)`（`GlNow`） | `gl_now_hive()` / `gl_now_hive(string, ...)` |
| `gl_how_many_day(date, dtype)` | `gl_how_many_day`（`GlHowManyDay`） | `gl_how_many_day_hive`（`GlHowManyDayHive`） |
| `gl_date_first(date, dtype)` | `gl_date_first`（`GlDateFirst`） | `gl_date_first_hive`（`GlDateFirstHive`） |
| `gl_date_last(date, dtype)` | `gl_date_last`（`GlDateLast`） | `gl_date_last_hive`（`GlDateLastHive`） |

全部返回 STRING。实现要点：
- 原 UDF 都是一个 `evaluate(Text... args)` 变参方法，按参数个数分支。StarRocks Java UDF 支持变参：声明 `(string, ...)`，方法 `evaluate(String... args)`，所以每个函数一个类、一次注册。
- StarRocks 的两个限制：变参函数至少要有一个固定参数；一个 UDF 类只能有一个 `evaluate`。所以 `gl_now()` 的零参数形式单独注册。其它函数的零参数调用在 Hive 返回 NULL，在 StarRocks 报“找不到函数签名”（实际 SQL 不会这样写）。
- 入参是 DATE/DATETIME 列时，StarRocks 会隐式转成 `yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss` 字符串，与 Hive 一致；数字参数（如 `gl_date_delta(d, 3)`）同样转成字符串。
- **正式版**（`com.glbk.starrocks.udf` + `core/GlDates`）：用 `java.time` 重写，按墙上时间计算，结果与 JVM 时区和 Locale 无关（`gl_now` 除外），线程安全，不往 stdout/stderr 打印。
- **对账版**（`com.glbk.starrocks.udf.hive`）：调用原样复制的 `com.glbk.utils.date.DateUtils`，唯一改动是所有 `Calendar` 和 `SimpleDateFormat` 固定为 `Locale.US`（文件头注释列明了改动）。原因：JDK 9+ 默认用 CLDR 区域数据，zh_CN 的周首日变成周一，而原 jar 时代的 JDK 8 是周日；不固定的话 BE 的 `LANG` 和 JDK 版本会改变周相关结果。Hive 里 `result.set(null)` 会抛 NPE 让查询失败，`HiveTextCompat` 复现了这一点。

## 正式版的语义

**输入日期**：去掉空格、`-`、`:` 后必须是 8 位（`yyyyMMdd`）或 14 位（`yyyyMMddHHmmss`）数字，并且是真实存在的日期时间（严格校验）。只接受这 4 种写法，`2019-1-7` 这类不补零的写法不接受。

**非法输入**：

| 情况 | 结果 |
|---|---|
| 日期为 NULL | NULL |
| 日期非法（`20191332`、`abc`、空串等），或设置值对该日期不存在（4 月 `d=31`、非闰年 `m=2,d=29`） | NULL |
| 参数写错：未知的 dtype / interval / offsets 单位，offsets 格式错，设置值越界（`m=13`、`h=24`、`d=0`），dtype/interval 为 NULL 或空，非法 format | 报错（`IllegalArgumentException`，消息里写明函数名和错误的值），查询失败 |

dtype / interval / 单位名区分大小写，与原版相同：`year|y`、`quarter|q`、`month|m`、`week|w`、`day|d`、`hour|h`、`minute|n`、`second|s`。

**各函数**：
- `gl_date_delta(date, offsets[, format])`：offsets 写法与原版相同（`+3`、`day=+3`、`y=+1,m=1,d=1,d=-1`）。带 `+`/`-` 是加减（月份加减在月末收敛，1 月 31 日加 1 个月是 2 月 28 日）；不带符号是设置，`h` 按 24 小时制。年、月、日的设置像原版一样合并后再判断，所以常用写法 `m=3,d=1` 在 1 月 31 日也能得到 3 月 1 日。format 为空时输出与输入同样的格式。只传 1 个参数时原样返回。
- `gl_date_diff(date1, date2[, interval])` = date1 − date2，默认 `day`。quarter 按自然季度，week 按周日开头的周，day 按日历日期，hour/minute/second 按截断到该单位的墙上时间相减。
- `gl_how_many_day`：year→年内第几天，quarter→季内第几天，month→月内第几天，week→周日为 1、周六为 7。
- `gl_date_first` / `gl_date_last`：year/quarter/month/week 的第一天、最后一天（周日到周六），输出 `yyyyMMdd`。
- `gl_date_format(date[, format])`：默认 `yyyyMMdd`。`gl_now([format])`：当前时间（BE JVM 时区），默认 `yyyyMMdd`。格式串按 `SimpleDateFormat` 规则，Locale.US。

## 与 Hive 的差异（正式版）

| 类别 | 例子 | Hive 原版 | 正式版 |
|---|---|---|---|
| 季度差值 | `gl_date_diff('2019-04-01','2019-03-31','quarter')` | 0（按 `月/4` 计算） | 1 |
| 周差值跨年 | `gl_date_diff('2019-01-01','2018-12-31','week')`（同一周） | 52（`年×52` 公式） | 0 |
| 小时设置 | `gl_date_delta('2019-04-17 15:00:00','h=13')` | `2019-04-18 01:00:00`（12 小时制） | `2019-04-17 13:00:00` |
| 按天差值 | `gl_date_diff('2019-04-17 07:00:00','2019-04-16 09:00:00','day')`（东八区） | 0（按 UTC 毫秒整除） | 1 |
| 季内第几天 | `gl_how_many_day(...,'quarter')` | 只在有夏令时的时区可能差 1（东八区无影响） | 按日历日 |
| 非法日期 | `gl_date_format('20191332','yyyy-MM-dd')` | `2020-02-01`（宽松滚动） | NULL |
| 无法解析的日期 | `gl_date_format('abc')`、空串 | 报错 | NULL |
| 越界设置值 | `gl_date_delta('20190417','m=13')` | `20200117`（滚动） | 报错 |
| 未知 interval | `gl_date_diff('20190417','20190416','xx')` | `86400000`（毫秒差） | 报错 |
| 其它 | 1970 年前的时分秒差、夏令时附近、值两侧空格、年份超出 1..9999 | 见 [samples/README.md](samples/README.md) | |

完整的逐条对比见 [samples/samples.csv](samples/samples.csv)（214 条，含每条差异的类别）。

## 构建、注册、部署要求

```bash
cd contrib/glbk-hive-udf-compat
mvn -B package          # 生成 target/glbk-hive-udf-compat-1.0.0.jar，并跑全部测试
```

1. `fe.conf`：`enable_udf = true`，重启 FE。
2. `be.conf` 的 `JAVA_OPTS` 加 `-Duser.timezone=Asia/Shanghai`（`gl_now` 和对账版都用 BE JVM 的时区，不读会话的 `time_zone`）。
3. jar 的 class 版本不能高于 17（`pom.xml` 已设 `release 8`）；用 JDK 21 默认编译的 jar 会被 FE 拒绝。
4. 把 jar 放到所有 FE、BE 都能访问的 HTTP 地址，替换 [sql/register.sql](sql/register.sql) 里的 `${JAR_URL}` 后执行。它会注册两套共 16 个全局函数。
5. Hive 脚本里的 `create temporary function ... using jar ...` 在 StarRocks 上不需要，也执行不了，迁移时要从脚本里去掉（本次不做自动处理）。

## 原生内置函数（BE，fn_id 400001-400006）

`gl_date_delta`、`gl_date_diff`、`gl_date_format`、`gl_how_many_day`、`gl_date_first`、`gl_date_last` 另有 C++ 原生内置实现（`be/src/exprs/glbk_date_core.{h,cpp}` 是算法，`be/src/exprs/glbk_functions.{h,cpp}` 是 BE 绑定，函数表在 `gensrc/script/functions.py`），语义与上面的 Java 正式版一致（以 [samples/samples.csv](samples/samples.csv) 为准）。`gl_now` 仍然是 Java UDF（要用 BE JVM 的时区）。

- **同名 UDF 必须删除**：FE 解析函数名时先找内置函数、后找 UDF（`FunctionAnalyzer.getAnalyzedFunctionImpl`），所以内置函数上线后，不带库名的 `gl_date_delta(...)` 等调用永远走内置版，Java 版的 UDF 不会再被调用。升级后请执行 `DROP FUNCTION gl_date_delta(VARCHAR, ...)` 等语句清理同名 UDF（`gl_now` 保留），避免误以为还在用 Java 版。FE 不需要改动。
- 与 Java UDF 一样是变参 `(VARCHAR, ...)`：参数个数不支持时返回 NULL，零参数调用在分析阶段报“找不到函数签名”。
- 参数为 SQL NULL 时原样传给函数（内置标量函数没有统一的 NULL 传播），由函数决定返回 NULL 还是报参数错误（如 `gl_date_format('20190417', NULL)` 报错）。参数错误以 `InvalidArgument` 状态让查询失败，错误信息与 Java 版同样指明函数名。
- 常量的 format / offsets 在 prepare 阶段预编译；常量参数本身有错时不在 prepare 阶段失败，而是让逐行路径在第一行真正校验参数时报同样的错（日期为 NULL 的行仍返回 NULL，与 Java 版一致）。

## 验证

| 测试 | 内容 | 结果 |
|---|---|---|
| 单元测试 `mvn -B test` | 112 个：规则测试、环境无关性（时区、Locale、并发、无输出）、UDF 类约束、对账版差分、正式版“预期差异”差分、样本生成 | 全部通过（JDK 21） |
| 对账版差分 `hive/DifferentialTest` | 测试桩把**原始 Hive UDF 源码原样编译**作为参照（Locale.US），对账版在 en_US、zh_CN、de_DE、ar_SA 四种默认 Locale × 3 个时区下必须逐条一致 | 0 差异 |
| 正式版差分 `ExpectedDifferenceTest` | 约 270 万次比对：每处与原版不同的结果都必须能归入上表某个已同意的修复类别，归不进去就失败；某个类别一次都没出现也失败 | 0 处无法解释的差异 |
| 变异测试 | 撤掉一处 Locale.US、或把季度 bug 改回去，相关测试立即失败 | 有效 |
| 端到端 [e2e/](e2e/README.md) | 真实 StarRocks 4.0.17 单机集群（本仓库 docker 环境，BE JDK 17、Asia/Shanghai）：正式版 `samples.csv` 214/214，对账版 `samples_hive.csv` 214/214，冒烟用例 16/16，零参数 `gl_now()` 可执行 | 全部通过 |

## 实验：用内置函数组合实现（StarRocks 4.1+）

[sql/register_sqludf.sql](sql/register_sqludf.sql) 把正式版 `gl_*` 注册成 SQL UDF：常量参数、常见写法的调用会展开成纯内置函数，不走 JNI；其它情况退回 Java 版 `gl_*_jni`。结果和 Java 版完全一致（端到端样本 214/214）。性能上 `gl_date_format`、`gl_date_delta` 比 Java 版快 1.2 到 2.5 倍，其它函数基本持平。详见 [e2e/BENCHMARK.md](e2e/BENCHMARK.md) 的“方案二”。

## 已知问题

- **对账版在 WHERE 里遇到有 NULL 行的可空 STRING 列会报错**：StarRocks 会在列字典上对谓词求值，字典里用空串占位 NULL，而原版遇到空串会抛错（在 Hive 上 NULL 会被跳过，没有这个问题）。规避：会话里 `SET enable_column_expr_predicate = false`，或只在 SELECT 列表里用。正式版遇到空串返回 NULL，不受影响。详见 [e2e/README.md](e2e/README.md) 第三节。
- `gl_now()` 在 Hive 中可能被编译期常量折叠；StarRocks Java UDF 按行求值，跨午夜的长查询里不同行可能得到不同日期。

## 性能

实测（4 vCPU 单机，1000 万行）：经过 UDF 内部优化后，Java UDF 比等价的内置函数表达式慢 **1.3 到 3.5 倍**（优化前 1.2 到 5.8 倍），`gl_date_delta` 和 `gl_date_format` 差距最大，剩余差距主要是 JNI 调用本身的开销。方法、数据和优化方向见 [e2e/BENCHMARK.md](e2e/BENCHMARK.md)。

所有 `gl_*` 都是 BE 通过 JNI 调用的 Java UDF，FE 不会对常量参数做折叠。BE 的 `udf_thread_pool_size` 默认 1。大表上如果成为瓶颈，可以对常量参数的调用改写成 StarRocks 内置函数组合（例如 `gl_date_first(d,'month')` 改成 `date_format(date_trunc('month', str_to_date(d,'%Y%m%d')),'%Y%m%d')`），但对非法输入的处理要逐个用 `samples.csv` 核对。
