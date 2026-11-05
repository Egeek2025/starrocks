# Spark SQL 方言：实现交接（暂停中）

- Status: active（暂停，未完成）
- Owner: Egeek2025
- Last Updated: 2026-11-05

## Summary

在 StarRocks FE 中实现 `SET sql_dialect='spark'`，让大部分 Spark SQL 无需修改即可运行。设计文档在分支 `docs/spark-sql-compat-design` 的 `handbook/plans/active/2026-11-05-spark-sql-compat/`（README、02 解析器设计、03 函数适配、04 大小写、05 语义、`handover/implementation-map.md` 任务单及其第 0 节的设计修订）。本分支 `feature/spark-sql-dialect` 基于 fork 的 `main`。

工作按“先写完代码、提交，再逐项测试”的方式进行，在第二阶段写代码途中按要求暂停。**本分支最后一次提交是未完成状态（WIP）：没有跑过任何单测，第二阶段的代码也没有确认能整体编译通过。**

## Acceptance Criteria

- 第一阶段单测、第二阶段单测全部通过。
- 现有 Trino 测试和 `ParserTest` 零回归。
- 默认 StarRocks 方言的行为不变。

## 当前进度

| 部分 | 提交 | 状态 |
|---|---|---|
| 第一阶段：方言注册机制 + Spark 语法 + 基本查询 | `455f8ade` | 代码完成；main 和 test 源码用 Maven 编译通过；checkstyle 0 违规；**单测未运行** |
| 第二阶段 B1：查询语法扩展 | WIP 提交 | 代码完成；agent 用 javac 单独编译自己的文件无误；**整体编译未确认**；单测未运行 |
| 第二阶段 B2：语句、会话、命令 | WIP 提交 | **写到一半时被暂停**，完成程度未核对 |
| 第二阶段 B3：函数改写 + 表名大小写 | WIP 提交 | **写到一半时被暂停**；暂停前它的 `function/` 包有约 200 个编译错误 |

### 第一阶段（`455f8ade`）内容

- **方言注册机制**：`sql/parser/dialect/` 下有 `SqlDialect`、`SqlDialectRegistry`、`StarRocksDialect`、`DialectDowngradePolicy`。`SqlParser.parse` 按 `sql_dialect` 分派，名字不区分大小写，未知方言按 StarRocks 处理。Trino 的逻辑原样搬到了 `connector/parser/trino/TrinoDialect`。
- **新模块 `fe/fe-grammar-spark`**：
  - 引入 Spark 3.5.10 的 `SqlBaseLexer.g4`/`SqlBaseParser.g4`，改名后生成到 `com.starrocks.sql.parser.spark.gen` 包；保留 Apache 许可，并在 `NOTICE.txt` 登记。
  - 已在 Maven 和 Gradle 两边注册。注意 fork 的 `main` 上 Gradle 构建本来就因缺少 fluss 依赖而失败，所以构建请用 Maven。
- **fe-parser 改动**：
  - 新增 `ParsedDialect` 枚举，并在 `StatementBase` 上加了取值和设置的方法，用来标记语句来自哪种方言。
  - `CollectionElementExpr` 新增 0 起始下标标志。
- **`connector/parser/spark/`**：
  - 解析器：`SparkParser` 先用 SLL 模式解析，失败再用 LL 模式，大小写不敏感；按顶层分号切分多条语句。
  - 语法树转换：`SparkAstBuilder` 通过反射把各条语法规则分派给 `SparkRelationBuilder`、`SparkExprBuilder` 等 builder 类。新增规则只需写在自己的 builder 里，不用改 `SparkAstBuilder`。
  - 不支持的语法抛 `SparkUnsupportedException`，由回退策略交给 StarRocks 解析器处理。
- **分析器改动**：`Analyzer.analyze` 对 Spark 语句开启表别名不区分大小写；`ExpressionAnalyzer` 对数组下标做 0 起始到 1 起始的转换。
- **覆盖的语法**：
  - 查询：SELECT 全套子句、CTE、各类 JOIN（含 LEFT SEMI/ANTI）、集合运算；
  - 表达式：常见表达式和字面量、Spark 类型名的 CAST、窗口函数、子查询、lambda；
  - EXPLAIN。

