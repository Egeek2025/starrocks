# r3: Spark vs StarRocks 标识符 / 字符串大小写行为调研

说明：Spark 部分中 identifier 页面（sql-ref-identifier）已在线核对；其余（SQLConf、Analyzer resolver、collation）基于 Spark 3.x/4.x 源码与文档知识，collation 页 URL 抓取 404，未在线复核，标注"待核"。StarRocks 部分均为本地源码核对（路径相对 fe/fe-core/src/main/java/com/starrocks，下称 `$FE`）。

## Part A — Spark 行为

| 主题 | Spark 行为 |
|---|---|
| `spark.sql.caseSensitive`（默认 false） | 影响 Analyzer 对列、struct 字段、（Hive 表）分区列的解析；默认 false 时按 `resolver = caseInsensitiveResolution`（`equalsIgnoreCase`）。true 时 `caseSensitiveResolution`（`==`）。SQLConf 中为 internal-ish 配置，运行时可 SET。 |
| 标识符（官方文档） | regular 与 backtick delimited identifier 均 case-insensitive（文档原文）。反引号内可含任意字符，`` `` `` 转义反引号。 |
| 列名解析 | 默认大小写不敏感；`SELECT ID FROM t(id)` 成功。**输出 schema 保留"引用来源"的大小写**：`select ID from t` 输出列名是 `id`（来自表定义的 attribute 名），而 `select ID as Foo` 输出 `Foo`；对未别名的表达式，名称取自 SQL 文本（如 `select Upper(a)` 输出 `upper(a)`，函数名按 FunctionRegistry 规范化为小写，参数列名保留 attribute 的原名）。 |
| 重复列检测 | 同一 relation 中大小写不同的重复列：caseSensitive=false 时 `CREATE TABLE t(a int, A int)` 报 `COLUMN_ALREADY_EXISTS`；DataFrame/写数据源时 `SchemaUtils.checkColumnNameDuplication` 按 resolver 判重。SELECT 中输出两个同名/仅大小写不同列允许（结果集可有重复列），但引用时报 `AMBIGUOUS_REFERENCE`。 |
| 库/表名 | 解析 (`SessionCatalog.formatTableName`) 时：caseSensitive 无关，catalog 内部 `format()` 一律 lowercase（`SessionCatalog.format` 在 `!conf.caseSensitiveAnalysis` 时 lower）。Hive metastore 本身 lowercase 库表列名。V2 catalog（Iceberg 等）名称由 catalog 自己决定，但 Spark 侧 Identifier 解析仍受 resolver 影响。临时视图名也按 `format` 规范化。 |
| Struct 字段 | 字段解析同样走 `resolver`，默认不敏感；输出保留 schema 中的原大小写。`GetStructField` 名称取 schema 里的。 |
| CTE / 别名 | CTE 名、子查询别名按 resolver 比较（`CTESubstitution` 用 `conf.resolver`）；输出保留写法。 |
| 函数名 | 恒不敏感（`FunctionIdentifier` 在 `FunctionRegistry` lower 后查表），与 caseSensitive 无关。 |
| Map key | map key 是数据，**恒大小写敏感**（`m['A']` != `m['a']`）。 |
| 分区列 | 分区 spec 列名按 resolver 校验（`PartitioningUtils.normalizePartSpec`），存储用表定义大小写。 |
| 字符串比较 | `=`、`<`、`ORDER BY`、`GROUP BY`、`DISTINCT` 默认二进制（UTF8_BINARY）区分大小写；`LIKE`、`RLIKE` 区分大小写；`ILIKE`（3.3+）不区分；`LIKE ... ESCAPE` 同。 |
| Spark 4.0 Collation | `COLLATE UTF8_LCASE`/`UNICODE_CI` 等；`CREATE TABLE t(c STRING COLLATE UTF8_LCASE)`，`'a' COLLATE UTF8_LCASE = 'A'` 为 true；默认 `UTF8_BINARY`；4.0 起可设 schema/table 的 DEFAULT COLLATION（4.1 左右，待核）。影响 `=`、`IN`、join、group by、`LIKE`、`contains/startsWith`、`lower/upper` 等。需 `spark.sql.collation.enabled`（4.0 默认 true）。 |
| ANSI 交互 | `spark.sql.ansi.enabled`（4.0 起默认 true）不改变大小写解析；但 ANSI 下 `enforceReservedKeywords` 影响哪些词可作标识符；字符串/数值隐式转换更严格。Collation 与 ANSI 无依赖。 |
| 反引号 | 反引号标识符同样 case-insensitive（默认），`` `A.b` `` 视为单个列名；`spark.sql.legacy.*` 无关。 |
| 输出标签 | `df.columns` / `DESCRIBE` / JDBC ResultSetMetaData 返回保留大小写的名称（Hive 表列因 metastore 为小写，故返回小写；Parquet/Delta 表保留混合大小写）。 |

