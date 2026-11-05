# glbk UDF 端到端测试（真实 StarRocks 单机集群）

在容器里起一个单节点 StarRocks（FE+BE，官方 all-in-one 镜像），把 `target/` 里的 jar 注册成全局 UDF，再用 CSV 里的 `sql` / 期望值逐条验证。

已验证环境：`starrocks/allin1-ubuntu:4.0.17`（`4.0.17-818b93c`），BE 的 JVM 是 OpenJDK 17.0.20.1。

## 目录

| 文件 | 作用 |
|---|---|
| `cluster.sh` | 启动/停止/查看集群（必要时先拉起 dockerd、拉镜像、改 BE 配置） |
| `run_e2e.sh` | 构建 jar、起 HTTP 服务、渲染并执行 `sql/register.sql`、跑 CSV 检查 |
| `e2e_runner.py` | 实际的注册与检查逻辑（pymysql，不需要 mysql 客户端） |
| `smoke.csv` + `smoke_setup.sql` | 冒烟用例（README 示例、`gl_now()`、NULL、多行表） |
| `known_issues.csv` | 复现下文“已知坑 1”的用例（需 `--set enable_column_expr_predicate=false`） |

## 一、启动集群

```bash
cd contrib/glbk-hive-udf-compat/e2e
./cluster.sh start     # 幂等；首次会拉 ~2GB 镜像
./cluster.sh status
./cluster.sh stop      # 停止（数据在 docker volume 里保留）；再次 start 即恢复
./cluster.sh rm        # 删容器和数据卷，彻底重来
./cluster.sh mysql -e 'select current_version()'   # 用容器里自带的 mysql 客户端
./cluster.sh logs      # be.out / be.INFO / fe.log 尾部
```

连接：`mysql -h127.0.0.1 -P9030 -uroot`（无密码）；FE HTTP `8030`，BE HTTP `8040`。容器用 `--network host`，所以 FE/BE 访问宿主机的 `127.0.0.1` 就是宿主机本身。

### `cluster.sh start` 自动完成的手工步骤（在本沙箱里都是必须的）

1. **启动 docker 守护进程**：沙箱里 `dockerd` 默认没起。脚本执行
   `nohup dockerd --iptables=false --bridge=none &`（没有 iptables/网桥权限，所以容器必须 `--network host`）。
   拉镜像走代理：dockerd 继承当前 shell 的 `HTTPS_PROXY`（`http://127.0.0.1:38521`）和 `SSL_CERT_FILE=/root/.ccr/ca-bundle.crt`，所以要在带这些环境变量的 shell 里运行；日志在 `.run/dockerd.log`。
2. **BE 文件描述符检查**：沙箱 `ulimit -Hn` 硬上限是 20000，`docker run --ulimit nofile=655350` 会报 `error setting rlimit type 7: operation not permitted`；而 BE 默认要求 `ulimit -n >= 60000`，否则 `file descriptors limit is too small`，supervisor 里的 `beservice` 变成 FATAL。解决：在容器内 `be.conf` 追加 `min_file_descriptor_number = 1024`，然后 `supervisorctl restart beservice`。
3. **BE JVM 时区**（README 决策）：在容器内 `be.conf` 的 `JAVA_OPTS` 前面加 `-Duser.timezone=Asia/Shanghai`（脚本变量 `BE_TZ`）。要同时固定 Locale，可设 `BE_LOCALE_OPTS="-Duser.language=en -Duser.country=US"` 后在**新容器**上启动（`./cluster.sh rm` 后重来）。
4. **`enable_udf`**：4.0 里它不是可变配置（`ADMIN SET FRONTEND CONFIG` 会报 `not mutable`），但 all-in-one 镜像的 `fe.conf` 已经带 `enable_udf = true`；脚本只做校验，不满足时报错提示去改 `fe.conf` 并重启 FE。

BE JVM 实测的默认值（用探针 UDF 在 BE 内读取）：

```
Locale = en_US   TimeZone = Asia/Shanghai   Calendar.getFirstDayOfWeek() = 1 (周日)   java.version = 17.0.20.1   file.encoding = UTF-8
```

即容器没有设置 `LANG`，JVM 回落到 `en_US`；所以 `gl_date_first('20190417','week')` = `20190414`（与原 README 一致）。FE 会话时区是 `Etc/UTC`，与 BE JVM 的时区不同——UDF 用的是 BE JVM 的时区。

