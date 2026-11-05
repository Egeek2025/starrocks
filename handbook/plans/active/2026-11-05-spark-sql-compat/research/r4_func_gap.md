# R4 Spark SQL 与 StarRocks 内置函数差距分析

产出文件：`/tmp/claude-0/-home-user/26eaf88c-2924-5019-8bd6-ec9f29be9b81/scratchpad/func_mapping.csv`（479 行，逐函数分类）；`/tmp/claude-0/-home-user/26eaf88c-2924-5019-8bd6-ec9f29be9b81/scratchpad/spark_functions.txt`（Spark 函数清单）；本报告。

## 1. 方法与数据来源

* Spark 3.5 清单：直接解析 `apache/spark` `branch-3.5` 的 `FunctionRegistry.scala`（`expression[...]/expressionBuilder/castAlias` 共 430 个注册名，含算子 `+ - * / % = == <=> & | ^ ~ ! div`、cast 别名、窗口、生成器）。另补充：标准语法的 `percentile_cont/disc ... WITHIN GROUP`，以及 Spark 4.0/4.1 新增函数（来自记忆，未联网核对，标注为 "4.0/4.1"，准确性较低，不计入核心统计）。
* StarRocks 清单：解析 `gensrc/script/functions.py`（460 个不同的标量/聚合/窗口注册名，含 `'` 与 `"` 两种引号）+ `FunctionSet.java` 常量（498 个，补充 agg/window/table 函数）+ `docs/en/sql-reference/sql-functions/**` 文件名。中间文件：`sr_scalar.txt`、`sr_fs.txt`、`sr_all.json`。
* 核心结论所依据的核实：阅读了约 40 个 SR 函数文档页，并核对了 BE/FE 源码（见第 6 节"已核实项"）。其余 B 类判定属于基于 Spark 语义 + SR 文档/签名的推断，CSV 的 difference 列里带 "test"/"verify" 的条目需要上线前用对拍用例验证。
* 重要发现：仓库已有 **Trino 方言转换器** `fe/fe-core/.../connector/parser/trino/`（`TrinoParser`、`Trino2SRFunctionCallTransformer` 约 440 行注册表，`FunctionCallTransformer`/`ComplexFunctionCallTransformer` 支持参数重排与占位符），由 `sql_dialect=trino` 触发（`SqlParser.java`）。Spark 适配层最自然的落点是仿此结构新增 `sql_dialect=spark`（词法/语法差异：`LATERAL VIEW`、`DISTRIBUTE/CLUSTER/SORT BY`、反引号、`||`），函数层再加一张 Spark→SR 映射表。

## 2. 分类定义

* A：同名且语义兼容（含仅有次要细节差异的）
* B：同名但语义/参数不同（必须适配，否则静默出错——最危险）
* C：不同名、语义相同（别名/简单改写）
* D：SR 缺失，需新增（难度 S=纯 FE 表达式改写，无需 BE；M=少量 BE 函数或复杂改写；L=大工程/UDAF/类型系统）
* E：不适用（Spark 运行时概念），给出模拟方案

## 3. 统计（Spark 3.5 核心 430 个注册名）

| 类 | 数量 | 占比 |
|---|---|---|
| A 兼容 | 142 | 33% |
| B 同名异义 | 89 | 21% |
| C 别名/改写 | 66 | 15% |
| D 缺失 | 128 | 30%（S=59，M=38，L=31）|
| E 不适用 | 5 | 1% |

说明：
* C 里有 12 个是类型名 cast 别名（`int(x)`、`string(x)` 等），由解析器处理；D-S 的 59 个多数是一行表达式改写（如 `hypot`、`log1p`、`regr_*`、`array_union`），不需要 BE 开发。
* 实际"需要 BE/UDF 开发"的约为 D-M + D-L 中的约 55 个，其中大部分是低频函数（xpath、regr、sketch、interval）。
* 全量 479 行（含 4.0/4.1 与标准语法补充 49 行）见 CSV。
* 按类别：agg 中 A15/B14/C6/D23；string A21/B19/C12/D26；datetime A17/B17/C7/D21；collection A20/B11/C7/D13；math A32/B10/C8/D19；window 9 个中 8 个 A（缺 `nth_value`）。

