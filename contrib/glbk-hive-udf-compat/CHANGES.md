# gl_* 日期函数迁移到 StarRocks：结果变更说明

**适用范围**：所有用到 `gl_date_delta`、`gl_date_diff`、`gl_date_format`、`gl_now`、`gl_how_many_day`、`gl_date_first`、`gl_date_last` 的 SQL。

**一句话**：函数名和写法不变，SQL 不用改；但原 Hive 版本有几个计算错误，迁到 StarRocks 后已修正，**踩到这些情况的 SQL 结果会和 Hive 历史数据不同**。

## 哪些结果会变

| 场景 | 例子 | Hive（旧） | StarRocks（新） |
|---|---|---|---|
| 按季度算差值 | `gl_date_diff('2019-04-01','2019-03-31','quarter')` | 0 | **1** |
| 按周算差值，日期跨年 | `gl_date_diff('2019-01-01','2018-12-31','week')` | 52 | **0** |
| 按天算差值，带时间且跨日 | `gl_date_diff('2019-04-17 07:00:00','2019-04-16 09:00:00','day')` | 0 | **1** |
| 设置小时 `h=` | `gl_date_delta('2019-04-17 15:00:00','h=13')` | 2019-04-18 01:00:00 | **2019-04-17 13:00:00** |
| 不存在的日期 | `gl_date_format('20191332','yyyy-MM-dd')` | 2020-02-01 | **NULL** |
| 无法识别的日期（`abc`、空串、`2019/04/17`） | `gl_date_format('abc')` | 整条查询报错 | **NULL** |
| 设置值对该日期不存在 | `gl_date_delta('20190417','d=31')`（4 月没有 31 日） | 20190501 | **NULL** |

## 哪些写法现在会直接报错（SQL 写错了，需要改）

| 写法 | Hive（旧） | StarRocks（新） |
|---|---|---|
| 设置值越界：`m=13`、`h=24`、`d=0`、`d=32` | 悄悄进位，例如 `m=13` 变成下一年 1 月 | 报错 |
| 未知的 interval：`gl_date_diff(a, b, 'xx')` | 返回一个毫秒数 | 报错 |
| 未知的 dtype：`gl_how_many_day(d, 'xx')` | 返回 0 | 报错 |

报错信息会写明是哪个函数、哪个值写错了。

## 不变的部分

- 函数名、参数个数和顺序、返回类型（字符串）、`offsets` 的写法（`+3`、`day=+3`、`y=+1,m=1,d=1,d=-1`）。
- 日期输入仍是 4 种格式：`20190417`、`2019-04-17`、`20190417030405`、`2019-04-17 03:04:05`。
- 一周从周日开始、到周六结束。
- 月末加减规则：1 月 31 日加 1 个月是 2 月 28 日。

## 迁移期如何对账

StarRocks 上另外提供一组 `*_hive` 函数（如 `gl_date_diff_hive`），结果与旧 Hive 完全一致，只用于核对差异：

```sql
SELECT d, gl_date_diff(d, '2019-03-31', 'quarter')      AS new_value,
          gl_date_diff_hive(d, '2019-03-31', 'quarter') AS hive_value
FROM t
WHERE gl_date_diff(d, '2019-03-31', 'quarter') <> gl_date_diff_hive(d, '2019-03-31', 'quarter');
```

`*_hive` 函数在迁移完成后会删除，请不要在正式报表里使用。

## 其它

- 脚本开头的 `create temporary function gl_xxx as '...' using jar '...'` 在 StarRocks 上不需要，迁移时请删掉。
- 逐条的新旧结果对比见 [samples/samples.csv](samples/samples.csv)。有疑问请联系数据平台组。