关键结论：Spark 是"解析不敏感 + 保留原大小写 + 数据比较敏感"。

## Part B — StarRocks 现状

### 1. lower_case_table_names
- 只是 MySQL 兼容变量，**无实际作用**：`$FE/qe/GlobalVariable.java:55,124`；文档 System_variable.md:1499 "No practical usage. Table names in StarRocks are case-sensitive"。
- 真正的开关是 **`enable_table_name_case_insensitive`**（FE Config，`$FE/common/Config.java:5665`；`GlobalVariable.java:175-176` READ_ONLY）：**仅集群初始化时可设，之后不可变**，Leader 启动时校验（`server/GlobalStateMgr.java:3799-3821`, `qe/VariableMgr.java:430`）。开启后：
  - 解析期把 catalog/db/table 名统一转小写：`common/util/Util.java:514 normalizeName`，被 `catalog/TableName.java:85-87,131,135,151,163,168` 与 `sql/parser/AstBuilder.java:717-846,10309` 大量调用；
  - `Field.tryToMatch` 对 struct 路径用 `equalsIgnoreCase`（`sql/analyzer/Field.java:178`）；
  - 授权 `TablePEntryObject.java:185`，`ConnectContext.java:983`，`SRStringUtils.java:70`。
  - 因此它是**集群级、不可逆**，不能用作 session 级 Spark 行为。

### 2. 列名解析（已经不敏感）
- `sql/analyzer/RelationFields.java:56` 以 `toLowerCase()` 建 multimap，`:100` 查找时 lower；`Field.canResolve` `sql/analyzer/Field.java:108-120` 用 `equalsIgnoreCase`；`Scope.resolveField` `sql/analyzer/Scope.java:102-118`。
- 表元数据列名：`catalog/Table.java:564-565` 使用 `TreeMap(CASE_INSENSITIVE_ORDER)`；`ColumnId.CASE_INSENSITIVE_ORDER`。即 **StarRocks 本地表列名天然不敏感，且保留定义时的大小写**。
- Struct 子字段：`fe-type/.../type/StructType.java:56,160-170,182` fieldMap 以小写为 key，**不敏感**，重复检测按小写（`:57` 抛异常）；与 Spark 默认一致。`StructType` 比较 `:122` 用 `equalsIgnoreCase`。

