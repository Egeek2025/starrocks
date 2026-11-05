# glbk UDF 对齐：实现路径与经验记录

本文记录把业务方 7 个 Hive 日期 UDF（`glbk-hive-udf-1.0.0.jar`）迁到 StarRocks 的全过程：做了什么、为什么这样做、踩过的坑、实测数据，以及下一步（C++ 原生函数）的计划。它也是 Spark SQL 兼容项目里“UDF 适配层”的第一个落地样例，经验可以直接复用到后续其它 UDF。

## 1. 结论速览

| 方案 | 状态 | 适用版本 | 与 Hive 一致性 | 性能（相对手写内置表达式） |
|---|---|---|---|---|
| 正式版 `gl_*`（Java UDF，修复 bug） | 已完成，生产可用 | 3.x / 4.0 / 4.1 | 按约定修复 8 类 bug，其余一致 | 1.3 到 3.5 倍（优化后） |
| 对账版 `gl_*_hive`（Java UDF，逐条复刻） | 已完成，迁移期使用 | 同上 | 与原 jar 逐条一致 | 与正式版相近 |
| SQL UDF 版 `gl_*`（内置函数组合 + Java 兜底） | 实验，已验证 | 4.1+ | 与正式版逐条一致 | 1.2 到 2.7 倍；`gl_date_format`/`gl_date_delta` 比 Java 快 1.2 到 2.5 倍 |
| C++ 原生内置函数 | 代码已完成；核心算法已验证，BE 接入待在完整 BE 编译环境中测试（见第 6 节） | 需改 fork 并重新编译 BE | 核心与 Java 正式版逐条一致（255 万条差分 0 差异） | 核心单次调用 20 到 185ns，无 JNI |

## 2. 实现路径（按时间顺序）

1. **逐条复刻移植**：每个 Hive UDF 写一个 StarRocks Java UDF 类，`evaluate(String... args)` 对应原来的 `evaluate(Text... args)`，`DateUtils` 原样复制。用测试桩（`Text`、`UDF`、`Description`、pdk 注解）把**原始 Hive 源码原样编译**作为参照，做约 85 万次差分比对。
2. **拷问对齐口径**（两轮，结论见第 3 节）：用户选择“修 bug”，并额外保留一套逐条复刻的对账版。
3. **分成两套函数**：原样复刻的类移到 `hive` 子包，注册为 `gl_*_hive`；正式版用 `java.time` 按约定的规格重写。由 3 个 agent 并行完成：对账版固定 Locale、正式版实现、端到端环境。
4. **对账版固定 Locale**：发现周相关结果依赖 JVM 默认 Locale 和 JDK 版本（见 4.1），把 `Calendar`/`SimpleDateFormat` 固定为 Locale.US。
5. **正式版差分测试改为“预期差异”**：约 270 万次比对，每一处差异都必须能归入已同意的修复类别，归不进去就失败；某个类别一次都没出现也失败。
6. **真实集群端到端**：在容器里起 StarRocks 4.0.17 单机集群，`register.sql` 一次注册 16 个函数，正式版和对账版各 214 条样本全部通过。
7. **性能基准**：Java UDF 与等价的内置函数表达式相比慢 1.2 到 5.8 倍。
8. **Java 内部优化**：常用格式手写拼接、offsets 解析结果缓存、自定义格式按线程缓存。`gl_date_delta` 快了约 37%，整体差距缩小到 1.3 到 3.5 倍。
9. **SQL UDF 版**（4.1+）：用 SQL UDF 展开成内置函数组合，复杂情况退回 Java。经过 4 轮表达式调优，`gl_date_format`、`gl_date_delta` 明显快于 Java 版，其余函数持平。

## 3. 关键决策（与用户拷问后确定）