## 4. 最危险的同名异义陷阱（B 类，静默错误）

按"出错概率 x 影响面"排序，均已在 SR 文档/源码中核实：

1. **`stddev`/`std`/`variance` 语义相反**：SR 的 `stddev`、`std`、`variance` 是**总体**（等同 `stddev_pop`/`var_pop`，见 aggregate-functions/stddev.md、variance.md），Spark 同名函数是**样本**。必须改写为 `stddev_samp`/`var_samp`。
2. **`||`**：SR 默认 sql_mode 下是逻辑 OR（`StarRocksLex.g4` 中 `LOGICAL_OR: '||'`，仅当 `PIPES_AS_CONCAT` 位开启才变 CONCAT）；Spark 是字符串拼接。适配层必须为 Spark 会话设置该 sql_mode。
3. **`date_add/date_sub(date,int)`**：Spark 返回 DATE；SR 解析为 `days_add`，返回 DATETIME（functions.py 50142）。输出格式变成 `2024-01-02 00:00:00`，影响下游 join/分区字段写入。需外包 `cast(... as date)`。
4. **`date_format`/`from_unixtime`/`unix_timestamp`/`to_date`/`to_timestamp` 格式串**：Spark 用 Java 模式（`yyyy-MM-dd HH:mm:ss`、`EEE`、`a`），SR 用 MySQL 说明符（`%Y-%m-%d %H:%i:%s`）。BE 仅对 `yyyyMMdd`、`yyyy-MM-dd`、`yyyy-MM-dd HH:mm:ss` 等 3~5 个常见模式做了特判（`time_functions.cpp` 1748-1760, 2635-2644），其余模式会返回 NULL 或错误。需要 FE 常量折叠式的模式翻译；非常量格式列需拒绝或走 UDF。`unix_timestamp` 在 SR 对 1970 前/2038 后返回 0（文档），需用 64 位重载。
5. **`greatest`/`least`**：Spark 跳过 NULL；SR 任一参数 NULL 返回 NULL（文档）。
6. **`collect_list`/`array_agg`**：SR 没有 `collect_list`；`array_agg` 保留 NULL（文档明确），Spark 丢弃 NULL。`collect_set` → `array_unique_agg`/`array_agg(DISTINCT)` 加 NULL 过滤。
7. **数组下标与 `element_at`**：Spark `arr[i]` **0 基**，`element_at` 1 基且支持负数；SR 两者均 1 基且 `element_at` 仅正数（文档）。FE 需对 Spark 方言的下标运算做 `+1` 改写。
8. **`size(NULL)`**：Spark 默认返回 -1（legacy sizeOfNull），SR 返回 NULL；Spark `size` 同时支持 array/map，SR 分 `array_length`/`map_size`/`cardinality`。
9. **`split`**：Spark 第二参是**正则**且有 limit；SR `split` 是字面量分隔符、无 limit（文档，且空分隔符按字符拆）。含正则元字符时改走 `regexp_split`（文档存在，未核实签名）。
10. **`regexp_extract`/`regexp_extract_all`/`regexp_replace`**：Spark idx 默认 1，SR 第 3 参必填；正则方言 Java vs RE2（无 lookaround/反向引用）；替换串 Spark 用 `$1`，SR（RE2）用 `\1`。
11. **`substring/substr`**：Spark `pos=0` 当作 1；SR `pos=0` 返回空串（文档）。
12. **`length`**：SR 的 `length` 是字节数（MySQL 风格），Spark 是字符数 → 映射到 `char_length`；`octet_length` → SR `length`。
13. **`hash`/`xxhash64`**：Spark 为 Murmur3_x86_32 / XXH64，seed=42，按列类型逐列链式哈希；SR `murmur_hash3_32` seed=104729、仅接受字符串，`xx_hash64` seed=0（`hash_util.hpp`）。数值完全不同，用于分桶/去重/采样时会改变结果，需要新增 BE 函数 `spark_hash` 复现 Spark 算法（M-L）。
14. **`ltrim/rtrim(trimStr, str)`** 参数顺序与 `TRIM(BOTH c FROM s)` 语法；**`trunc(date,fmt)`** 与 `date_trunc(fmt,ts)` 参数顺序相反且 fmt 取值集合不同；**`transform`/`filter`** 在 SR 是 lambda 在前（`array_map(x->f, arr)`）。
15. **`array_sort`/`sort_array`**：SR 升序 NULL 在前；Spark `array_sort` NULL 在后；`sort_array(arr,false)` 降序 NULL 在后。
16. **`percentile_approx`**：第三参语义不同（Spark accuracy 默认 10000；SR compression 范围 [2048,10000]），结果近似值本身也不同。
17. **`round`**：二者均为 half-up（BE 使用 `std::round`，已核实），但 SR `round(DOUBLE)` 单参返回 BIGINT、Spark 返回 DOUBLE；DECIMAL 精度规则不同。Spark `bround`（half-even）缺失。
18. **decimal 运算**、**cast 宽松度**（`'abc'`→NULL、`'1.5'`→1、溢出回绕、string↔date 格式）：属于类型系统差异，是最大的长尾风险，建议单独立项做 cast 兼容适配（L）。
19. **`rand(seed)`**：SR 忽略 seed（文档）。
20. **`next_day`**：SR 星期名大小写敏感且非法值报错，Spark 不敏感。
21. **`count(DISTINCT a,b)`** 双方支持；`count(a,b)` 非 distinct 多参 SR 不支持。

