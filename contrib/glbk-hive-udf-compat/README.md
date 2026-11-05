# glbk Hive 日期 UDF 的 StarRocks 对齐版

把 `glbk-hive-udf-1.0.0.jar`（源自 https://github.com/jet2007/HiveDateUDF ，Apache-2.0）中的 7 个 Hive UDF 移植为 StarRocks Java UDF。**函数名、参数个数、参数顺序、返回值与报错行为都和 Hive 上一致**，已有 SQL 不用改。

## 对齐表

| Hive 函数（原类） | StarRocks 注册签名 | StarRocks 类 | 返回 |
|---|---|---|---|
| `gl_date_delta(date, offsets[, format])` (`com.glbk.hive.udf.date.gl_date_delta`) | `gl_date_delta(string, ...)` | `com.glbk.starrocks.udf.GlDateDelta` | string |
| `gl_date_diff(date1, date2[, interval])` | `gl_date_diff(string, ...)` | `GlDateDiff` | string |
| `gl_date_format(date[, format])` | `gl_date_format(string, ...)` | `GlDateFormat` | string |
| `gl_now([format])` | `gl_now()` 和 `gl_now(string, ...)` | `GlNowNoArg` / `GlNow` | string |
| `gl_how_many_day(date, dtype)` | `gl_how_many_day(string, ...)` | `GlHowManyDay` | string |
| `gl_date_first(date, dtype)` | `gl_date_first(string, ...)` | `GlDateFirst` | string |
| `gl_date_last(date, dtype)` | `gl_date_last(string, ...)` | `GlDateLast` | string |

实现方式：
- 原 UDF 都是 `evaluate(Text... args)` 一个变参方法，内部按参数个数分支。StarRocks Java UDF 支持变参（声明 `(string, ...)`，方法 `evaluate(String... args)`），所以每个函数一个类、一次注册，分支逻辑逐行照搬。
- 两处 StarRocks 约束：变参函数至少要有一个固定参数；一个 UDF 类只能有一个 `evaluate`。因此 `gl_now()` 零参数形式单独用 `GlNowNoArg` 注册。
- 日期计算 `com.glbk.utils.date.DateUtils` / `StringUtils` **逐字复制**原源码（sha256 与原文件一致），不做任何修改。
- Hive 里 `result.set(null)` 会抛 NPE 导致查询失败（例如未知的 dtype）。`HiveTextCompat.set` 复现了这一点：同样抛错，而不是悄悄返回 NULL。
- 入参是 DATE/DATETIME 列时，StarRocks 会隐式转成 `yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss` 字符串，与 Hive 的隐式转换一致；数字参数（如 `gl_date_delta(d, 3)`）同样会转成字符串。

## 构建、注册、使用

```bash
cd contrib/glbk-hive-udf-compat
mvn -B package          # 生成 target/glbk-hive-udf-compat-1.0.0.jar，并跑全部测试
```

1. `fe.conf` 中设置 `enable_udf = true` 并重启 FE。
2. 把 jar 放到所有 FE、BE 都能访问的 HTTP 地址。
3. 替换 [sql/register.sql](sql/register.sql) 里的 `${JAR_URL}` 后执行（用 `CREATE GLOBAL FUNCTION`，所有库可用）。
4. 原有 SQL 直接运行，例如 `SELECT gl_date_delta('2018-09-06','y=+1,m=1,d=1,d=-1');` 返回 `2018-12-31`。

Hive 作业脚本里的 `create temporary function gl_xxx as 'com.glbk.hive.udf.date.gl_xxx' using jar ...` 在 StarRocks 上不需要也不能执行：全局函数已注册。Spark 方言落地后（见 `handbook/plans/active/2026-11-05-spark-sql-compat/03-function-adapter.md` 的 L4），计划把这类语句按类名映射为 no-op，脚本可以原样保留。

## 验证