| 问题 | 决定 |
|---|---|
| 对齐口径 | 修 bug：季度、跨年周、24 小时制、按本地日历日计算天差、严格解析、严格设置值、未知 interval 报错、去掉 stderr 输出；只认 4 种输入格式 |
| 非法输入 | 数据值非法返回 NULL；参数写错报错 |
| 对账 | 另外注册 `gl_*_hive`（逐条复刻），迁移完成后删除 |
| Locale | 两套都固定为周日开头、Locale.US |
| 时区 | BE 的 `JAVA_OPTS` 加 `-Duser.timezone=Asia/Shanghai` |
| 落地形态 | Java UDF；L4 Hive UDF 桥不提前做 |
| 脚本里的 `create temporary function` | 不处理（迁移时由人工删除） |
| 验收 | 生成样本集 + 真实集群端到端（由我准备） |

## 4. 技术经验

### 4.1 Hive UDF 本身

- **结果依赖 JVM 的 Locale 和 JDK 版本**：JDK 9+ 默认用 CLDR 区域数据，zh_CN 的周首日变成周一；JDK 8 时代是周日。同一份代码在不同 BE 上，`gl_date_first(...,'week')` 可能得到 `20190421`，比同一周的“最后一天”还晚。迁移老 UDF 时，Locale 和 JDK 版本必须作为“输入”一起固定下来。
- **结果依赖 JVM 时区**：Hive UDF 用的是 JVM 默认时区，不是 SQL 会话时区，StarRocks Java UDF 也一样。BE 的启动脚本不设时区，会继承系统的 `TZ`。
- **原 README 和注释里的示例值有错**（月差 `-12` 实际是 `-13`；`'31'` 实际是 `-31`）。期望值要以实际运行原代码的结果为准。
- **Hive 里 `Text.set(null)` 会抛 NPE 让查询失败**。看起来“返回 NULL”的分支，在 Hive 上其实是报错，移植时要复现这一点。

### 4.2 StarRocks Java UDF

- 每个类只能有一个 `evaluate` 方法；变参写法是声明 `(string, ...)`、方法 `evaluate(String... args)`，至少要有一个固定参数，所以零参数要单独注册一个类（`gl_now()`）。
- jar 的 class 版本不能高于 17，FE 会拒绝 JDK 21 默认编译的 jar。
- 执行全部在 BE，通过 JNI 调用 Java。FE 不会对常量参数做折叠，异常会以 `JNI Exception` 的形式让整条查询失败。
- **`WHERE` 里遇到有 NULL 行的可空 STRING 列**：BE 会在列字典上对谓词求值，字典里用空串占位 NULL。UDF 遇到空串如果抛错，整条查询就会失败，而 Hive 上没有这个问题。规避办法：UDF 对空串返回 NULL，或者 `SET enable_column_expr_predicate=false`。
- `udf_thread_pool_size` 默认 1。对轻量函数来说，每行的 JNI 和数据拷贝开销已经是主要成本，Java 内部再优化，收益很快就到头了。

### 4.3 StarRocks SQL UDF（4.1+）

- SQL UDF 只能声明固定个数的参数，没有变参；参数个数不对的调用，只能用多个固定个数的签名兜底。
- **不要让同名的 SQL UDF 和 Java 变参版共存**：FE 按注册顺序选第一个匹配的版本，结果取决于谁先注册，不可靠。
- 嵌套的 SQL UDF 会被内联；公共子表达式会复用（执行计划里能看到 `common expressions`）。
- **`CASE`/`if` 会在所有行上计算所有分支**，不会因为整批数据都走同一分支就跳过另一个。“快路径 + 慢路径”的写法反而更慢。
- 函数成本差异很大（1000 万行）：
  - 3 次 `replace` 0.48s，`translate` 只要 0.10s；
  - 判断全是数字，用 `translate(n,'0123456789','')=''` 比 `regexp` 便宜；
  - `str_to_date` 是严格解析（2 月 29 日、13 月、24 点都返回 NULL）；`cast(... as datetime)` 会宽松接受 `2019-4-7` 和 24 点；
  - 一次 `str_to_date`、格式串按长度选，比写两个分支各调一次更快。
