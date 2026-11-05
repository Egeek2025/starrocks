# R5: 先行实践 + 非函数语义差异 + 测试策略

标注: [已验证-代码] = 在 /home/user/starrocks 源码确认; [已验证-网页] = 网页检索确认; [待验证] = 凭 Spark/StarRocks 通用知识, 未在本次检索/代码中核实, 落地前需实测。

## 1. 先行实践 (Prior Art)

### 1.1 StarRocks 现有 Trino 方言 (最直接的参照)
- 机制 [已验证-代码]: `SessionVariable.sql_dialect`(默认 "StarRocks") + `enable_dialect_downgrade`(默认 true)。
  `fe/fe-core/.../sql/parser/SqlParser.java:80-90` 在 `parse()` 里判断 `sql_dialect=="trino"` 就走 `parseWithTrinoDialect`:
  用 Trino 官方 parser(io.trino.sql.parser) 解析 -> `connector/parser/trino/TrinoParserUtils/TrinoParser` 的 AstBuilder 把 Trino AST 直接翻译成 StarRocks 的 StatementBase/Expr;
  遇到 `ParsingException` / `TrinoParserUnsupportedException` 则 `rollbackStarRocksDialect` 回退到原生 parser; 成功时 `setRelationAliasCaseInsensitive(true)`(Trino 别名大小写不敏感 —— 这正是一个“方言带来的 case-sensitivity 语义开关”的先例, 与 Spark 大小写不敏感需求直接相关)。
  另有 `analyzer/TranslateAnalyzer.java`、`catalog/HiveView.java` (Hive view 带 Trino/Hive 文本时的翻译)、`docs/en/sql-reference/sql-statements/TRANSLATE_TRINO.md`(翻译语句)。
- 覆盖范围: 仅“部分语法”(代码注释原话 "We only support Trino partial syntax now"), 主要面向数据湖查询(SELECT)。Trino 函数名通过 FunctionMapping/函数别名映射到 SR 函数。官方未给出覆盖率数字。
- 文档: 阿里云 EMR Serverless StarRocks 文档描述了 `set sql_dialect='trino'` 及全局设置、例子 `doy(d)`: https://help.aliyun.com/zh/emr/emr-serverless-starrocks/use-cases/use-trino-syntax-for-data-lake-analysis ; 第三方 DeepWiki 摘要: https://deepwiki.com/StarRocks/starrocks/13.3-trino-connector-and-sql-dialect-compatibility ; 社区讨论“语法兼容是否含函数”: https://forum.starrocks.io/t/can-you-explain-what-trino-syntax-compatibility-mean/35
- GitHub issue/PR: 本次检索 **没有找到** StarRocks 关于 "spark dialect"/"hive dialect" 的 issue/PR(搜索引擎不索引 issue 内文; 建议人工在 github.com/StarRocks/starrocks/issues 搜 `sql_dialect`、`spark`、`hive dialect` 复核)。
- 经验教训: (a) “换 parser + AST->AST 翻译”可行, 但维护成本=第二套 grammar+AstBuilder; (b) 回退机制(downgrade)非常重要; (c) 方言只改 FE, BE 函数语义不变 —— Trino 与 SR 在函数/类型语义上的差异靠“别名映射”而不是新实现, 对 Spark 这种语义差异大(如 ANSI、cast、round)的场景不够。

### 1.2 Apache Doris SQL Dialect Conversion
- 架构: 独立的 SQL Convertor 服务(基于 **SQLGlot**, SelectDB 开发, 官方标注 experimental), 默认端口 5001, FE 通过全局变量 `sql_converter_service_url` 调用(HTTP), 3.0.7 起支持多 URL 高可用并优先本机; 会话变量 `sql_dialect` 选择方言(presto/trino/clickhouse/hive 为文档列出的; 另一页提到 spark/postgresql), `serde_dialect` 控制结果展示格式(Hive/Presto/Trino/Doris), `enable_sql_convertor_features=ctas`。
  语义不完全一致的函数, 文档建议用户自己写 UDF, 并通过 `sql_convertor_config.ignore_udf` 让转换器跳过这些函数。