### 3. 表/库/别名/CTE 名（敏感）
- `Field.matchesPrefix` `Field.java:218-233`：catalog、db 用 `equals`，表别名仅当 `ConnectContext.isRelationAliasCaseInsensitive()` 时不敏感。该标志**只有 Trino 方言解析成功后才置 true**（`sql/parser/SqlParser.java:111`，字段 `qe/ConnectContext.java:249,1403-1408`）——这是现成的"方言驱动大小写策略"先例。
- `Field.tryToMatch` 注释 "only table name is case-sensitive"（`Field.java:166-185`）。
- CTE：`sql/analyzer/Scope.java:39,130-141` 用 `LinkedHashMap<String,...>` 精确匹配；`QueryAnalyzer.java:500-506` 重复 CTE 名检测精确匹配。
- 内部库表：`server/LocalMetastore.java:310 fullNameToDb` 为 `ConcurrentHashMap`，`getDb` `:3080-3098`（仅 information_schema/sys 例外不敏感）；`catalog/Database.java:108,142 nameToTable` ConcurrentHashMap，`getTable` `:250,416` 精确匹配。
- CatalogMgr：`server/CatalogMgr.java:251,274` 仅对 `default_catalog` 用 equalsIgnoreCase，其余 catalog 名精确。
- 外部 catalog：Hive `connector/hive/HiveMetastoreApiConverter.java:154`(db lower)；Iceberg `connector/iceberg/IcebergMetadata.java:841-842,875-876`（db/table lower）；Hudi 列名 lower `:571`。即**外部表名已在连接器内 lowercase**，但用户输入的大写名需先到 `TableName` 才能被命中——未开 `enable_table_name_case_insensitive` 时 `SELECT * FROM hive.DB.T` 是否命中取决于连接器是否对入参 lower（Iceberg/Hive 有，部分连接器无）。

### 4. 函数名
- `sql/analyzer/FunctionNameAnalyzer.java:44` 把函数名 `toLowerCase()`，`FunctionSet.java:1224` canonical lower。→ 不敏感，与 Spark 一致。

### 5. 字符串比较 / LIKE / ILIKE
- 无 collation 支持（grep 仅见 information_schema 的 collation 系统表）。`=`、`LIKE`、`GROUP BY` 二进制敏感，与 Spark 默认一致。
- `ILIKE` 通过语法糖 `sql/parser/SyntaxSugars.java:37,63-65` 改写为 `like(lower(a), lower(b))`（注意 lower 只处理 ASCII/BE 实现，非完整 Unicode casefold）。
- 没有 `UTF8_LCASE`/`COLLATE` 语法。

### 6. 输出标签
- `SelectAnalyzer.java:315` 无别名时取 `SlotRef.getColumnName()`（用户**输入的写法**，非表定义写法）。→ `select ID from t(id)` StarRocks 返回 `ID`，Spark 返回 `id`。别名则保留写法，一致。
- Struct 路径引用输出标签亦为写法。

### 7. 重复列
- CreateTable `CreateTableAnalyzer.java:232` ERR_DUP_FIELDNAME，不敏感判重（TreeMap），与 Spark 一致；SELECT 同名列在子查询里引用时报 ambiguous，类似。

## Part C — Gap 表

