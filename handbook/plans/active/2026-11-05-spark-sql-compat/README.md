# StarRocks Spark SQL 兼容（Spark Dialect + UDF 适配层 + 大小写对齐）

- Status: active
- Owner: Egeek2025
- Last Updated: 2026-11-05

## Summary

在 StarRocks（fork: `Egeek2025/starrocks`）中实现 Spark SQL 兼容模式，使绝大部分 Spark SQL **无需修改**即可运行并得到与 Spark 一致的结果：

1. **UDF/函数适配器层**：函数名称、入参、语义与 Spark/Hive 对齐（L1 别名 → L2 改写 → L3 Spark 语义原生函数 → L4 Hive UDF 桥）。
2. **Spark SQL 方言**：`SET sql_dialect='spark'`，内置 Spark 3.5 ANTLR 语法，翻译为 StarRocks AST，失败回落。
3. **大小写行为与 Spark 相同**：解析不敏感、输出保留原大小写、数据比较敏感（`CaseSensitivityPolicy`）。

默认 StarRocks 方言行为 **零变化**；无法保证一致的语义 **显式报错**，绝不静默返回不同结果。

## 文档索引

| 文档 | 内容 |
|---|---|
| [01-architecture.md](01-architecture.md) | 设计原则、分层架构图、端到端时序、组件职责、**代码组织**、类图、部署兼容 |
| [02-spark-dialect-parser.md](02-spark-dialect-parser.md) | 方案对比与选型、语法引入、解析/回落流程、语句覆盖矩阵、表达式翻译规则、AstBuilder 骨架 |
| [03-function-adapter.md](03-function-adapter.md) | 函数差距统计、19 个静默错误陷阱、四层适配架构、日期模式翻译、BE 新函数、Hive UDF 桥时序 |
| [04-case-sensitivity.md](04-case-sensitivity.md) | Spark vs StarRocks 大小写差距表、`CaseSensitivityPolicy`、元数据回退时序、输出列名规则 |
| [05-semantics-session-catalog.md](05-semantics-session-catalog.md) | `SET spark.*` 映射、新会话变量、catalog 映射、临时视图、类型/ANSI/DML 语义 |
| [06-testing-strategy.md](06-testing-strategy.md) | 测试金字塔、Spark golden、差分测试、CI 分层、量化验收指标 |
| [07-roadmap-cost-benefit.md](07-roadmap-cost-benefit.md) | 方案对比、甘特图路线、人力与成本、收益模型、风险、决策建议 |
| [func_mapping.csv](func_mapping.csv) | 479 个 Spark 函数逐一分类（A/B/C/D/E）、SR 对应、差异、策略、难度 |
| [research/](research/) | 5 份原始调研报告（parser、函数注册/UDF、大小写、函数差距、先行实践与语义） |
| [spark-compat-design.html](spark-compat-design.html) | 全部设计与接管手册合成的单页网页（含可筛选函数映射表），由 `site/build_page.py` 生成 |
| [contrib/glbk-hive-udf-compat](../../../../contrib/glbk-hive-udf-compat/README.md) | 第一个落地的 UDF 对齐样例：业务方 glbk Hive 日期 UDF（7 个）移植为 StarRocks Java UDF，同名同参，含与原 Hive 类的差分测试；Java UDF / SQL UDF 两种实现与性能对比，实现路径与经验见其 IMPLEMENTATION-LOG.md |
| [handover/](handover/README.md) | **开发接管手册**：开发流程、FE 查询链路、FE 元数据与平台、BE 执行与函数、58 张代码级任务单（含读码后对设计的 15 处修订） |

## 关键结论

- **现成先例**：StarRocks 已有 Trino 方言（`connector/parser/trino/`，2.5k LOC，`sql_dialect=trino` + `enable_dialect_downgrade` 回落 + `relationAliasCaseInsensitive`），Spark 方言按同构方式实现，风险可控。
- **函数差距**（Spark 3.5，430 个）：A 兼容 142 / **B 同名异义 89** / C 别名 66 / D 缺失 128 / E 不适用 5。最大风险是 B 类静默错误（`stddev` 样本 vs 总体、`||`、`date_add` 返回类型、Java 日期模式、`arr[0]` 下标、`size(NULL)`、`split` 正则、`length` 字节数、`hash` 种子…）。
- **大小写**：StarRocks 列名/struct/函数名已不敏感；缺口在库/表/catalog/别名/CTE 与输出列名。`enable_table_name_case_insensitive` 是集群级不可逆开关，不可复用 → 新增会话级 policy（精确优先、不敏感回退、歧义报错、规范名鉴权）。
- **推荐方案**：FE 内置 Spark grammar（vendored Spark 3.5，ANTLR 4.9.3 与 SR 一致）+ 四层函数适配 + 语义策略；sqlglot 仅作离线 oracle。
- **成本/周期**：约 90 人周（含缓冲 ~108）；5 人团队约 5.5 个月 GA；MVP（TPC-DS 90/99）约 3 个月。作业规模 ≥ ~2500 个时迁移节省即可覆盖投入。

## Acceptance Criteria

- `SET sql_dialect='spark'` 下 Spark 版 TPC-DS 99 条查询无修改运行且结果一致。
- Spark 官方 `sql-tests` golden：解析通过率 ≥95%，结果一致率 ≥93%。
- Spark 3.5 内置函数可用率（A+B+C 已适配 + D 已实现）≥92%。
- 已知 WRONG_RESULT 为 0（无法修复的改为显式报错）。
- 默认 StarRocks 方言的现有 FE/BE/SQL 测试零回归。

## Decision Log

- 2026-11-05: 选择“FE 内置 Spark grammar + AST 翻译”（方案 A），否决扩展 `StarRocks.g4`（默认方言风险与上游冲突）与外部 sqlglot 服务（无类型语义）。
- 2026-11-05: Spark 语义函数使用 `spark_` 前缀 builtin 作为改写目标，不覆盖同名 SR 函数。
- 2026-11-05: `spark_case_sensitive=true` 不实现（报不支持）；Collation 推迟到 P4。
- 2026-11-05: 语义基线 Spark 3.5（ANSI 默认关），`spark_version=4.0` 作为后续选项。
- 2026-11-05: 读码后修订（详见 [handover/README.md](handover/README.md) 与 implementation-map §0）：Spark 语法放新模块 `fe-grammar-spark`；大小写回退统一在 `MetadataMgr` 单点实现；数组下标复用 `CollectionElementExpr` 加 0 基标志，不新增节点；`explode(map)` 在分析期按类型改写；语句方言用 fe-parser 中的 `ParsedDialect` 枚举标记。

## 调研说明

本设计由 5 个并行调研 agent（源码 + 互联网）产出，主 agent 对关键结论做了源码抽查（`SqlParser.parse` L80-91、`Field.matchesPrefix` L218-233、`SelectAnalyzer` L315、ANTLR 版本）。标注 [待验证] 的语义项以及 Spark 4.0/4.1 函数清单置信度较低，需在 P0 基线测量中确认。
- 2026-11-05: glbk UDF 对齐样例完成（Java UDF 正式版 + 对账版；4.1+ 的 SQL UDF 版为实验）。C++ 原生函数推迟，路径记录在 `contrib/glbk-hive-udf-compat/IMPLEMENTATION-LOG.md` 第 6 节。