## 5. 适配层策略建议

1. **分层**：(a) 方言解析层（`sql_dialect=spark`，处理 `LATERAL VIEW`/`DISTRIBUTE BY`/`||`/`div`/下标/`TRIM(BOTH ...)`）；(b) FE 函数名/参数改写表（别名、参数重排、默认参数补齐、常量格式串翻译、返回类型外包 cast）——绝大部分 B/C/D-S 在此完成；(c) BE 新增少量函数（`bround/rint`、`isnan`、`sha1`、`spark_hash/xxhash64`、`months_between`、`format_number`、`bit_count`、位聚合、`nth_value`、`mask`、`levenshtein`）；(d) Java UDF 处理长尾（xpath、to_number、format_string、histogram_numeric）；(e) 兜底：无法适配的函数在 analyzer 阶段显式报 "unsupported in Spark dialect" 而不是静默错结果。
2. **优先级**：先 (1) 修正 17 个"静默错误"的 B 项（stddev/variance、||、date_add、格式串、greatest、collect_list、数组下标、size、split、regexp_*、substring、length、hash），再补 top-80 里的缺失项，最后长尾。
3. **验证**：对每个 A/B 函数建立 Spark 3.5 对拍用例（同一输入在 Spark 与 SR 上比较），特别是 NULL/空串/负数/边界日期。
4. **生成器（explode 系）**：Spark 在 SELECT 列表里直接用 `explode`；SR 需要 `FROM t, LATERAL unnest(...)`（SR g4 支持 `LATERAL`，`unnest` 支持多数组和 `LEFT JOIN ... ON TRUE`（v3.2.7+）），在 FE 做重写；map 的 explode 需 `map_keys/map_values` 配 `unnest`。
5. **E 类模拟**：`spark_partition_id()`→常量 0（或 hash 桶号）；`input_file_name()`→若外表有隐藏路径列则映射，否则返回空串（未核实 SR 是否有此列，请 FE 同事确认）；`input_file_block_*`→-1；`monotonically_increasing_id()`→`row_number() over ()` 转 BIGINT（全局排序代价高）或新增 BE 序列函数（BE id<<33 + 递增，更接近 Spark 语义）。