| 测试 | 内容 |
|---|---|
| `DifferentialTest` | 用测试桩（`Text`、`UDF`、`Description`、pdk 注解）把**原始 Hive UDF 源码原样编译**，与 StarRocks 版在同一组输入上逐一比对：值相同、同为 NULL、或同样抛错。输入覆盖 4 种日期格式、闰年/月末/年末、DST 切换时刻、1970 前、非法日期、空串、NULL，27 种 offsets（含非法单位、非法数字、空格、尾逗号），10 种 format（含非法格式），各种 dtype/interval（含未知值、大写、NULL），以及 0 到 4 个参数的全部组合；每组在 Asia/Shanghai、UTC、America/New_York 三个 JVM 时区下各跑一遍，共约 85 万次比对。 |
| `ReadmeExamplesTest` | 原 README 中的全部示例。 |
| 变异测试 | 把“未知 dtype 抛错”改成“返回 NULL”后，`DifferentialTest` 立即失败，确认比对是有效的。 |

`mvn -B test`：21 个测试全部通过（JDK 21，Maven 3）。

**尚未验证**：在真实 StarRocks 集群上注册并执行（本环境没有集群）。特别是零参数 Java UDF `gl_now()` 在 BE 上的执行 [未验证]。

## 原 UDF 的行为特点（已原样保留）

以下行为在 Hive 上就是如此，移植版**刻意保持一致**。如果要修正，需要单独决策（会造成两边结果不同）。

| # | 行为 | 例子（实测） |
|---|---|---|
| 1 | **周相关结果依赖 JVM 默认 Locale**（周首日） | `gl_date_first('20190417','week')`：en_US 得 `20190414`；zh_CN/de_DE 得 `20190421`，比 `gl_date_last` 的 `20190420` 还晚。`gl_date_diff(...,'week')` 同样受影响 |
| 2 | **JDK 版本也会改变第 1 条**：JDK 9+ 默认使用 CLDR 区域数据，zh_CN 周首日为周一；JDK 8 及以前为周日 | 原 jar 时代多跑在 JDK 7/8 上，StarRocks BE 用 JDK 17 |
| 3 | 依赖 JVM 默认时区（不是 StarRocks 会话的 `time_zone`） | 解析、`gl_now`、`Calendar` 计算都用 BE JVM 的时区 |
| 4 | 按天/小时的差值用 UTC 毫秒整除，在非 UTC 时区跨日会差 1 | Asia/Shanghai：`gl_date_diff('2019-04-17 07:00:00','2019-04-16 09:00:00','day')` = `0` |
| 5 | quarter 差值实际用 `月份/4` 计算（原代码 bug） | `gl_date_diff('2019-04-01','2019-03-31','quarter')` = `0`；`('2019-05-01','2019-03-31','quarter')` = `1` |
| 6 | `hour`/`h` 用的是 12 小时制字段 `Calendar.HOUR` | `gl_date_delta('2019-04-17 15:00:00','h=13')` = `2019-04-18 01:00:00` |
| 7 | 宽松解析：非法日期会滚动，不报错 | `gl_date_format('20191332','yyyy-MM-dd')` = `2020-02-01` |
| 8 | 无法解析的日期（如 `2019-1-7`、`abc`、空串）、未知 dtype、非法 offsets → 抛错，查询失败；解析失败还会向 stderr 打印堆栈（BE 的 `be.out`） | |
| 9 | 结果都是 **STRING**，不是 DATE/DATETIME | |
| 10 | 原 README 的两处错误：`gl_date_diff('20170101','20180201','month')` 实际是 `-13`（README 写 `-12`）；`@Description` 里写的 `'31'` 实际是 `-31`（date1 − date2） | |
| 11 | `gl_now()` 在 Hive 中可能被编译期常量折叠（UDF 默认 deterministic）；StarRocks Java UDF 按行求值，跨午夜的长查询可能不同行得到不同日期 | |

## 性能提示

Java UDF 走 JNI 逐批调用，每次调用都会新建 `SimpleDateFormat`/`Calendar`；BE 的 `udf_thread_pool_size` 默认 1。大表上如果成为瓶颈，可以对**常量参数、格式规整**的调用改写成 StarRocks 内置函数（例如 `gl_date_first(d,'month')` → `date_format(date_trunc('month', d), '%Y%m%d')`），但非法输入、Locale/时区相关的行为会和 Hive 不同，需要逐个评估。