- **FE 常量折叠只覆盖 `ScalarOperatorFunctions` 里的函数**（`replace`、`substr`、`date_format`、`str_to_date` 等有；`translate`、`length`、`regexp` 没有）。表达式里只要用到不能折叠的函数，常量调用就无法提前算出结果，也就不能触发分区裁剪。
- 路由条件要用 FE 能折叠的函数来写（比如用 `regexp_replace(...)=''` 而不是 `regexp`），常量参数时才能在编译期去掉不需要的分支。
- `SHOW FUNCTIONS` 显示的签名是不带长度的 `VARCHAR`，但 `DROP FUNCTION` 不接受这种写法，要改成 `STRING`。

### 4.4 测试方法

- **用原始代码当参照，而不是用文档当参照**：测试桩让原始 Hive 类不依赖 hive-exec 就能编译运行，这是整个对齐工作可信的基础。
- **“预期差异”差分**：每一处差异都必须能解释到已同意的类别，同时要求每个类别都至少出现一次，防止测试悄悄失效。
- **变异测试**：故意改回一个 bug，确认测试会失败，再恢复，证明测试真的有用。
- **基准测试**先校验 UDF 和内置写法逐行结果一致；排除低基数字典优化的影响；对比低基数和高基数两种数据；每条查询跑 5 次取中位数；单独测只扫描不计算的基线。

### 4.5 环境（本沙箱）

- **dockerd 和集群跟着命令一起结束**：这里每条命令结束都会回收它启动的进程，要让集群跨命令保持运行，得放在一直存活的后台任务里。
- **Docker Hub 匿名拉取被限流（429）**：改用 `mirror.gcr.io/starrocks/allin1-ubuntu:<tag>`。
- **容器里的文件描述符上限只有 20000**：BE 启动前要先在 `be.conf` 里设 `min_file_descriptor_number = 1024`。
- **就绪检查要让 BE 真正执行一条查询**：查 `information_schema` 只能说明 FE 就绪；BE 刚启动时会报“Have not get FE Master heartbeat yet”，要多等一会儿并重试。

### 4.6 协作过程

- **多 agent 并行要先划清文件边界**：先手工把要改动的类挪开、提交一个基线，再让各 agent 只改自己负责的路径，避免互相冲突。
- **agent 的结论要抽查**：例如“东八区季内第几天会少 1”这条说法，实测并不成立；“全部样本通过”那次，其实因为注册顺序，跑的是 Java 版而不是 SQL UDF 版。
- **说明文档里的每个示例值都用代码实测**，不要凭推断写。

## 5. 产物位置

| 内容 | 路径 |
|---|---|
| 正式版 / 对账版代码 | `src/main/java/com/glbk/starrocks/udf/`、`.../udf/hive/`、`.../udf/core/` |
| 注册脚本（Java 版） | `sql/register.sql` |
| 注册脚本（SQL UDF 版，4.1+） | `sql/register_sqludf.sql`、`e2e/register_sqludf.py` |
| 样本与差异分类 | `samples/samples.csv`、`samples/README.md` |
| 业务方变更说明 | `CHANGES.md` |
| 端到端与基准 | `e2e/`（`run_e2e.sh`、`bench.py`、`BENCHMARK.md`、`bench_result_*.md`） |

## 6. C++ 原生内置函数

**当前状态（代码已提交）**：
- **核心算法** `be/src/exprs/glbk_date_core.{h,cpp}`：只依赖 C++ 标准库。`contrib/glbk-hive-udf-compat/native/` 是差分测试套件：用 Java 正式版生成参照样本，C++ 版逐条比对。255.7 万条 0 差异，ASan/UBSan 下 22.7 万条 0 差异。
- **StarRocks 接入层** `be/src/exprs/glbk_functions.{h,cpp}`：
  - 内置函数 fn_id 400001 到 400006，每个函数注册为变参 `(VARCHAR, ...)`，参数个数的规则和 Java 版一致。
  - 常量的 format 和 offsets 在函数初始化时预先编译。
  - 参数写错时，返回 `Status::InvalidArgument`，查询失败。