## 6. 已核实项（读源码/文档）

* `stddev`/`variance` 总体语义（docs/aggregate-functions/stddev.md、variance.md）
* `greatest` NULL 规则（docs/math-functions/greatest.md），`concat` 任一 NULL 返回 NULL（与 Spark 一致）
* `array_agg` 保留 NULL（docs），`array_length` 对 NULL 返回 NULL，`element_at` 仅正数位置
* `split` 字面量分隔符、无 limit；`substring` pos=0 返回空串；`regexp_extract` 第 3 参必填；`instr/locate` 1 基；`datediff(end,start)` 与 Spark 同序
* `date_add(DATETIME, INT)`→`days_add`→DATETIME（functions.py）；`date_format` 仅特判几种 Java 模式（`time_functions.cpp`）
* `murmur_hash3_32` seed=104729，`xx_hash64` seed=0（`hash_util.hpp`）
* `||` 受 `PIPES_AS_CONCAT` 控制（`StarRocksLex.g4:551`）；`<=>`、`DIV`、`NULLS FIRST/LAST`、`QUALIFY`、`PIVOT`、`LATERAL`、`first_value ... IGNORE NULLS` 语法存在
* SR 窗口函数：avg/array_agg/count/cume_dist/dense_rank/first_value/last_value/lag/lead/max/min/ntile/percent_rank/rank/row_number/sum/方差系/covar/corr；无 `nth_value`
* `percentile_cont`/`percentile_disc`/`count_if`/`string_agg`/`group_concat`/`max_by`/`min_by`/`any_value`/`bool_or` 存在；无 `bool_and`、`collect_list`、`collect_set`、`first`、`last`、`mode`、`median`、`skewness`、`kurtosis`、`regr_*`、`bit_and/or/xor` 聚合
* SR 无：`nvl`、`nvl2`、`isnan`、`nanvl`、`chr`、`sha1`、`to_timestamp`、`trunc`、`months_between`、`make_date`、`sort_array`、`array_union/except/size/compact`、`map_contains_key`、`bit_count`、`format_number/format_string/printf`、`xpath*`、`from_json`（均经 functions.py/FunctionSet/文档全文搜索确认不存在）

## 7. 高频函数 Top 80（按 Spark ETL 经验排序）

类：A 兼容 / B 同名异义 / C 别名 / D 缺失。