### 第二阶段（WIP 提交）内容

- **B1 查询语法**，由 agent 报告完成：
  - 文件：`SparkRelationBuilder`、`SparkExprBuilder` 的扩展，新增 `SparkGenerators`；另外对 `TableFunctionRelation` 和 `QueryAnalyzer.visitTableFunction` 做了小改动。
  - 功能：
    - `LATERAL VIEW [OUTER] explode/posexplode/json_tuple/stack`；SELECT 列表里的生成器函数；对 map 做 explode 时在分析阶段按类型展开。
    - 命名窗口；`SORT BY`/`CLUSTER BY`/`DISTRIBUTE BY`。
    - `INTERVAL` 加减运算；`TRY_CAST`。
    - `LIKE ESCAPE`、`LIKE ANY/ALL`。
    - `struct`/`array`/`map` 构造。
    - 列别名列表；`TABLESAMPLE (n ROWS)`；`PIVOT`。
    - `BROADCAST`/`SHUFFLE_HASH` 提示。
  - 测试：`SparkQueryExtTest`。
- **B2 语句与会话**，未完成，已写出的文件：
  - `SparkStatementBuilder`（已在 `SparkAstBuilder` 中注册）、`SparkSetStmt`、`SparkCatalogNames`。
  - `qe/SparkConfMapper`。
  - `SessionVariable` 新增 `spark_*` 变量；改动了 `SetExecutor`、`ConnectContext`、`InsertAnalyzer`、`StatementPlanner`。
  - 让内部 SQL 不被方言接管：一批 `SqlParser.parse` 调用点改成只用 StarRocks 方言，涉及 `ConnectorView`、`HiveView`、`MvUtils`、`SPMPlanner`、`DeleteMgr` 等约 15 个文件。
  - 测试：`SparkStatementDdlTest`、`SparkStatementInsertTest`、`SparkStatementDialectGuardTest`、`SparkCommandSetTest`、`SparkCommandMiscTest`、`SparkStatementTestUtils`。
  - 还有一个新目录 `tools/spark-compat/`。
- **B3 函数与大小写**，未完成，已写出的文件：
  - `function/` 包：`Spark2SRFunctionCallTransformer`、`SparkFunctionCatalog`、`SparkFunctionRewriter`、`JavaDateTimePatternTranslator`、`SparkStringFunctions`、`SparkDateTimeFunctions`、`SparkCollectionFunctions`、`SparkMathAggFunctions`、`SparkRegexSupport`、`SparkExprs`。
  - 大小写：`SparkCaseInsensitiveNames`；`MetadataMgr` 加了不区分大小写的回退查找；`PlannerMetaLocker`、`QueryAnalyzer` 加了规范名回写；`FunctionCallExpr` 有改动。
  - 测试类还没写。

## 接手步骤（建议顺序）

1. **先恢复编译**：

   ```bash
   cd fe && mvn -B -o -Dcheckstyle.skip=true -DskipTests -pl fe-core test-compile
   ```

   - 去掉 `-o` 第一次会联网下载依赖；如果 Maven 中央仓库返回 429，配置 `~/.m2/settings.xml` 镜像，见下方“构建环境”。
   - 预计错误集中在 B3 的 `function/` 包，以及 B2 尚未完成的部分。
   - 如果一时修不好，可以先把未完成的类改成直接抛 `SparkUnsupportedException`，保证 B1 和第一阶段可用。
