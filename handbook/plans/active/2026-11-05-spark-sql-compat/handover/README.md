# 开发接管手册（Handover）

面向接手 Spark SQL 兼容项目的工程师。设计文档（[../README.md](../README.md)）回答“做什么、为什么”，本目录回答“代码在哪里、怎么改、怎么验证”。

## 阅读顺序

| 顺序 | 文档 | 读者 | 内容 |
|---|---|---|---|
| 1 | [dev-workflow.md](dev-workflow.md) | 所有人 | 开发环境、编译、本地起集群、FE/BE/SQL 测试、CI 与 PR 规范、fork 与上游同步、**2 周上手清单** |
| 2 | [fe-query-pipeline.md](fe-query-pipeline.md) | FE 工程师 | 模块结构、SQL 从文本到分布式计划的全链路、分析器内部（名字解析、输出列名、函数解析与隐式 cast、CTE/视图展开）、优化器与 EXPLAIN/TRACE、4 个操作步骤 |
| 3 | [fe-metadata-platform.md](fe-metadata-platform.md) | FE 工程师 | GlobalStateMgr 与 HA、EditLog/镜像、Catalog 与表名解析、函数元数据、会话变量/Config/sql_mode、权限、Follower 转发、指标/Profile/审计，5 个操作步骤 |
| 4 | [be-execution-functions.md](be-execution-functions.md) | BE 工程师 | be/src 目录地图与模块边界、Pipeline 执行引擎、列存模型、表达式框架，新增标量/聚合/窗口函数的完整步骤，Java UDF JNI 链路，BE 编译与单测 |
| 5 | [implementation-map.md](implementation-map.md) | 全组 | **58 张任务单**（P0 13 / P1 24 / P2 15 / P3 6）：文件、当前代码锚点、改法、依赖、规模、风险、测试；依赖图；P0+P1 5 人 11 周排期；`SqlParser.parse` 全部调用点分类 |

所有行号基于提交 `485ef510`（fork 的基线），rebase 后会漂移，开工前用 `git grep` 复核。标 **[未验证]** 的点在开工前先做 spike。

## 读码后对设计的修订（以 implementation-map §0 为准）

以下发现修正了设计文档 01–05 中的假设，实施时 **以 implementation-map.md 为准**：