| # | Spark 函数 | 类 | StarRocks 对应 | 差异要点 | 适配策略 | 难度 |
|---|---|---|---|---|---|---|
| 1 | `count` | B | count | count(DISTINCT a,b) supported; count(*) ok; count(a,b) multi-arg (non-distinct, NULL if any null) not in SR | rewrite | S |
| 2 | `sum` | B | sum | decimal sum precision (+10 digits) differs; integer sum -> BIGINT both; overflow wraps (Spark) vs error/NULL | check decimal | S |
| 3 | `coalesce` | A | coalesce | - | none | - |
| 4 | `nvl` | C | ifnull | same semantics | alias nvl->ifnull in FE function resolver | S |
| 5 | `ifnull` | A | ifnull | - | none | - |
| 6 | `if` | A | if | - | none | - |
| 7 | `nullif` | A | nullif | - | none | - |
| 8 | `nvl2` | C | if(isnotnull(a),b,c) | expr rewrite | FE rewrite to IF(a IS NOT NULL,b,c) | S |
| 9 | `concat` | B | concat | Spark concat: any NULL -> NULL (strings) same as SR; also concats ARRAYs/BINARY (SR concat is varchar only; array_concat for arrays) | dispatch by arg type: array->array_concat | S |
| 10 | `concat_ws` | A | concat_ws | both skip NULL args; Spark accepts array<string> args and SR has ARRAY overload | none | - |
| 11 | `substring` | B | substring | same as substr | - | S |
| 12 | `substr` | B | substr | Spark substr(s,0,n) treats 0 as 1; SR pos=0 returns '' ; negative start ok both | rewrite pos 0->1 (if literal) or CASE | S |
| 13 | `length` | B | length | SR length() = BYTES (MySQL style) ; Spark length = characters (bytes for binary) | remap to char_length for strings | S |
| 14 | `lower` | A | lower | - | none | - |
| 15 | `upper` | A | upper | - | none | - |
| 16 | `trim` | B | trim | Spark TRIM(BOTH/LEADING/TRAILING c FROM s) syntax; trim(c FROM s); SR trim(str[,chars]) fn form | parser rewrite | S |
| 17 | `split` | B | split | Spark split(str, REGEX [,limit]) vs SR split(str, LITERAL delimiter) (no limit); trailing empty strings kept in Spark(limit=-1); SR split('abc','') -> chars; Spark 'a1b2c' split by '[0-9]' works only in Spark | if delimiter is regex-metachar-free -> split; else regexp_split (SR has regexp_split? docs list regexp_split) ; limit via array_slice | M |
| 18 | `regexp_replace` | B | regexp_replace | replacement syntax: Spark uses $1 backrefs (Java), SR uses \1 (RE2); Spark 4th arg position(3.5) | translate $n -> \n in replacement; Java->RE2 regex check | M |
| 19 | `regexp_extract` | B | regexp_extract | Spark idx default=1 (2-arg form ok), SR requires 3rd arg; Java regex vs RE2: no lookahead/lookbehind/backrefs; non-match returns '' in both; idx=0 whole match; case-sensitivity same | add default idx=1; regex dialect translation/check; accept limitation | M |
| 20 | `replace` | B | replace | Spark replace(str,search) 2-arg form (replace with empty) missing in SR 3-arg only | add overload | S |
| 21 | `lpad` | B | lpad | Spark lpad(str,len,pad) truncates when len<length like SR; pad default ' '; SR 2-arg ok; empty pad -> SR NULL vs Spark returns str | S | S |
| 22 | `rpad` | B | rpad | same as lpad | - | S |
| 23 | `instr` | A | instr | 1-based, 0 not found; same | none | - |
| 24 | `locate` | A | locate | locate(substr,str[,pos]) 1-based; Spark locate(sub,str,0)=0 ; SR pos semantics similar | test pos<=0 | S |
| 25 | `get_json_object` | B | get_json_object (alias of get_json_string) | returns VARCHAR; JSONPath subset ($.a.b[0], $['a']); Spark returns JSON text for objects/arrays, SR get_json_string returns string of the value (nested -> JSON text?). Wildcard/filters unsupported; invalid JSON -> NULL both | test against Spark corpus; fall back to json_query | M |
| 26 | `json_tuple` | D | get_json_string x N (+ LATERAL VIEW) | generator returning many columns | FE rewrite to multiple get_json_object | M |
| 27 | `from_json` | D | (none) | from_json(str, schema) -> struct; SR parse_json + cast / json_query/get_json_* | FE rewrite: from_json(s,'a INT, b STRING') -> row(get_json_int(s,'$.a'),get_json_string(s,'$.b')) for flat schema; nested -> cast(parse_json as struct) (verify) | L |
| 28 | `to_json` | B | to_json | Spark to_json(struct/map/array, options) -> JSON text; SR to_json(map/struct) -> JSON type | cast to string | S |
| 29 | `date_format` | B | date_format | Spark uses Java DateTimeFormatter patterns (yyyy-MM-dd HH:mm:ss, EEE, a); SR uses MySQL specifiers (%Y-%m-%d %H:%i:%s); BE only special-cases yyyyMMdd/yyyy-MM-dd/yyyy-MM-dd HH:mm:ss | FE translate constant pattern Java->MySQL (rewriter) ; non-constant: reject or UDF | M |
| 30 | `to_date` | B | to_date / str_to_date | Spark to_date(str[,fmt]) with Java fmt & string parse; SR to_date(DATETIME) only 1-arg | rewrite: 1-arg -> to_date(cast(x as datetime)) ; 2-arg -> date(str_to_date(translate fmt)) | M |
| 31 | `to_timestamp` | B | str_to_date / cast | to_timestamp(str[,fmt]) Java fmt; unparsable ->NULL (non-ANSI) | rewrite w/ fmt translation | M |
| 32 | `unix_timestamp` | B | unix_timestamp | Spark unix_timestamp(str[,fmt]) default fmt yyyy-MM-dd HH:mm:ss, Java pattern; SR 1-arg takes DATETIME and out-of-range (<1970,>2038) -> 0; 2-arg uses MySQL fmt | translate pattern; check 2038 limit (64-bit variants exist) | M |
| 33 | `from_unixtime` | B | from_unixtime | Spark default fmt yyyy-MM-dd HH:mm:ss same; custom fmt Java vs MySQL (BE special-cases few); timezone: session tz both | translate pattern | M |
| 34 | `datediff` | A | datediff | datediff(end,start) same; Spark 3-arg datediff(unit,..) is 4.0 | none | - |
| 35 | `date_add` | B | date_add / days_add | Spark date_add(date, N) returns DATE; SR date_add(d,INT) returns DATETIME. SR also accepts INTERVAL form; Spark also accepts string date; Spark accepts negative N | cast result to DATE | S |
| 36 | `date_sub` | B | date_sub | same caveat as date_add (DATE vs DATETIME) | - | S |
| 37 | `add_months` | B | add_months | returns DATE in Spark, DATETIME in SR; end-of-month rule: Spark 3.x does NOT pin to month end (2022-02-28+1m=2022-03-28); SR same except docs | cast result to date | S |
| 38 | `trunc` | B | date_trunc | Spark trunc(date, fmt) (date first, fmt 'YEAR','MM','QUARTER','WEEK'); date_trunc(fmt, ts) fmt first; SR date_trunc(fmt, ts) only | rewrite trunc(d,f)->date_trunc(map f,d) cast date; MM->month etc. | S |
| 39 | `date_trunc` | B | date_trunc | fmt first both; Spark fmt aliases 'YYYY','YY','MON','MM','DD','DAYOFYEAR' etc; week starts Monday both | map aliases | S |
| 40 | `current_date` | A | current_date | - | none | - |
| 41 | `current_timestamp` | B | current_timestamp | type TIMESTAMP w/ microsecond; SR DATETIME seconds precision (ms in newer) | S | S |
| 42 | `last_day` | B | last_day | Spark has no unit arg; returns DATE ok | none | S |
| 43 | `months_between` | D | (none) | fractional months (31-day month, roundOff=true) | BE fn or rewrite using months_diff+days | M |
| 44 | `next_day` | B | next_day | Spark day names case-insensitive (Mon/Monday); SR case-sensitive & error on invalid | wrap lower->initcap | S |
| 45 | `cast` | B | CAST | cast semantics: string->int lenient (Spark: '1.5'->1, 'abc'->NULL); SR strict & different on overflow / invalid; string->date accepts different formats; double->string repr ('1.0E10' vs '1.0E10'); int->boolean; timestamp<->long unit; decimal rounding | build compat cast adapter per type pair; start with explicit cast set | L |
| 46 | `round` | B | round | Half-up both (half away from zero). Diff: round(DOUBLE) 1-arg returns BIGINT in SR vs DOUBLE in Spark; decimal result precision/scale rules differ; TINYINT/INT input types | FE: add return-type adaptation (cast to double) | M |
| 47 | `floor` | B | floor | same caveat as ceil: 2-arg floor(x,scale) missing | rewrite | S |
| 48 | `ceil` | B | ceil | Spark ceil(DOUBLE)->BIGINT, ceil(x,scale) 2-arg form (3.3+) not in SR; decimal result precision differs | S: add 2-arg overload via rewrite | S |
| 49 | `greatest` | B | greatest | Spark skips NULL args; SR returns NULL if any arg NULL | rewrite: greatest(coalesce-safe) i.e. compute over non-null via CASE/array_max(array_filter) or new BE flag | M |
| 50 | `least` | B | least | same as greatest: Spark skips NULLs, SR returns NULL | same as greatest | M |
| 51 | `row_number` | A | row_number | - | none | - |
| 52 | `rank` | A | rank | - | none | - |
| 53 | `dense_rank` | A | dense_rank | - | none | - |
| 54 | `lag` | A | lag | - | none | - |
| 55 | `lead` | A | lead | ignoreNulls opt | none | S |
| 56 | `first_value` | B | first_value | window form exists w/ IGNORE NULLS; as aggregate (Spark allows) not in SR | window OK; aggregate -> any_value | S |
| 57 | `collect_list` | B | array_agg | SR array_agg includes NULLs; Spark collect_list drops NULLs; ordering undefined both | rewrite to array_agg(x) FILTER/ array_agg(if x is not null) + array_filter(is not null) or array_remove(arr,NULL) | S |
| 58 | `collect_set` | B | array_unique_agg | SR array_unique_agg (docs) / array_agg(DISTINCT) ; both drop dupes; NULL handling check | map to array_agg(distinct x) w/ null filter or array_unique_agg | S |
| 59 | `explode` | C | unnest (table fn) | Spark: SELECT explode(arr) / LATERAL VIEW explode; SR: FROM t, unnest(arr) AS x or LATERAL JOIN unnest; map explode has no SR equivalent (unnest on map unsupported)  | FE rewrite of generator in SELECT list and LATERAL VIEW to LATERAL JOIN unnest(); map via map_keys/map_values+unnest | L |
| 60 | `posexplode` | C | unnest(arr, generate_series(...)) / unnest + row index | SR unnest has no position output; need arrays_zip/generate_series trick | FE rewrite: unnest(arr, array_generate(1,len)) minus 1 (Spark pos is 0-based) | L |
| 61 | `stack` | D | unnest(array(row(..),row(..)))/UNION ALL | stack(n, a,b,c,d) pivot columns->rows | FE rewrite to LATERAL unnest over array of structs, or UNION ALL | M |
| 62 | `size` | B | array_length / map_size / cardinality | Spark size(NULL) = -1 (legacy sizeOfNull=true default), SR NULL; works on arrays and maps | rewrite size(x)->coalesce(cardinality(x),-1) | S |
| 63 | `array_contains` | B | array_contains | Spark returns NULL if element not found but array contains NULL; SR returns 1/0 (boolean int) | test null semantic | S |
| 64 | `element_at` | B | element_at | Spark array element_at 1-based, negative index counts from end, out-of-range ->NULL (non-ANSI); SR positive only. Spark arr[i] operator is 0-BASED (!), SR [] is 1-based | FE rewrite subscript on arrays: arr[i] -> element_at(arr,i+1); negative index CASE | M |
| 65 | `sort_array` | B | array_sort | sort_array(arr[,asc]) asc: NULLs first; desc: NULLs last; SR array_sort asc only (+array_sortby / reverse) | rewrite: asc->array_sort; desc->reverse(array_sort(..)) | S |
| 66 | `array` | A | [..] / array() | array(1,2) ok; empty array() type | none | S |
| 67 | `array_join` | A | array_join | null_replace third arg same | none | - |
| 68 | `stddev` | B | stddev_samp | SR stddev = POPULATION stddev (alias stddev_pop); Spark stddev = SAMPLE | MUST remap: stddev,std,stddev -> stddev_samp | S |
| 69 | `variance` | B | var_samp | SR variance = POPULATION variance (alias var_pop); Spark variance = SAMPLE | remap variance -> var_samp | S |
| 70 | `percentile_approx` | B | percentile_approx | 3rd arg: Spark accuracy (default 10000, bigger=more accurate) vs SR compression in [2048,10000]; results differ (t-digest vs GK-like); SR supports array p | map accuracy arg->compression or drop | S |
| 71 | `approx_count_distinct` | B | approx_count_distinct (HLL) | Spark HLL++ rsd param (2nd arg) unsupported; results differ numerically (error ~2% vs ~1%) | accept 1-arg; drop 2nd arg | S |
| 72 | `count_if` | A | count_if | - | none | - |
| 73 | `max_by` | A | max_by | - | none | - |
| 74 | `md5` | A | md5 | - | none | - |
| 75 | `sha2` | A | sha2 | bit length 0/224/256/384/512 ; invalid ->NULL | test | S |
| 76 | `hash` | B | murmur_hash3_32 | Spark hash() = Murmur3_x86_32 seed 42 chained over columns with type-specific hashing (ints as 4/8 bytes, strings utf8, NULL skip); SR murmur_hash3_32 uses seed 104729 and VARCHAR inputs only -> numerically different. Bucketing/partitioning compatibility broken | new BE fn spark_hash(...) implementing Spark algo | L |
| 77 | `sequence` | C | array_generate / generate_series | Spark sequence(start,stop[,step]) for ints and dates/timestamps (+interval); SR array_generate ints only | map int; date via rewrite | M |
| 78 | `transform` | C | array_map | Spark transform(arr, x -> f) ; SR array_map(x -> f, arr) lambda FIRST (arg order reversed); index variant (x,i)-> | FE reorder args | S |
| 79 | `filter` | C | array_filter | Spark filter(arr, x->pred) ; SR array_filter(arr, x->..) or array_filter(x->..,arr) (supports lambda forms) | reorder if needed | S |
| 80 | `exists` | C | any_match | exists(arr, x->pred) three-valued logic | rewrite | S |