2. **按顺序跑单测**，每次只跑一个：

   ```bash
   cd fe && mvn -B -o -pl fe-core surefire:test -Dtest=SparkParserTest -DfailIfNoTests=false
   ```

   也可以用仓库根目录的 `./run-fe-ut.sh --test <完整类名>`。顺序：
   1. `SparkParserTest`：不需要集群，最快。
   2. `SparkParserBasicTest`。
   3. `SparkDowngradeTest`。
   4. Trino 回归，必须原样通过：`TrinoDialectDowngradeTest`、`TrinoQueryTest`、`TrinoParserNotSupportTest`、`TrinoFunctionTransformTest`、`TrinoCtasTest`、`TrinoInsertTest`、`TrinoDropTableTest`、`TrinoTPCDSTest`、`TrinoTPCHTest`，以及 `com.starrocks.sql.parser.ParserTest`。
   5. `SparkQueryExtTest`，然后是 B2 的各个测试类。
   6. 补 B3 的测试（`SparkFunction*Test`、`SparkCaseSensitivity*Test`）。
3. **预计容易失败的地方**：测试里断言执行计划文本的写法是 agent 凭经验写的，没有验证过，失败时以实际输出为准修正期望值。agent 点名的有：
   - `REPEAT`（rollup/cube）、`LEFT SEMI/ANTI JOIN`、下标 `c2[1]`、`offset: 5`、窗口 frame 文本、`VARCHAR(65533)`、`days_add`、`limit: 5`、`array_generate`；
   - 别名大小写、`ilike` 改写成 `lower`、`count_if`/`sum_if`；
   - 回退测试里的错误信息文本。
4. **补完 B2/B3**：参照设计文档 02 第 5 节的语句矩阵、05 的会话和 catalog 设计、03 第 1.1 节的 19 个会悄悄算错的函数，以及 `func_mapping.csv`。

## 已知缺口与注意事项

- **第一阶段已知不支持**：
  - `TRANSFORM`、表值函数、时间旅行语法；
  - `inline`、`UNPIVOT`、`NATURAL JOIN`；
  - `OVER (w ORDER BY ...)`；
  - 按百分比的 `TABLESAMPLE`；
  - 单独出现的 `INTERVAL` 值。
- **表别名的大小写开关**设在 `Analyzer.analyze` 里；回退到 StarRocks 解析器的语句保持原有行为。
- **`SparkParser`** 没有设 token 和表达式子节点个数的上限；并发解析时的 DFA 缓存逻辑仿照 `SqlParser`，没有测过并发。
- **共用文件里混有多个 agent 的改动**（`QueryAnalyzer`、`ExpressionAnalyzer`、`SparkExprBuilder` 等），review 时需要按功能逐块核对。
- **观测性**：沿用已有的 `ProfileManager.SQL_DIALECT`，没有新增指标。

## 构建环境（本次在云端沙箱中使用的做法）

- **thrift 编译器**：FE 需要 0.24.0 版本。本次从 `https://dlcdn.apache.org/thrift/0.24.0/thrift-0.24.0.tar.gz` 下载源码，用 `cmake -DBUILD_LIBRARIES=OFF` 只编译了编译器，放在 `/usr/local/bin/thrift`。
- **Maven 中央仓库限流（429）**：`~/.m2/settings.xml` 加镜像 `https://maven-central.storage-download.googleapis.com/maven2/`（mirrorOf central）。
- **Gradle**：fork 的 `main` 缺少 fluss 依赖，Gradle 构建不可用，请使用 Maven。

  ```bash
  cd fe && mvn -B -DskipTests -Dcheckstyle.skip=true -pl fe-core -am install
  ```

  完整编译约 4.5 分钟。
- **Spark 语法来源**：`apache/spark` 仓库的 `branch-3.5`，路径 `sql/api/src/main/antlr4/org/apache/spark/sql/catalyst/parser/`。

## Decision Log

- 2026-11-05: 采用“引入 Spark 语法 + 语法树转换 + 失败时退回 StarRocks 解析器”（设计方案 A），Spark 语法放在独立模块 `fe-grammar-spark`（实施地图 D1）。
- 2026-11-05: 按用户要求改为“先写完代码、提交，再逐项测试”；第二阶段写代码途中按要求暂停，未完成的状态以 WIP 提交保存。