- **测试代码已写好，尚未在真实 BE 上运行**：BE 单测 `be/test/exprs/glbk_functions_test.cpp`、SQL 集成测试 `test/sql/test_glbk_functions/`。已做的检查：模块边界检查通过、代码生成脚本接受新函数、用替身头文件编译的单测通过。
- **待在开发机或 CI 上执行**：

  ```bash
  ./run-be-ut.sh --build-target expr_test --module expr_test --without-java-ext --gtest_filter 'GlbkFunctionsTest*'
  ```

  ```bash
  cd test && python3 run.py -d sql/test_glbk_functions/R/test_glbk_functions -v
  ```
- **上线注意**：内置函数优先于 UDF，所以同名的 `gl_*` UDF 要先删掉；`gl_*_hive` 和 `gl_now`（Java）保留。

### 原计划（保留作参考）

目标：把 `gl_*` 实现成 StarRocks 内置函数，向量化执行，常量参数可以由 FE 折叠，性能接近手写内置表达式，语义与正式版逐条一致。实现路径（行号以 fork 基线为准，开工前用 `git grep` 复核；细节见 `handbook/plans/active/2026-11-05-spark-sql-compat/handover/be-execution-functions.md`）：

1. **登记函数**：在 `gensrc/script/functions.py` 的未占用号段（400000 到 409999）里登记每个函数的每种参数个数，fn_id 不能重复。命名有两种选择：
   - 直接用 `gl_*`：要先删掉同名的 Java/SQL UDF，因为内置函数优先于 UDF；
   - 用带前缀的名字（如 `glbk_date_delta`），再通过 SQL UDF 或 Spark 方言的函数改写层映射过去。
2. **BE 实现**：在 `be/src/exprs/` 下新增一个模块（例如 `glbk_functions.{h,cpp}`），直接复用 `GlDates` 的算法（手写解析、手写格式化、offsets 解析）。常量参数在 `prepare` 阶段只解析一次，存进 `FunctionContext` 的状态里。
3. **接上代码生成**：
   - `gen_functions.py` 的 `modules` 列表是写死的，要把新模块加进去；
   - `gensrc/script/Makefile` 的生成输出列表也要加；
   - 新 `.cpp` 文件末尾要 `#include "gen_cpp/opcode/<Module>.inc"`；
   - `be/src/exprs/CMakeLists.txt` 显式登记源文件。
4. **FE**：通常不用改；如果要支持常量折叠，在 `ScalarOperatorFunctions` 里补上对应实现。另外顺手让 `translate`、`length` 也能在 FE 折叠，SQL UDF 版的常量调用就能触发分区裁剪。
5. **测试**：
   - BE 单测（`be/test/exprs/`）；
   - 把 `samples/samples.csv` 转成 SQL 集成测试（`test/sql/test_glbk_udf/`）；
   - 端到端和基准直接复用 `e2e/run_e2e.sh`、`e2e/bench.py`，基准里增加一个“原生函数”对照列。
6. **兼容与发布**：新 fn_id 在旧版 BE 上不存在，需要先升级完全部 BE，再启用这些函数。遵守 `be/AGENTS.md` 的模块边界：`exprs` 模块不能依赖 `exec`、`storage`。

工作量估计：
- 7 个函数的 C++ 实现和单测约 1.5 到 2.5 人周；
- 接入代码生成、集成测试和基准约 1 人周。

需要能编译 BE 的环境：本沙箱 4 核，完整编译 BE 很慢，建议在团队的开发机或 CI 上做。