另有若干高频但 A 类、无需适配的：`abs`、`pow`、`rand`、`min/max/avg`、`year/month/day/hour`、`isnull`、`map_keys`、`named_struct`。

## 8. 缺失函数（D）按难度归类

* S（59，FE 表达式改写）：isnan nanvl randn acosh asinh atanh sec csc expm1 factorial hypot log1p rint width_bucket try_divide try_sum try_to_binary every bool_and regr_count regr_avgx regr_avgy regr_r2 regr_sxx regr_sxy regr_syy regr_slope chr bit_length elt encode luhn_check overlay soundex from_utc_timestamp to_utc_timestamp to_timestamp_ntz/ltz make_date date_from_unix_date unix_date timestamp_millis/micros unix_millis/micros array_union array_compact map_contains_key shuffle array_prepend zip_with get sha sha1 typeof bit_count bit_get getbit mask
* M（38）：inline inline_outer stack bround try_add/subtract/multiply try_aes_decrypt kurtosis skewness regr_intercept mode format_number format_string printf to_char to_varchar levenshtein json_tuple sentences regexp_instr months_between window_time make_timestamp(_ntz/_ltz) array_insert array_except map_from_entries nth_value bit_and bit_or bit_xor bitmap_* schema_of_json
* L（31）：histogram_numeric count_min_sketch hll_sketch_*（DataSketches 二进制格式不通用，SR 有 `ds_hll_*` 可映射但与 Spark 序列化互不兼容）to_number try_to_number xpath* window/session_window（需窗口化聚合重写）make_interval 系（interval 类型）aggregate/reduce（通用 fold lambda）map_zip_with reflect/java_method hll_union from_json（需 schema 驱动的 JSON→STRUCT）from_csv/to_csv/schema_of_csv

## 9. 局限

* Spark 4.0/4.1 新增函数清单来自记忆，仅作参考。
* 部分 B 判定（`initcap`、`translate`、`array_intersect`、`arrays_zip` 结构字段名、`weekofyear`、`parse_url`、`char`）未逐条验证，CSV 中标注 test/verify。
* SR 文档可能落后于代码；若发现 FunctionSet 以外的 FE 内置改写（如 parser 特判）未被本次解析覆盖，数量可能略有偏差。