- 链接: https://doris.apache.org/docs/lakehouse/sql-convertor/sql-convertor-overview , https://doris.apache.org/docs/lakehouse/sql-dialect
- 教训: 优点=不进内核、迭代快、复用 sqlglot 方言生态; 缺点=引入外部 Python 服务(运维/延迟/单点)、文本到文本转换丢失类型信息(无法知道 `a/b` 的类型, 无法做语义感知改写)、语义差异只能“尽量对齐 + UDF 兜底”。**“UDF 兜底 + ignore_udf”** 这一思路值得借鉴: 即 Spark 语义函数以 UDF/内置同名函数形式存在, 转换器不再改写。

### 1.3 sqlglot spark -> starrocks
- sqlglot 有 `starrocks` 方言(继承 MySQL), 也有成熟的 Spark 方言。已知弱点: UNNEST/LATERAL VIEW explode(多数组 zip)、类型映射、丢参告警。https://github.com/tobymao/sqlglot/issues/3962 ; 测试文件(含 hive 对多参 UNNEST 抛 UnsupportedError): https://sources.debian.org/src/sqlglot/28.6.0-1/tests/dialects/test_starrocks.py 。WrenAI 讨论指出 Doris 方言与 StarRocks 不同需用 starrocks 方言: https://github.com/Canner/WrenAI/discussions/2788
- 结论: sqlglot 只做语法/函数名层面转写, 不保证语义等价; 可作为**离线对照/测试辅助(oracle for syntax rewrite)**, 不宜作为运行期唯一依赖。未发现公开的质量基准数据。
- Calcite 也有 StarRocks 方言请求(CALCITE-6257), 同样基于 MySQL 方言: https://issues.apache.org/jira/browse/CALCITE-6257

### 1.4 Velox / Gluten (最相关的“语义分离”范例)
- Velox 把 `functions/prestosql` 与 `functions/sparksql` 分目录实现, 同名函数各自语义(如 round、cast、hash、concat、substring 下标、NULL 处理)。Gluten 注册顺序: 先 Presto 再 Spark, 同签名 Spark 覆盖 Presto; 查找时先 spark 类别再 presto。Gluten 另有 `overlay` 目录在上游修复前覆盖 Velox 语义有差距的实现(如 Round.h)。
- Gluten **不支持 ANSI 模式, 开启即回退原生 Spark** —— 重要经验: 不能保证语义一致时, 明确“回退/拒绝”比悄悄给出不同结果好。支持度文档(Spark3.3 共 387 函数, 支持约 189-199): https://gluten.apache.org/docs/velox-backend/support ; 开发指南: https://gluten.apache.org/developers/velox-function-development-guide.html
- 教训: 对 StarRocks, 应有 **独立的 “spark 函数命名空间/注册表”**(BE 与 FE 常量折叠两侧), 在 sql_dialect=spark 下优先解析; 并有“不支持清单/严格模式”。