| # | 修订 | 影响的设计文档 |
|---|---|---|
| D1 | `fe-grammar` 的 ANTLR `-package`/`-lib` 是全局参数（`fe/fe-grammar/build.gradle.kts` L36-42，Maven 同理），Spark 语法要放到**新模块 `fe-grammar-spark`**，不是子目录 | 01 §5、02 §3 |
| D2 | `ExpressionAnalyzer` 自底向上、原地改写（`date_part` 先例），不存在 `replaceInParent`。分析期（L2b）改写只能把节点改成另一个函数调用；需要换根节点类型（如外包 CAST）的改写放到解析期（L2a） | 03 §4 |
| D3 | 大小写回退的接入点远多于设计所列：`PlannerMetaLocker` 在分析**之前**用原名收集要加锁的表，另有外表预解析 visitor、别名判重、递归 CTE 等。**回退统一放在 `MetadataMgr.getDb/getTable`**，否则加锁集合与分析结果不一致 | 04 §3.3 |
| D4 | `StatementBase` 在 fe-parser 模块，不能引用 fe-core 类型：用 `ParsedDialect` 枚举标记语句方言 | 01 §6 |
| D5 | Trino 的 `relationAliasCaseInsensitive` 是解析时副作用写入的会话位，内部 SQL 解析会误改它 → 改为“按语句标记 + 分析入口绑定/恢复” | 04 §3.4 |
| D6 | `unnest` 只接受数组，`explode(map)` 在解析期无法翻译 → 放到 `QueryAnalyzer.visitTableFunction` 按类型改写 | 02 §6 |
| D7 | 数组下标不新增 `SparkSubscriptExpr`，复用 Trino 先例：给 `CollectionElementExpr` 加 0 基标志，少改 6 个 visitor | 02 §6–7 |
| D8 | `SET spark.sql.x=y` 在 StarRocks 语法里无法解析（标识符不含 `.`），翻译在 `SparkAstBuilder`；warning 必须在执行期加入，映射要走 `ConnectContext.modifySystemVariable` 才会转发给 Leader | 05 §1 |
| D9 | `gen_functions.py` 的 `modules` 列表写死，新增 `SparkFunctions` 模块需改 3 处（模块列表、Makefile 的 `GEN_FUNCTIONS_OUTPUT`、cpp 末尾 include）；fn_id 号段 400000 起未被占用 | 03 §5 |
| D10 | `TRANSLATE` 语句目前只支持 trino，且会改写活的会话变量 → 修复后支持 `TRANSLATE spark ...`，作为翻译结果对拍的天然入口 | 06 |
| D11 | Hive 视图文本用用户会话的方言解析，Spark 会话下 Hive 建的视图会被误当 Spark 文本 → 需显式区分视图来源 | 05 §3 |
| D12 | StarRocks 没有临时函数概念，需新增会话级函数注册表 | 03 §6 |
| D13 | BE 已能通过 `RuntimeState::get_sql_dialect()` 拿到方言（`TQueryOptions.sql_dialect`），BE 侧行为差异无需新增 thrift 字段 | 03 §5 |
| D14 | 视图持久化还保存了用户原文 `originalViewDefineSql`，Spark 建视图时需处理，避免把 Spark 原文当 StarRocks SQL 重放 | 01 §1 P6 |
| D15 | 若干行号偏差（`Database.getTable` 实为 L416、`CatalogMgr` 精确匹配点 L257/L414 等） | 04 §2 |

其它要点（来自各手册）：
- 函数参数的隐式 cast 由优化器的 `ImplicitCastRule` 插入，不在分析器里；分析期改写拿到的是未转换的原始参数类型。
- 新增 EditLog 操作类型时，老版本 FE 遇到未知操作码会退出，需默认关闭的开关。
- `information_schema` 新系统表需要 BE 扫描代码；第一阶段可先扩展 `SHOW FUNCTIONS`。
- BE 已有 `joda::JodaFormat`（`time_functions.cpp`），`spark_date_format` 优先基于它扩展。
- Java UDF 调用线程池 `udf_thread_pool_size` 默认 1，评估 Hive UDF 桥性能时要考虑。
- 仓库自带的 `fe/AGENTS.md` 部分过时（测试工具类实际在 `fe-core/src/test`，`@ConfField` 无 `varType` 等），以手册为准。

## 工作量复核

implementation-map 按任务单累计约 355 人天（≈71 人周），低于设计估算的 90 人周（不含缓冲）。差额主要来自设计里的测试基础设施与语义项按更粗粒度估算；建议仍按设计的 ~108 人周（含 20% 缓冲）做预算，在 P0 结束后用实际速度重估。

## 实现进度（2026-11-05，已暂停）

| 部分 | 分支 | 状态 |
|---|---|---|
| Spark SQL 方言 | `feature/spark-sql-dialect` | 第一阶段（方言注册机制 + Spark 语法 + 基本查询）编译通过，单测未运行；第二阶段为 WIP 提交，整体编译未确认。交接文档见该分支的 `handbook/plans/active/2026-11-05-spark-sql-dialect-handover.md` |
| glbk UDF 对齐（Java UDF / SQL UDF / C++ 原生） | 本分支 `contrib/glbk-hive-udf-compat/`、`be/src/exprs/glbk_*` | Java 与 SQL UDF 已在集群上验证；C++ 原生函数的核心算法已验证（255 万条差分 0 差异），BE 接入层待在完整 BE 编译环境中测试，见 `contrib/glbk-hive-udf-compat/IMPLEMENTATION-LOG.md` 第 6 节 |