| 行为 | Spark | StarRocks | 需要改动 | 代码位置 | 风险 |
|---|---|---|---|---|---|
| 列名解析 | 不敏感 | 不敏感 | 无 | RelationFields:56/100, Field:108 | 低 |
| struct 子字段 | 不敏感(默认) | 不敏感 | 无；caseSensitive=true 需另做 | StructType:56,160 | 低 |
| 输出列标签（无别名列引用） | 取表 schema 原名 | 取用户输入写法 | 输出名改取 `Field.getName()`（解析后 Field 名）而非 SlotRef 文本 | SelectAnalyzer:315 附近及 `AnalyzeState.outputExprs` | 中：影响 MV/CTAS 列名、UI 展示 |
| 表别名/relation alias | 不敏感 | 敏感（非 Trino 方言） | spark 方言下 `setRelationAliasCaseInSensitive(true)` | Field.matchesPrefix:228, SqlParser:111 | 低（现成机制） |
| db/catalog 前缀匹配 | 不敏感 | 敏感（`equals`） | 改用策略比较 | Field.matchesPrefix:219-226, tryToMatch:172-178 | 低 |
| 库/表名查找（内部表） | 不敏感（lower） | 敏感；仅集群级一次性开关 | 增加 session 级 fallback：精确未命中时，不敏感查找唯一匹配 | LocalMetastore.getDb:3080, Database.getTable:416/250 | 中：歧义（`T`与`t`并存） |
| 外部 catalog 表名 | 取决 catalog，Hive 小写 | Hive/Iceberg 已 lower | 统一在 MetadataMgr 入口按策略 lower；检查 JDBC/Paimon/Delta/Hudi | MetadataMgr.getDb/getTable, connector/* | 中 |
| catalog 名 | 不敏感 | 敏感（default_catalog 例外） | 查找时 fallback | CatalogMgr:251,274 | 低 |
| CTE 名 | 不敏感 | 敏感 | key 规范化为 lower | Scope:39,130-141, QueryAnalyzer:500-506 | 低 |
| 函数名 | 不敏感 | 不敏感 | 无 | FunctionNameAnalyzer:44 | 低 |
| Map key | 敏感 | 敏感 | 无 | — | 无 |
| 分区列名 | 按 resolver | 不敏感(Column treemap) | 无 | Table:564 | 低 |
| 重复列检测 | 按 resolver | 不敏感 | 无 | CreateTableAnalyzer:232 | 低 |
| `=`/GROUP BY 字符串 | 二进制 | 二进制 | 无 | — | 无 |
| LIKE | 敏感 | 敏感 | 无 | — | 无 |
| ILIKE | 不敏感 | 改写为 like(lower,lower) | 可接受；大小写折叠 Unicode 差异 | SyntaxSugars:37,65 | 低 |
| COLLATE UTF8_LCASE | 4.0 支持 | 不支持 | 大工程：类型系统带 collation，BE 比较/hash/排序/join 全改。短期：语法接受并改写 `col COLLATE UTF8_LCASE = x` → `lower(col)=lower(x)`，或报明确 unsupported | 语法 g4 + Analyzer；BE 暂不动 | 高 |
| `caseSensitive=true` 模式 | 支持 | 不支持（列名永远不敏感） | 不建议实现；仅支持 false，设为 true 时报错 | — | 低 |
| backtick 标识符 | 不敏感 | 同普通标识符（也不敏感列），表名敏感 | 同上 | 词法 | 低 |
| lower_case_table_names | N/A | 空壳 | 不要复用；新增 session 变量 | GlobalVariable:55 | 低 |

## 设计提案：CaseSensitivityPolicy

### 目标
Spark 默认 `caseSensitive=false`：**解析不敏感、保留原大小写、数据比较敏感**。StarRocks 列名/函数/struct 已满足，缺口在：库/表/catalog/CTE/别名前缀、输出标签、collation。

### 开关
- 新增 SessionVariable `spark_case_sensitive`（bool，默认 false，仅在 `sql_dialect=spark` 生效；非 spark 方言完全保持现状）。`sql_dialect` 见 `qe/SessionVariable.java:1027,3271`。设为 true 暂报 unsupported。
- 抽象 `CaseSensitivityPolicy`（放 `sql/analyzer/`）：
  ```java
  interface CaseSensitivityPolicy {
    boolean nameEquals(IdentKind kind, String a, String b); // kind: CATALOG/DB/TABLE/ALIAS/CTE/COLUMN/STRUCT_FIELD
    String  key(IdentKind kind, String s);                   // 用于 Map key 规范化
    static CaseSensitivityPolicy of(ConnectContext ctx);     // spark -> INSENSITIVE_PRESERVING; 否则 LEGACY
  }
  ```
  `LEGACY` 精确复刻现状（含 `enableTableNameCaseInsensitive` 与 `isRelationAliasCaseInsensitive`），保证零回归。
- 替换点：`Field.matchesPrefix`/`tryToMatch`（统一经 policy），`Scope` CTE map 用 `policy.key`，`QueryAnalyzer` 重复 CTE 检测。可顺带把 Trino 的 `relationAliasCaseInsensitive` 标志并入 policy。

### 避免破坏元数据（核心）
1. **不要在 parser 里 lower**（`normalizeName` 方式会让混合大小写的内部表不可达，且该方式需集群初始化一次性开关）。保持 AST 里的名字原样。
2. **在元数据解析入口做"精确优先、不敏感回退"**：`MetadataMgr.getDb/getTable`（及 `LocalMetastore.getDb:3080`、`Database.getTable`）先精确查；未命中且策略不敏感时，遍历 `fullNameToDb`/`nameToTable` 做 `equalsIgnoreCase`；若恰好 1 个命中则返回，>1 个报 `AMBIGUOUS`（避免静默选错）。热路径开销：仅 miss 时线性扫描，可对 db 内表名维护懒构建的 lower→names 索引（Database 内 `volatile` 缓存，DDL 时失效）。
3. 解析后把 `TableName` **重写为元数据中的规范名**（canonical name），后续 privilege 检查、MV 依赖、plan cache、audit 都用规范名，避免权限 bypass（授权对象按精确名存储，`TablePEntryObject:185`）。
4. 外部 catalog 沿用连接器自己的 lower 规则（Hive/Iceberg 已 lower），入口对用户输入也 lower 后再请求 metastore。
5. DDL（CREATE TABLE/DB）在 spark 方言下：保持用户写法存储（Spark+非 Hive 会保留，Hive 表会 lower）；创建前用同样的不敏感冲突检测（`T` 与 `t` 并存报 already exists），与 Spark 语义一致。可选：Hive-compatible 模式 lower。

### 输出标签保留
- Spark 语义：无别名的列引用输出 **解析到的 attribute 名**。在 `SelectAnalyzer`（:315）里，当 policy 为 spark 且 item 是 `SlotRef` 且已解析到 `Field` 时，输出名取 `Field.getName()`（表定义大小写）。别名与表达式文本沿用现状。注意 Spark 的表达式列名（如 `upper(a)`）规则不同，属 r 其它任务（列命名）范围，仅在此提示。
- 外部 Hive 表列已经是小写，无差别。

### Collation
- 阶段 1：解析 `COLLATE UTF8_LCASE|UNICODE_CI|UTF8_BINARY` 子句；`UTF8_BINARY` 忽略；其他 CI 在比较/IN/LIKE/GROUP BY 语义上改写为 `lower()` 包装，GROUP BY 需输出原值（取 `any_value`）——复杂且易错，建议阶段 1 先**明确报 unsupported**。
- 阶段 2：类型系统 `StringType` 带 collation id，BE 比较/hash 支持（工程量大，单列独立立项）。

### 与 ANSI 的关系
大小写策略与 ANSI 正交：不要把二者绑定同一变量。

### 测试策略
1. FE 单测（`fe-core/src/test/.../sql/analyzer/`）：参数化 `LEGACY` vs `SPARK` 两种 policy，覆盖：`SELECT T.A FROM DB.T`、CTE `WITH X ... FROM x`、别名前缀、struct `s.F`、`T`/`t` 并存报歧义、information_schema。
2. 回归保护：在默认方言下全量现有 analyzer/planner 测试必须不变（policy=LEGACY）。
3. 权限测试：不敏感名访问需按规范名鉴权（用户仅有 `db.t` 权限，用 `DB.T` 能访问；无权限时被拒）。
4. 输出标签：`ResultSetMetaData`/MySQL protocol 列名断言（`select ID from t(id)` -> `id`）。
5. SQL 集成测试（test/sql/test_spark_dialect/）：对照 Spark 生成 golden（可用 Spark 3.5/4.0 本地跑同一 SQL，比较 schema+结果）；`ILIKE`、`LIKE`、`=` 敏感性用例。
6. 外部 catalog：Hive/Iceberg mock 元数据，混合大小写库表输入。
7. 并发/性能：lower 索引在 DDL 并发下的一致性；miss 路径微基准。
8. 元数据兼容：EditLog/回放不应写入规范化后的名字（仍存用户原写法），升级降级无影响。

### 风险摘要
- 内部表同名仅大小写不同 → 歧义策略必须明确（报错，精确匹配优先）。
- `TableName` 被 MV、view 定义、权限等广泛持有，规范化时点要放在 analyzer 入口之后统一一次。
- View/MV 定义存储的 SQL 在不同方言/策略下重新解析的一致性：展开 view 时用创建时的 policy 或原始精确名。
- 不要触碰 `enable_table_name_case_insensitive`（集群级、不可逆）。