### 1.5 DataFusion `datafusion-spark` / Comet
- `datafusion-spark` 是 DataFusion 主仓库的子 crate, 提供 Spark 兼容表达式; 来源于 Comet 的 `datafusion-comet-spark-expr`(正在迁移并停止发布, 跟踪: apache/datafusion-comet#2084)。文档建议实现新函数先参考 Sail 与 Comet 的现有实现并移植测试。https://docs.rs/crate/datafusion-spark/latest , https://github.com/apache/datafusion-comet/pull/638
- 教训: 与 Velox 一致 —— Spark 语义函数 **独立 crate/目录**, 并**移植 Spark 自身测试用例**作为验收。Sail(LakeSail)是另一个可参考的 Spark-on-Rust 项目 (未在本次检索核实细节)。

### 1.6 Kyuubi / Databend / EMR 等 [待验证 - 本次未检索到可靠结果]
- Kyuubi 是 Spark SQL 网关(多租户、JDBC/Thrift), 本身不做方言转换; 其 Spark SQL extension 可作为“用 Spark 作 oracle 的差分测试端点”。Databend 有自己的 `sql_dialect`(PostgreSQL/MySQL/Hive/Prql 等 parser 模式), 其中 Hive 模式主要影响标识符引号与字符串字面量; 具体需查 databend 文档。阿里云 EMR StarRocks 仅见 Trino 语法文档, 未见 Spark 兼容文档。
- Hive UDF: StarRocks 官方 `CREATE FUNCTION` 当前仅支持 Java UDF/UDAF/UDWF/UDTF [已验证-代码: docs/en/sql-reference/sql-statements/Function/CREATE_FUNCTION.md 第8行]; 没有 `type hive` 或直接加载 Hive `GenericUDF`/`UDF` 的能力。Spark/Hive UDF 适配层需自建(参见 Hive `UDF` evaluate 反射、`GenericUDF` ObjectInspector, Spark `UDF1..N`, `ExpressionEncoder` 类型映射)。

### 1.7 先行实践的架构选项对比
| 方案 | 代表 | 优点 | 缺点 |
|---|---|---|---|
| A. FE 内置第二套 parser + AST 翻译 | StarRocks Trino | 无外部依赖, 类型感知, 可回退 | 维护第二套 grammar; 工作量大 |
| B. 外部转写服务 (sqlglot) | Doris | 快速上线, 复用生态 | 文本级, 无语义感知, 运维外部服务 |
| C. 扩展原生 grammar + 会话变量切换语义 | (建议) | 复用 SR parser, 增量(Spark 语法多为 SR 语法超集的小补丁), 可在 analyzer 层语义感知 | 需改 StarRocks.g4, 与主线 merge 成本 |
| D. 独立 Spark 函数库 | Velox/DataFusion | 语义精确 | 双份实现, 需要大量测试 |
推荐: **C + D**(语法层加 `sql_dialect=spark` 开关的增量规则与 AST 重写; 函数/cast/算术语义用独立 Spark 命名空间 + sql_mode 类标志), 并保留 A 的 downgrade 回退; B 仅作测试对照。

## 2. 非函数语义差异矩阵
严重度: blocker=大量 Spark SQL 无法运行/静默错结果; high; med; low。

### 2.1 类型系统
| 项 | Spark | StarRocks | 严重度 | 处理 |
|---|---|---|---|---|
| STRING | 无长度上限 | `STRING`=VARCHAR(65533)(早期 1048576) 且 `VARCHAR(n)` 按 **字节** 计 [待验证 版本差异] | med | parser: STRING->STRING 别名; 建表/CTAS 默认最大长度; 校验 UTF-8 字节语义 |
| DECIMAL | 默认 DECIMAL(10,0); 精度最大 38; 运算结果精度按 Spark 规则(adjustPrecisionScale), 溢出 ANSI off 返回 NULL | DECIMAL32/64/128 最大 38; 运算精度规则与 Spark 不同(如除法 scale), `div_precision_increment` 存在 [已验证-代码] | high | analyzer 层 Spark 专用 decimal 推导规则(flag); 差分测试覆盖 |
| TIMESTAMP | 带会话时区的瞬时(内部 UTC 微秒), 显示按 `spark.sql.session.timeZone` | DATETIME 无时区(墙钟), 按 `time_zone` 变量转换仅在部分函数/导入 | high | 将 Spark TIMESTAMP 映射为 DATETIME, 并保证 `from_utc_timestamp/to_utc_timestamp/current_timestamp` 以 session tz 语义; 建议文档声明限制 |
| TIMESTAMP_NTZ (Spark 3.4+) | 无时区 | 与 DATETIME 等价 | low | 映射 DATETIME |
| 精度 | 微秒(6) | DATETIME 支持到微秒(版本相关) [待验证] | low | |
| BINARY | 有 | VARBINARY(3.0+) | med | 类型别名 BINARY->VARBINARY; 字面量 `X'..'` |
| TINYINT/SMALLINT/INT/BIGINT/FLOAT/DOUBLE/BOOLEAN | 同名 | 同名(LARGEINT 额外) | low | |
| 复杂类型 ARRAY/MAP/STRUCT | 有, 下标 0 起始 (`arr[0]`)? Spark `arr[0]` 为 0 起, `element_at` 为 1 起 | SR ARRAY 下标 **1 起** | high | 方言下重写 `[]` 下标 +1 (ANSI off 越界返回 NULL, SR 也 NULL) |
| INTERVAL (YEAR-MONTH / DAY-TIME) 类型、`INTERVAL 1 DAY` 字面量多单位 `INTERVAL '1 2' DAY TO HOUR` | 支持 | SR 支持 `INTERVAL n unit` 表达式, 无独立 interval 类型, 不能作为列类型 | med | 字面量重写为 SR interval 表达式; 列类型不支持则报错 |
| CHAR(n) | 读时补空格 | SR CHAR 有 | low | |

### 2.2 算术、cast、ANSI
| 项 | Spark | StarRocks | 严重度 | 处理 |
|---|---|---|---|---|
| ANSI 开关 | 3.x 默认 `spark.sql.ansi.enabled=false`, 4.0 默认 true(溢出/非法 cast/除零报错) https://docs.databricks.com/en/sql/language-manual/sql-ref-ansi-compliance.html | `sql_mode` 有 `ERROR_IF_OVERFLOW`、`ERROR_FOR_DIVISION_BY_ZERO` 等位 [已验证-代码 SqlModeHelper.java:85,94,141-148] | high | 新增 session var `spark.sql.ansi.enabled`->映射到 sql_mode 位 + cast 严格化; 默认跟 3.x(false)并可配 |
| `/` 整数除法 | `int/int` -> double (非 ANSI: 除零 NULL; ANSI: 报错); `div` 为整除 | SR `int/int` 返回 DOUBLE, 除零默认 NULL(取决 ERROR_FOR_DIVISION_BY_ZERO) [待验证具体版本] | med | 基本一致; 需要 `div` 关键字、`%`/`mod` 符号差异测试 |
| 整数溢出 | 非 ANSI: 回绕(wrap); ANSI: 报错 | SR: 默认(含 ERROR_IF_OVERFLOW 时)报错, 否则类型提升? [待验证] | med | 差分测试确认, 回绕语义很可能需要 Spark 版算术函数 |
| 非法 cast `cast('abc' as int)` | 非 ANSI: NULL; ANSI: 报错 | SR: NULL(默认) | low | ANSI 开时需严格 cast |
| cast string->date/timestamp 容错格式 (`'2020-1-1'`, 带 T) | 宽松 | 较严格/部分宽松 | med | 差分测试 |
| cast double->int 截断/NaN/负零 | 截断; NaN->0 | 待验证 | med | |
| 字符串与数字比较 | 非 ANSI 隐式 cast 字符串->double (或另一侧);4.0 ANSI 更严格 | SR 比较时将字符串转数值/DOUBLE? 规则不同 [待验证] | high | 差分测试 + analyzer 隐式转换规则 flag |
| 字符串 `+` 数字 | `'1'+1` = 2.0 | SR: 隐式转换, 类型可能不同 | med | |
| 浮点 NaN 比较/排序 | NaN 等于自身, 且大于一切 | SR 遵循 IEEE? [待验证] | med | |
| 字符串字面量转义 | 反斜杠转义(默认), `''`; `spark.sql.parser.escapedStringLiterals` | MySQL 风格转义, 类似 | low | |
| 双引号 | 字符串字面量(非 ANSI) | SR: 字符串字面量(MySQL 风格, 不是标识符) | low | 一致 |
| 标识符反引号 | `` ` `` | `` ` `` | low | 一致 |

### 2.3 查询语法
| 项 | Spark | StarRocks | 严重度 | 处理 |
|---|---|---|---|---|
| NULL 排序 | ASC=NULLS FIRST, DESC=NULLS LAST | SR 同样 ASC NULLS FIRST、DESC NULLS LAST(MySQL 风格), 另有 sql_mode `SORT_NULLS_LAST` 位 [已验证-代码 SqlModeHelper.java:113, grammar 第2546行支持 NULLS FIRST/LAST] | low | 保证 sql_mode 不含 SORT_NULLS_LAST |
| GROUP BY 序号/别名 | 支持序号(`spark.sql.groupByOrdinal`)、别名(`groupByAliases`) | SR 支持序号; 别名通过 `enable_groupby_use_output_alias` [已验证-代码 SessionVariable:562] | med | 方言下默认打开该变量 |
| ROLLUP/CUBE/GROUPING SETS, `WITH ROLLUP`, `GROUPING__ID`/`grouping_id()` | 全支持, 含 `GROUP BY a, ROLLUP(b)` 混合 | SR 支持 ROLLUP/CUBE/GROUPING SETS; `grouping_id` 位序、Spark `GROUP BY ... WITH CUBE` 旧语法 [待验证] | med | parser 增补 `WITH ROLLUP/CUBE` |
| HAVING 无 GROUP BY | 允许 | SR [待验证] | low | |
| SELECT 无 FROM | 允许 | 允许 | low | |
| UNION | 默认 DISTINCT(`UNION`=去重), UNION ALL | 同 | low | |
| INTERSECT/EXCEPT | 支持 DISTINCT/ALL(`INTERSECT ALL`, `EXCEPT ALL`); `MINUS` 同 EXCEPT | grammar 有 INTERSECT/UNION/EXCEPT/MINUS (g4:2532-2533); `ALL` 量词对 INTERSECT/EXCEPT 支持情况 [待验证] | med | 缺失则用窗口函数 rewrite |
| LATERAL VIEW explode/posexplode/inline, `OUTER` | 常用(blocker 级常见) | SR 用 `unnest` + `LATERAL` 表函数 | high | AST 重写 `LATERAL VIEW explode(x) t AS c` -> `CROSS JOIN LATERAL unnest(x) AS t(c)`; OUTER -> left join lateral |
| TABLESAMPLE、DISTRIBUTE BY / CLUSTER BY / SORT BY | Spark 特有 | SR 无 | med | 解析后忽略 DISTRIBUTE/CLUSTER, SORT BY -> ORDER BY(仅 final 不需要) |
| 位置参数 `?`、命名参数 `:name` | 3.4+ | SR 有 PREPARE | low | |
| Hint `/*+ BROADCAST(t) */`, `REPARTITION` | Spark | SR hints 不同(`[BROADCAST]`, `/*+ SET_VAR */`) | med | 忽略未知 hint 或映射 BROADCAST/SHUFFLE |
| CTE、子查询、窗口(`ROWS BETWEEN`, `RANGE`) | 支持 | 支持 | low | 窗口默认 frame/ NULL 处理差异需测 |
| 大小写 | 默认列/表/函数名不区分大小写; `spark.sql.caseSensitive=false`; 但结果列名保留原样 | SR: 表名大小写敏感(取决 lower_case_table_names/版本), 列名不敏感 | high (项目目标之一) | 参照 Trino 方言 `setRelationAliasCaseInsensitive(true)` 的做法, 扩展到表名/库名/列名解析, 以 session var 控制 |
| 结果列名 | 未命名表达式列名为原始表达式文本 (`count(1)`, `(a + b)`) | SR 列名为表达式文本, 风格不同 | low-med | 对比测试时需归一化 |

### 2.4 DDL / DML / 命令
| 项 | Spark | StarRocks | 严重度 | 处理 |
|---|---|---|---|---|
| CREATE TABLE ... USING parquet / STORED AS / PARTITIONED BY(col) / LOCATION / TBLPROPERTIES | Spark/Hive 语法 | SR 内表需 DISTRIBUTED BY/ENGINE, 外表需 Hive catalog | high | 区分: 目标表在 Hive/Iceberg catalog -> 走 SR 外表 DDL(`CREATE TABLE` in hive catalog 支持 3.2+); 内表: 解析并补默认 `DUPLICATE KEY`/`DISTRIBUTED BY RANDOM` |
| CTAS | 支持 | 支持 (语法差异: 无 USING) | med | |
| TEMP VIEW / CREATE OR REPLACE TEMP VIEW / GLOBAL TEMP VIEW | 会话级 | SR 无会话级视图 | high | 需 FE 层 session-scope view registry(比较复杂), 或映射为 CTE 内联; 先支持 `CREATE TEMPORARY VIEW` 作为会话对象 |
| CACHE TABLE / UNCACHE / REFRESH TABLE / ANALYZE TABLE ... COMPUTE STATISTICS | 常见 | SR 有 `ANALYZE TABLE`(语法不同), `REFRESH EXTERNAL TABLE` | med | CACHE/UNCACHE -> no-op; REFRESH TABLE -> `REFRESH EXTERNAL TABLE`; ANALYZE 映射 |
| INSERT OVERWRITE (动态分区 `INSERT OVERWRITE t PARTITION(p)`, 静态 `PARTITION(p='x')`) | 支持; 默认 static overwrite 会清整表(`partitionOverwriteMode=static`) | SR: `INSERT OVERWRITE`(grammar g4:1362 有), 分区覆盖语义为 dynamic/partition-level; 语法 `PARTITION (p=val)` [待验证] | high | 分区子句语法重写; 语义取 SR 的分区级覆盖(对 `dynamic` 模式一致, 对 static 模式需告知差异) |
| INSERT INTO ... PARTITION(p=...) | 支持 | SR 内表 `PARTITION (p1,p2)` 为分区名, 非值; Hive catalog 的静态分区写 3.x 支持 | med | |
| MERGE INTO / UPDATE / DELETE | Delta/Iceberg 表支持 | SR 主键表 UPDATE/DELETE; MERGE 无 [待验证, grammar g4:1388 显示 DELETE ... USING] | med-high | 内表主键表映射为 UPSERT; 其他不支持 |
| TRUNCATE、DROP ... PURGE、IF EXISTS | 支持 | 基本支持 | low | |
| `SET key=value` / `SET spark.sql.x=...` | 设置 conf, 未知键只警告 | SR `SET var=...` 未知变量会报错 | high | 方言下: 已知 spark.* 映射到 SR 变量, 其余 no-op 忽略并 warning; 无参 `SET` 和 `SET -v` 返回 conf 表 |
| `USE db` / `USE catalog.db` / `USE CATALOG` | 3.x 多 catalog | SR `USE db`, `USE catalog.db`/`SET CATALOG` | low-med | 统一 |
| SHOW TABLES / DATABASES / COLUMNS / PARTITIONS / CREATE TABLE / FUNCTIONS; DESCRIBE [EXTENDED/FORMATTED] | 输出列: `namespace, tableName, isTemporary`; `col_name, data_type, comment` | SR 输出列名/形状不同(`Tables_in_xxx`) | med | JDBC 客户端若依赖列名则需 shape 适配 (Spark 方言下重排/重命名列) |
| ADD JAR / LIST JAR / ADD FILE | 支持 | 无 | med | 与 UDF 适配层结合: ADD JAR 注册 JAR 到 UDF 类加载器; 否则 no-op |
| CREATE [TEMPORARY] FUNCTION f AS 'class' [USING JAR 'path'] | Hive/Spark UDF | SR 仅 Java UDF `symbol`+`file` 属性 | high | 解析并映射到 UDF 适配层(核心工作之一) |
| `EXPLAIN [EXTENDED/FORMATTED/COST/CODEGEN]` | | SR EXPLAIN 不同 | low | |
| SQL 注释、`;`、多语句 | | | low | |

### 2.5 外部 catalog 与命名解析
- Spark 常见 `spark_catalog.db.tbl` / `catalog.db.tbl` / 单 `db.tbl`(隐式 session catalog = Hive metastore)。StarRocks: 三段名 `catalog.db.tbl` 已支持; 默认 catalog 为 `default_catalog`(内表)。高: 需 session 变量 `default catalog`(如 `spark_catalog`->用户选定的 hive catalog), 并支持 `spark_catalog` 别名映射。严重度 high。
- Hive/Iceberg/Delta/Hudi/Paimon: SR 有对应 connector(`fe/.../connector/` 下)。Delta/Hudi 读支持为主, 写入受限; Spark 的 `INSERT OVERWRITE`/`MERGE` 对这些表语义依赖格式。时间旅行 `VERSION AS OF`/`TIMESTAMP AS OF` 与 SR 语法 `FOR VERSION AS OF`? [待验证]。
- Hive 表分区列在 Spark 中位于列表末尾, `SELECT *` 顺序、`__HIVE_DEFAULT_PARTITION__` 处理需核对。
- 视图: Spark 创建的 Hive view 文本是 Spark SQL —— SR 读取 HiveView 时目前有 Trino 路径 (`catalog/HiveView.java`), 需扩展为 spark 方言解析 [已验证-代码 存在 HiveView 对 trino 的处理]。

### 2.6 优先级汇总(建议)
1. Blocker/high: 大小写不敏感、`SET spark.*` 忽略、LATERAL VIEW、CREATE TABLE/ INSERT OVERWRITE 语法、默认 catalog 映射、CREATE FUNCTION USING JAR、temp view、数组下标、ANSI 语义开关、decimal/timestamp 语义。
2. med: 类型别名(STRING/BINARY/interval)、INTERSECT/EXCEPT ALL、hint、SHOW/DESCRIBE 形状、cache/refresh/analyze。
3. low: 其余。

## 3. 兼容性测试策略
1. **Spark 官方 SQL golden 测试**: `sql/core/src/test/resources/sql-tests/inputs/*.sql` 及对应 `results/*.sql.out`(含 `ansi/`, `postgreSQL/`, `udf/`, `subquery/`, `typeCoercion/`, `datetime-*`, `decimalArithmeticOperations.sql`, `cast.sql`, `group-by.sql`, `null-handling.sql`, `window.sql`, `array.sql` 等)。优势: 自带期望输出, 含 ANSI 版本。做法: 写 harness 把 `.sql` 按 `--QUERY-DELIMITER`/`--SET`/`--IMPORT`/`--CONFIG_DIM` 切分, 逐条在 SR 执行, 对比 `.sql.out`(schema + 结果, 错误信息只比较类别)。建立 **allowlist/known-failure 清单** 逐步收敛, 以“通过率”作为里程碑(类似 Gluten/Comet 对 Spark 测试的做法)。
2. **TPC-DS / TPC-H (Spark 版)**: Spark 仓库 `sql/core/src/test/resources/tpcds/q*.sql`(及 `tpcds-v2.7.0`, `tpcds-modifiedQueries`) 与 `tpch`。用于验证 Hive 表读取 + 大量窗口/rollup/INTERSECT 语法, 以及性能基线。Spark 的 TPCDSQuerySuite 里 golden approved plans 可参考。
3. **差分测试(differential)**: 同一 SQL + 同一数据(Parquet/Hive 外表)分别在 Spark(可通过 Kyuubi/Thrift 或 pyspark)与 SR 运行, 归一化比较: 行集合排序、浮点容差、列类型映射、NULL。SQL 来源: (a) Spark golden 输入; (b) 函数/算子随机生成器(基于类型的 fuzz, 边界值: NULL、NaN、溢出、空串、负数、Unicode); (c) 生产查询日志。可参考 Gluten/Comet 的 fuzz 与 `datafusion-comet` 的 expression fuzz。分 ANSI on/off 两套配置。
4. **StarRocks `test/sql` 框架** [已验证-代码: /home/user/starrocks/test/sql 下有大量 `test_*` 目录]: 每个 case 目录含 `T/`(SQL)、`R/`(期望结果), 由 SQL-tester 执行比对。新增 `test/sql/test_spark_dialect_*`, 其中前置 `set sql_dialect='spark'`; 对 Spark golden 通过的子集转存为 R 文件做回归。FE 单测(`fe/fe-core/src/test`)用于 parser/AST 重写的纯翻译断言(类似 Trino 方言的 `TrinoParser*Test`)。
5. **类型/函数覆盖矩阵自动生成**: 从 Spark `FunctionRegistry` / `SHOW FUNCTIONS` 导出函数清单 + `ExpressionDescription` 内 examples(文档示例自带期望值), 作为函数层的第一批用例(Gluten 支持度文档与 sqlglot 测试即此类)。
6. **反向 oracle**: 用 sqlglot (spark->starrocks) 转写 Spark golden 输入, 作为“转写基线”对比我方方言结果, 找出自己漏的语法。
7. **CI 分层**: PR 级跑小型 allowlist(秒级); nightly 跑全量 Spark golden + TPC-DS SF1 差分; 发布前跑 fuzz。度量: 语法通过率(parse ok)、结果一致率、已知差异清单大小。
8. **失败分类**: PARSE_ERROR / UNSUPPORTED(应明确报错) / WRONG_RESULT(最严重) / PERF。借鉴 Gluten: 无法保证语义时 **显式报错或回退**, 绝不静默给不同结果。

## 4. 总体建议摘要
- 沿用 StarRocks Trino 方言模式 (`sql_dialect` + parser 分派 + downgrade 回退), 但对 Spark 采用“扩展原生 grammar + AST 重写 + 会话语义标志”而非整套第二 parser (Spark 语法与 SR 差异多为小补丁); 函数语义走独立 Spark 注册表(Velox/DataFusion 范式)。
- 外部 sqlglot 服务(Doris 模式)只做离线对照。
- 先解决: case-insensitivity、SET 忽略、catalog 默认映射、LATERAL VIEW、DDL/DML 语法重写、ANSI 开关, 然后用 Spark golden 测试驱动迭代。

## 来源
- Doris: https://doris.apache.org/docs/lakehouse/sql-convertor/sql-convertor-overview ; https://doris.apache.org/docs/lakehouse/sql-dialect
- StarRocks Trino: https://help.aliyun.com/zh/emr/emr-serverless-starrocks/use-cases/use-trino-syntax-for-data-lake-analysis ; https://deepwiki.com/StarRocks/starrocks/13.3-trino-connector-and-sql-dialect-compatibility ; https://forum.starrocks.io/t/can-you-explain-what-trino-syntax-compatibility-mean/35
- Gluten/Velox: https://gluten.apache.org/developers/velox-function-development-guide.html ; https://gluten.apache.org/docs/velox-backend/support
- DataFusion: https://docs.rs/crate/datafusion-spark/latest ; https://github.com/apache/datafusion-comet/pull/638
- sqlglot: https://github.com/tobymao/sqlglot/issues/3962 ; https://sources.debian.org/src/sqlglot/28.6.0-1/tests/dialects/test_starrocks.py
- ANSI: https://docs.databricks.com/en/sql/language-manual/sql-ref-ansi-compliance.html
- 源码: /home/user/starrocks/fe/fe-core/src/main/java/com/starrocks/sql/parser/SqlParser.java (80-130), .../qe/SessionVariable.java (SQL_DIALECT 3271, ENABLE_DIALECT_DOWNGRADE 3274, ENABLE_GROUPBY_USE_OUTPUT_ALIAS 562), .../qe/SqlModeHelper.java, /home/user/starrocks/fe/fe-grammar/src/main/antlr/com/starrocks/grammar/StarRocks.g4