## 二、运行测试

```bash
./run_e2e.sh smoke.csv                 # 冒烟（自动用 smoke_setup.sql 建表，期望列 expected）
./run_e2e.sh                           # 默认 ../samples/samples.csv，期望列 new_result
./run_e2e.sh ../samples/samples.csv --expected-col hive_result
./run_e2e.sh x.csv --register-sql /path/other_register.sql --setup-sql setup.sql --skip-blank-expected -v
./run_e2e.sh x.csv --no-build          # 不重新 mvn，直接用 target/ 里最新的 jar（或 --jar FILE）
./run_e2e.sh x.csv --no-register       # 只跑检查，不重新注册
```

退出码：全部通过为 0，有任何不一致为 1，参数/环境错误为 2。

`run_e2e.sh` 做的事：

1. `mvn -B -q package -DskipTests`（`--no-build` 跳过），算 md5；
2. 把 jar 复制到 `.run/serve/<name>-<md5前8位>.jar`（内容变了 URL 就变，避免 FE/BE 缓存旧 jar），用 `python3 -m http.server` 在 `18088`（被占用则递增）上提供，**脚本结束后服务继续运行**（BE 重启后可能需要重新下载 jar）。URL 里的主机默认 `127.0.0.1`，若 StarRocks 不在 host 网络里，设 `JAR_HOST=<容器可达的宿主机地址>`；
3. 渲染 `sql/register.sql`：替换 `${JAR_URL}`，并给每个 `"file"` 后追加 `"md5" = "..."`；先对 register.sql 里每个 `CREATE GLOBAL FUNCTION name(args)` 执行 `DROP GLOBAL FUNCTION IF EXISTS name(args)`，所以重复运行没问题（register.sql 以后改名/加函数也自动适配）；
4. 执行 `--setup-sql` 指定的 SQL，再逐行执行 CSV 的 `sql` 列。

CSV 规则：列名默认 `sql` 和 `new_result`（`--sql-col`、`--expected-col` 可改）；只比较结果第一行第一列的文本；期望值 `NULL` 表示 SQL NULL，`ERROR` 表示该查询必须失败；`sql` 不以 select/with/show/values/( 开头时自动补 `SELECT `；检查连接默认库是 `glbk_e2e`（脚本自动创建，`smoke_setup.sql` 在里面建表）。

环境变量：`SR_HOST`(127.0.0.1) `SR_PORT`(9030) `SR_USER`(root) `SR_PASSWORD`、`JAR_HOST`、`HTTP_PORT`。依赖：python3 + pymysql（缺失时脚本自动 `pip install pymysql`；宿主机没有 mysql 客户端也可以，需要时用 `./cluster.sh mysql`）。

## 三、实测发现（给后续改动的人）

1. **WHERE 里对“可空字符串列”调用会抛错的 UDF，可能整条查询失败。** 例如 `SELECT ... FROM t WHERE gl_date_delta(d,'day=+1')='20180102'`，`d` 为可空 STRING 且有 NULL 行时，报 `NullPointerException: date must not be null`（be.INFO 栈里是 `column_predicate_rewriter.cpp` → `ColumnExprPredicate`）。原因是 BE 把谓词下推到字典上求值，字典里有 NULL 行占位的空串，而 `gl_*` 对空串按 Hive 行为抛错。同一表上 SELECT 列表里用没问题（NULL 入参返回 NULL）。列是 NOT NULL，或会话里 `SET enable_column_expr_predicate = false`，就正常。用 `known_issues.csv` 可复现（不加 `--set` 失败，加了通过）。同一个探针 UDF（对空串不抛错）在 WHERE 里正常，说明这不是 NULL 传参框架问题，而是“空串抛错”和字典谓词求值的叠加。
2. **jar 的字节码版本不能高于 17**：FE/BE 的 JVM 是 17，用 JDK 21 默认参数编译的类会报 `compiled by a more recent version ... class file version 65.0`。模块 `pom.xml` 当前是 `maven.compiler.release=8`，注册和执行均成功；不要去掉这个设置。
3. 零参数 Java UDF `gl_now()` 在 BE 上可以注册并执行（返回 8 位字符串），见 `smoke.csv`。
4. 非法输入抛错时，BE 返回的是 `1064 JNI Exception, java.lang.NullPointerException ...`，与 Hive 一样是“查询失败”；CSV 里用 `ERROR` 即可匹配。
