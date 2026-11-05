# 样例结果对照（samples.csv）

`samples.csv` 列出 214 条代表性调用，对照 **原 Hive UDF** 与 **修复后的 StarRocks UDF**（`gl_date_delta`、`gl_date_diff`、`gl_date_format`、`gl_now`、`gl_how_many_day`、`gl_date_first`、`gl_date_last`）的结果。UTF-8 编码，遵循 RFC 4180（含逗号或引号的字段用双引号包裹，引号写成两个引号，行尾 CRLF）。

| 列 | 含义 |
|---|---|
| `function` | 函数名 |
| `sql` | 等价的 StarRocks 调用；`NULL` 表示传入 SQL NULL |
| `hive_result` | 原 Hive UDF 的结果。运行条件：JVM 默认 `Locale.US`、时区 `Asia/Shanghai`。`NULL` 表示返回 NULL，`ERROR` 表示抛异常（查询失败），空单元格表示空字符串 |
| `new_result` | 修复后版本的结果，标记含义同上。`ERROR` 即抛 `IllegalArgumentException`（参数错误） |
| `same` | 两个结果是否完全一致（`true`/`false`） |
| `diff_category` | 不一致时的原因分类；一致时为空 |

## diff_category

| 分类 | 说明 |
|---|---|
| `QUARTER_FIX` | quarter 差值原来按 `月份/4` 计算，现改为 `(月-1)/3` |
| `WEEK_FIX` | week 差值原来是 `YEAR*52 + WEEK_OF_YEAR`，现改为两个日期各自所在周（周日起）之间的周数 |
| `HOUR_24_FIX` | `h=N` 设置小时，原来用 12 小时制字段，现为 24 小时制（0..23） |
| `CALENDAR_DAY_FIX` | 按天差值和 `gl_how_many_day(..., 'quarter')` 原来用 UTC 毫秒整除，非 UTC 时区会差 1；现按日历日计算 |
| `PRE_EPOCH_TRUNC` | 1970 年前的小时/分钟/秒差值，原来的毫秒整除向零取整，现按墙上时间截断后相减 |
| `WALL_CLOCK_TZ` | 夏令时切换附近：原来按真实经过的时间计算，现在统一按墙上时间（不含时区）计算 |
| `STRICT_PARSE` | 日期严格校验：原来宽松解析（`20191332` 会滚动成 `2020-02-01`）或直接抛错，现在返回 NULL；只接受 8 位/14 位数字 |
| `STRICT_SET` | 设置值（`m=13`、`h=24`、`d=0`、`d=32` 等）超出范围现在抛错；范围内但该日期不存在（4 月 31 日、非闰年 2 月 29 日）现在返回 NULL |
| `PARAM_ERROR_POLICY` | 参数错误统一抛 `IllegalArgumentException`：原来返回值或 NULL 的坏参数（未知 `how_many_day` dtype、`day=1=2` 等） |
| `UNKNOWN_INTERVAL` | `gl_date_diff` 的 interval 未知、为空串或大小写不对：原来返回毫秒差，现在抛错 |
| `OFFSET_TRIM` | offsets 里值两侧的空格（如 `' day = +3 '`）：原来抛 `NumberFormatException`，现在会去掉空格 |
| `SR_NO_ZERO_ARG_SIGNATURE` | 零参数调用（如 `gl_date_diff()`）：Hive 返回 NULL；StarRocks 的变参 UDF 至少要一个固定参数，只有 `gl_now()` 单独注册了零参数版本，所以其它函数的零参数调用在分析阶段就报“找不到函数签名”。实际 SQL 不会这样写 |
| `YEAR_RANGE` | 结果年份超出 1..9999：原来输出 `100000101` 之类，现在返回 NULL |

## 说明

- `gl_now` 的结果每天都会变，样例里只放了一行必报错的非法格式（`bad'`）。
- `hive_result` 取决于 JVM 的时区与 Locale；表中固定为 `Asia/Shanghai` + `Locale.US`。`CALENDAR_DAY_FIX` 在 UTC 下不会出现差异，`WALL_CLOCK_TZ` 只在有夏令时的时区出现。周相关结果在其它 Locale 下，原版会不同，新版不受影响。
- 这个文件由 `src/test/java/com/glbk/starrocks/udf/SampleGenerator.java` 生成，不要手工编辑。在模块目录下执行：

```bash
mvn -B test-compile
java -cp target/classes:target/test-classes com.glbk.starrocks.udf.SampleGenerator samples/samples.csv
```

- `SampleGeneratorTest` 会校验行数、每个函数和每个分类都有样例、没有无法解释的差异。
