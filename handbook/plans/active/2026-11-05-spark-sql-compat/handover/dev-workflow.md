# StarRocks Spark SQL 兼容项目 · 开发工作流与上手指南

- Status: draft
- 适用仓库: `github.com/Egeek2025/starrocks`（fork，基于上游 `StarRocks/starrocks` main，本文校验于 commit `485ef510`）
- 设计文档: [`../README.md`](../README.md)、[`../06-testing-strategy.md`](../06-testing-strategy.md)
- 约定: 命令均在仓库根 `$STARROCKS_HOME`（下文记作 `<repo>`）执行，除非特别注明。`[未验证]` 表示本文作者只读了脚本/文档，没有真实执行或无法在只读环境确认。

> 本文只给"在这个仓库里怎么干活"的精确命令与路径，不重复已有规范。先读下面这些（按顺序），再回来看本文：
>
> | 文档 | 读什么 |
> |---|---|
> | [`AGENTS.md`](../../../../../AGENTS.md) | 仓库级不变量、PR 契约、Observability 要求 |
> | [`handbook/index.md`](../../../../index.md)、[`domains/index.md`](../../../../domains/index.md)、[`policies/index.md`](../../../../policies/index.md) | handbook 导航 |
> | [`domains/frontend.md`](../../../../domains/frontend.md)、[`backend.md`](../../../../domains/backend.md)、[`sql-integration.md`](../../../../domains/sql-integration.md)、[`generated-and-extensions.md`](../../../../domains/generated-and-extensions.md)、[`ci-and-tooling.md`](../../../../domains/ci-and-tooling.md)、[`docs-and-translation.md`](../../../../domains/docs-and-translation.md) | 各子系统命令与护栏 |
> | [`policies/jdk-support-policy.md`](../../../../policies/jdk-support-policy.md)、[`agent-change-flow.md`](../../../../policies/agent-change-flow.md)、[`observability-awareness.md`](../../../../policies/observability-awareness.md)、[`reliability-first.md`](../../../../policies/reliability-first.md)、[`internal-vs-public-docs.md`](../../../../policies/internal-vs-public-docs.md) | 政策 |
> | [`fe/AGENTS.md`](../../../../../fe/AGENTS.md)、[`be/AGENTS.md`](../../../../../be/AGENTS.md)、[`test/AGENTS.md`](../../../../../test/AGENTS.md)、[`docs/AGENTS.md`](../../../../../docs/AGENTS.md)、[`gensrc/AGENTS.md`](../../../../../gensrc/AGENTS.md)、[`java-extensions/AGENTS.md`](../../../../../java-extensions/AGENTS.md) | 目录级规则 |
> | [`CONTRIBUTING.md`](../../../../../CONTRIBUTING.md) | 上游贡献流程（多处仍是 TBC，见 §5） |

---

## 1. 开发环境

### 1.1 硬件与 OS

| 项 | 要求 | 来源 |
|---|---|---|
| 内存 | Docker 构建最低 8 GB；**本项目建议 ≥ 32 GB**（BE 全量编译 + FE UT 并行 + 本地集群同时跑） | `docs/en/developers/build-starrocks/Build_in_docker.md`（8 GB）、`docs/en/developers/development-environment/ide-setup.md`（"at least 16GB RAM"）；32 GB 为本文建议 |
| 磁盘 | Docker 路径 ≥ 20 GB 空闲；源码构建（含 thirdparty、`be/build_*`、`~/.m2`）建议 ≥ 100 GB [未验证 实际占用] | `docs/en/developers/development-environment/dev-env-setup.md`（20 GB） |
| CPU/架构 | x86_64 或 aarch64 均支持；`build.sh` 默认并行度 = 核数/4 + 1（`PARALLEL=$[$host_parallelism/4+1]`），用 `-j` 覆盖 | `build.sh`、`docs/.../build_starrocks_on_ubuntu.md` |
| OS | Ubuntu 24.04（main）/ 22.04（v4.1 及更早）、Rocky Linux 9（v4.2+）、CentOS 7.9（仅 ≤4.1）；macOS 有独立路径（`build-support/darwin_build_env.sh`，见 `docs/en/developers/mac-compile-run-test.md`），本项目不推荐 | `Build_in_docker.md` |
| 编译器 | GCC ≥ 12.1.0（`env.sh` 硬检查 `required_ver="12.1.0"`）；CMake ≥ 3.20.1（Ubuntu 文档） | `env.sh` |
| 其他 | Python 3（`env.sh` 要求）、Maven（`MVN_CMD`，`env.sh` 检查 `mvn --version`）、`protoc`/`thrift`（`build.sh` 在 `$STARROCKS_THIRDPARTY/installed/bin` 或 PATH 中查找） | `env.sh`、`build.sh` |

### 1.2 JDK 版本（注意脚本间不一致）

- **FE 编译目标 = 17**：`fe/fe-core/pom.xml` 中 `maven.compiler.release=17`；`fe/README-gradle.md` 同样要求 17。
- **运行时下限 17、推荐 21**：`bin/start_fe.sh` 中 `MIN_JDK_VERSION=17`、`RECOMMENDED_JDK_VERSION=21`（低于推荐值只警告）。规则见 [`policies/jdk-support-policy.md`](../../../../policies/jdk-support-policy.md)。
- **陷阱**：`env.sh` 里的构建期检查只要求 `JAVA_VER >= 11`，**不会**拦住 JDK 11 去编 FE，之后才在 `maven-compiler` 阶段失败。本项目一律用 JDK 17 编译，JDK 21 做运行验证。
- `JAVA_HOME` 未设置时 `env.sh` 会按 `which javac` 推断；多 JDK 机器请显式 `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`。

### 1.3 官方开发镜像（推荐起点）

镜像: `starrocks/dev-env-ubuntu:latest`（main 分支对应；其他镜像见 `Build_in_docker.md` 的分支-镜像表）。镜像内已预装工具链与**预编译 thirdparty**（`/var/local/thirdparty`，由 `build-in-docker.sh` 注入 `STARROCKS_THIRDPARTY`）。镜像 Dockerfile 在 `docker/dockerfiles/dev-env/dev-env.Dockerfile`（说明见同目录 `README.md`，含 ubuntu/centos7/rocky9 三个 `--build-arg distro=` 变体）。

三个入口，层层包装，最终都落到 `build-in-docker.sh` → 容器内 `./build.sh`：

```bash
# 1) docker-dev.sh：最简包装（shell / build-fe / build-be / build-all / clean-build / test-fe / test-be / test-all / build）
./docker-dev.sh shell
./docker-dev.sh build-fe
./docker-dev.sh build --be --with-gcov      # build 子命令透传任意 build.sh 参数

# 2) build-in-docker.sh：功能完整，--shell / --test / --image，其余参数透传 build.sh
./build-in-docker.sh --fe --be --clean -j 8
./build-in-docker.sh --shell
STARROCKS_DEV_ENV_IMAGE=starrocks/dev-env-ubuntu:latest ./build-in-docker.sh --fe

# 3) Makefile.docker：同样的包装，make 风格
make -f Makefile.docker docker-pull
make -f Makefile.docker shell
make -f Makefile.docker build-all
make -f Makefile.docker test-fe

# 4) docker-compose.dev.yml：服务 starrocks-dev / build-fe / build-be / test-fe / test-be，
#    带 maven 与 output 命名卷；UID/GID 取环境变量（默认 1000:1000）
docker compose -f docker-compose.dev.yml run --rm build-fe
```

要点（均来自 `build-in-docker.sh` 的 `setup_docker_run`）:

- 仓库挂载为 `/workspace`，`~/.m2` 挂为容器内 `/tmp/.m2`，`MAVEN_OPTS=-Dmaven.repo.local=/tmp/.m2/repository`，容器以宿主 `uid:gid` 运行，故产物属主正确。
- `output/` 就落在宿主仓库根目录下（因为整仓库被挂载）。
- `docker-dev.sh test-fe/test-be` 是直接 `docker run ... ./run-fe-ut.sh`，**不会**带 `--test` 过滤；要跑单个测试请 `./docker-dev.sh shell` 进容器后手动执行 §4 的命令。
- `docker-compose.dev.yml` 的 `starrocks-build-cache` 卷覆盖了 `/workspace/output`，会让宿主看不到 compose 路径的产物 [未验证 实际行为]；日常用 `build-in-docker.sh` 更直观。
- 镜像 tag 的 `latest` 会漂移；fork 长期开发建议固定 digest 并记录在团队 wiki [建议]。

### 1.4 Nix flake（可选）

- 入口 `flake.nix` + `nix/dev-env.nix`，支持 `x86_64-linux`、`aarch64-linux`、`aarch64-darwin`。
- 输出: `devShells.default`（通用）、`devShells.be`（BE 专用）、`packages.starrocks-build-env` 等，以及 thirdparty 分片包（`starrocks-thirdparty-*`）。
- 用法 [未验证 未在本环境运行 nix]:
  ```bash
  nix develop            # 默认 shell
  nix develop .#be       # BE 开发 shell
  nix flake check --print-build-logs   # 与 .github/workflows/nix-flake.yml 一致
  ```
- 仅当改动 `thirdparty/**`、`build-support/**`、`nix/**`、`flake.nix` 时 CI 才触发 `nix-flake.yml`；本项目不碰这些路径，**优先使用 Docker 镜像**，nix 作为备选。

### 1.5 源码方式准备 thirdparty（无 Docker 时）

`build.sh` 在 `$STARROCKS_THIRDPARTY/installed/llvm/lib/libLLVMInstCombine.a` 不存在时自动执行 `thirdparty/build-thirdparty.sh`（`STARROCKS_THIRDPARTY` 默认 `<repo>/thirdparty`）。手动方式：

```bash
cd thirdparty
./download-thirdparty.sh          # 下载源码包（URL 在 thirdparty/vars.sh）
./build-thirdparty.sh -j16        # 全量；耗时很长 [未验证 具体时长，经验值数小时]
./build-thirdparty.sh --continue rocksdb   # 失败后从某个包继续
./build-thirdparty.sh --clean boost thrift # 只重编指定包
```

Ubuntu 依赖安装见 `docs/en/developers/build-starrocks/build_starrocks_on_ubuntu.md`（`apt install build-essential automake bison byacc ccache flex libiberty-dev libtool maven zip python3 python-is-python3 bzip2 cmake gcc g++ openjdk-17-jdk`）。Ubuntu 24.04 / GCC 12+ 若 thirdparty 因 warning-as-error 失败：`export DISABLE_WARNING_AS_ERROR=1`（同文档 FAQ）。

> **项目优先级**: Spark 兼容的绝大部分改动在 FE（`fe-core` + `fe-grammar`）与 `gensrc/script/functions.py`、少量 BE 函数。**FE 开发者可以完全不编 BE**：用官方发布的 BE 二进制（或同事共享的 `output/be`）+ 自己编的 `output/fe` 即可组集群（见 §3）。

### 1.6 IDE

**IntelliJ IDEA（FE）**，权威步骤: `docs/en/developers/development-environment/IDEA.md`（macOS Apple Silicon）与 `ide-setup.md`（远程服务器编译）。

1. `File > Open` 选 `<repo>/fe`（只开 fe 目录，Maven 工程根为 `fe/pom.xml`）。
2. 先在命令行 `cd fe && mvn install -DskipTests`，生成 thrift/proto/`VectorizedBuiltinFunctions` 等 Java 源码（输出在 `fe/fe-core/target/generated-sources/`），否则 IDE 里大量红线。
3. 导入代码风格: `Settings > Editor > Code Style > Java > Import Scheme`，选 `fe/starrocks_intellij_style.xml`。
4. 安装 CheckStyle-IDEA 插件，配置文件指向 `fe/checkstyle.xml`（Google Java Style 派生，强制 import 顺序与许可证头，见 `fe/AGENTS.md` Code Style 一节）。
5. 在 IDE 里直接跑 `StarRocksFE.main`（`fe/fe-core/src/main/java/com/starrocks/StarRocksFE.java`）需要：把 `conf`、`bin`、`webroot` 复制到 `fe/` 下，创建 `fe/log`、`fe/meta`，并设置 `STARROCKS_HOME` 环境变量，详见 `IDEA.md` "Run StarRocks FE in MacOS"。
6. 设置 Project SDK = JDK 17。

**CLion / clangd（BE）**:

1. 先跑一次 `./build.sh --be`（或 `./run-be-ut.sh`），BE 的 thrift/proto C++ 头文件由 CMake configure 生成到活动构建目录，如 `be/build_Release/gensrc/gen_cpp`（`ide-setup.md`）。
2. `compile_commands.json`: `build.sh`（L662）与 `run-be-ut.sh`（L369）都传了 `-DCMAKE_EXPORT_COMPILE_COMMANDS=ON`，因此文件位于 `be/build_${BUILD_TYPE}/compile_commands.json`（Release）或 `be/ut_build_${BUILD_TYPE}/compile_commands.json`（UT 构建）。clangd 用法:
   ```bash
   ln -sf be/build_Release/compile_commands.json be/compile_commands.json   # 让 clangd 在 be/ 下找到
   ```
   并确保 clangd 的 `--query-driver` 指向 `$STARROCKS_GCC_HOME/bin/g++` [未验证]。
   注意 `be/CMakeLists.txt:114` 写的是 `set(CMAKE_EXPORT_COMPILECOMMANDS ON)`（变量名拼写疑似缺下划线，不生效），实际依赖的是命令行 `-D` 参数，所以**不要绕过 `build.sh` 自己跑 cmake**。
3. CLion: `Open` 选 `<repo>/be`；Toolchain 环境变量设 `JAVA_HOME`、`STARROCKS_GCC_HOME=/usr/`、`STARROCKS_THIRDPARTY=<thirdparty 路径>`（`ide-setup.md`）。
4. 格式化: 仓库根 `.clang-format`、`.clang-tidy`。

---

## 2. 构建

### 2.1 `build.sh` 常用选项

完整列表: `./build.sh --help`。与本项目相关的：

| 选项 / 环境变量 | 作用 |
|---|---|
| （无参数） | 构建 FE + BE（`output/`） |
| `--fe` | FE（含 Spark DPP 应用、hive-udf 插件）。Maven: `mvn package -am -pl <FE_MODULES> -DskipTests -T <PARALLEL>` |
| `--be` | BE。先 `cd gensrc && make script`，再 CMake + make/ninja |
| `--clean` | 必须与 `--fe/--be/--format-lib/--spark-dpp/--hive-udf` 之一同用；BE 会 `rm -rf be/build_<TYPE>` 与 `be/output/`，gensrc 执行 `make clean` |
| `-j N` | 并行度；默认 核数/4+1 |
| `BUILD_TYPE=Release\|Debug\|ASAN`（环境变量） | 默认 Release；构建目录 `be/build_${BUILD_TYPE}`，例 `BUILD_TYPE=Debug ./build.sh --be -j16` |
| `--with-gcov` / `--without-gcov` | BE 覆盖率构建，目录 `be/build_${BUILD_TYPE}_gcov`；**与 `BUILD_TYPE=ASAN` 互斥**（脚本直接报错退出） |
| `--without-java-ext` | BE 不构建 `java-extensions`（FE 开发/加速 BE 编译常用） |
| `--without-starcache` / `--without-tenann` / `--without-paimon-cpp` / `--without-avx2` | 裁剪依赖，加速或适配老 CPU |
| `--enable-shared-data` | 存算分离，本项目不需要 |
| `--disable-java-check-style` 或 `DISABLE_JAVA_CHECK_STYLE=ON` | 构建时跳过 checkstyle（`-Dcheckstyle.skip=true`）。**提交前必须开着跑一次**，CI 会查 |
| `--output PATH` | 修改产物目录（默认 `<repo>/output/`） |
| `--configure-only` | 只做 CMake configure（生成 `compile_commands.json` 给 IDE 用最快） |
| `--with-clang-tidy`、`--with-bench` | 静态检查/基准 |

最小示例:

```bash
./build.sh --fe -j8                              # 只编 FE（增量）
./build.sh --fe --be --clean -j16                # 全量重编
BUILD_TYPE=ASAN ./build.sh --be --without-java-ext -j16
./build.sh --be --with-gcov
```

`ASAN`: 即 `BUILD_TYPE=ASAN`（大小写敏感，脚本比较的是字符串 `"ASAN"`）；产物额外拷贝 `asan_suppressions.conf`。

### 2.2 FE: Maven（正式）vs Gradle（可选）

- **Maven 是 canonical 构建**，`build.sh --fe`、`run-fe-ut.sh`、CI 全部走 Maven。
- Gradle（`fe/build.gradle.kts`、`fe/settings.gradle.kts`、`fe/gradlew`）是镜像实现，说明见 [`fe/README-gradle.md`](../../../../../fe/README-gradle.md)。常用:
  ```bash
  cd fe
  ./gradlew :fe-core:build                       # 编译+测试+checkstyle
  ./gradlew :fe-core:test --tests "com.starrocks.qe.QueryPlannerTest"
  ./gradlew :fe-core:test -Pfe_ut_parallel=4
  ./gradlew :fe-core:checkstyleMain :fe-core:checkstyleTest
  ./gradlew :fe-core:generateByScripts           # 仅重跑 gen_build_version.py + gen_functions.py
  ```
- 同步规则: 改了 `pom.xml` 依赖后需同步 Gradle，脚本 `build-support/sync_pom_to_gradle.py` [未验证 用法]。**本项目建议只用 Maven**，Gradle 仅在 IDE 导入慢时备用，避免双轨导致的"本地过 CI 不过"。
- 常用 Maven 单模块命令（`fe/AGENTS.md`）:
  ```bash
  cd fe && mvn package -pl fe-core -am -DskipTests
  cd fe && mvn checkstyle:check -pl fe-core
  ```

### 2.3 gensrc 生成与何时重跑

| 产物 | 生成方 | 触发时机 |
|---|---|---|
| FE 侧 thrift Java | `fe-core/pom.xml` 的 thrift 插件，源 `gensrc/thrift/*.thrift` | 每次 FE Maven 构建的 generate-sources 阶段 |
| FE 侧 proto Java | `fe-core/pom.xml` antrun 里调 `jprotobuf-starrocks`，源 `gensrc/proto/*.proto` | 同上 |
| `VectorizedBuiltinFunctions.java`（FE 内置函数注册表）+ BE `gen_cpp/opcode/*.inc` | `python3 gensrc/script/gen_functions.py --cpp gensrc/build/gen_cpp --java fe/fe-core/target/generated-sources/build`，源 `gensrc/script/functions.py`（1913 行） | FE：每次 Maven generate-sources；BE：`build.sh --be` 先 `cd gensrc && make script`；Gradle：`generateByScripts` |
| 版本类 | `build-support/gen_build_version.py` | 同上 |
| BE 侧 thrift/proto C++ | CMake configure/build，输出 `be/build_<TYPE>/gensrc/gen_cpp` | `build.sh --be` / `run-be-ut.sh` |

结论:

- **改 `functions.py` 后**：FE 需要重新 `./build.sh --fe`（或 `cd fe && mvn generate-sources -pl fe-core`）；BE 需要 `./build.sh --be`。两侧都要重编，否则 FE 看得见函数而 BE 找不到实现（运行期报错）。
- 重复函数 id 或重复签名会被 `gen_functions.py` 直接 `exit(1)`（`add_function`），可当作第一道冲突检测。
- **不要手改** `gensrc/build/`、`fe/fe-core/target/generated-sources/` 或 `be/build_*/gensrc`（`gensrc/AGENTS.md` Golden Rule）。
- thrift/proto 字段只能 optional/repeated、序号不可复用；改动 `gensrc/thrift|proto` 会触发 CI `ci-proto-checker` 加标签，并受 `build-support/check_gensrc_schema_compatibility.py` 约束。**本项目原则上不改 thrift/proto**（设计上全部在 FE 完成），如必须改请先升级评审。

### 2.4 StarRocks.g4（语法）何时重新生成

ANTLR 语法在 `fe/fe-grammar/src/main/antlr/com/starrocks/grammar/{StarRocks.g4,StarRocksLex.g4}`，由 `fe-grammar` 模块的 Maven antlr 插件在构建时生成 lexer/parser（说明见 `fe/fe-grammar/README.md`、`fe/fe-parser/README.md`）。**设计上 Spark 方言用独立的 vendored 语法**（`fe-grammar/.../spark/`，见 [`../01-architecture.md`](../01-architecture.md) §5），不改 `StarRocks.g4`；因此只需 `cd fe && mvn install -DskipTests -pl fe-grammar,fe-parser -am` 就会重新生成。

### 2.5 产物目录

`build.sh` 把所有内容拷贝到 `output/`（`STARROCKS_OUTPUT`）:

```
output/
├── fe/
│   ├── bin/        start_fe.sh stop_fe.sh common.sh show_fe_version.sh
│   ├── conf/       fe.conf udf_security.policy hadoop_env.sh core-site.xml cluster_snapshot.yaml failpoint.btm
│   ├── lib/        starrocks-fe.jar + 依赖 jar + starrocks-hadoop-ext.jar
│   ├── webroot/
│   ├── spark-dpp/  hive-udf/
├── be/
│   ├── bin/        start_be.sh start_backend.sh stop_be.sh start_cn.sh ...
│   ├── conf/       be.conf be_test.conf cn.conf hadoop_env.sh log4j2.properties core-site.xml udf_security.policy
│   ├── lib/        starrocks_be  *.so  jni-packages/  py-packages/  hadoop/
│   └── www/
└── format-lib/     （仅 --format-lib）
```

运行期会在 `output/fe/` 下新增 `log/`、`meta/`，`output/be/` 下新增 `log/`、`storage/`。**重新 `build.sh --fe` 会 `rm -rf output/fe/lib/*` 并覆盖 conf**（`cp -r -p conf/fe.conf`），所以不要把长期使用的集群目录直接放在 `output/` 内，见 §3 脚本做法。

---

## 3. 本地集群

### 3.1 关键配置

`output/fe/conf/fe.conf`（模板 `conf/fe.conf`，默认值如下；全部 FE 参数见 `fe/fe-core/src/main/java/com/starrocks/common/Config.java`）:

| 键 | 默认 | 说明 |
|---|---|---|
| `http_port` | 8030 | FE HTTP |
| `rpc_port` | 9020 | thrift RPC |
| `query_port` | 9030 | **MySQL 协议端口**，`mysql -P9030` |
| `edit_log_port` | 9010 | FE 间 bdbje 复制 |
| `meta_dir` | `${STARROCKS_HOME}/meta`（注释状态，默认值） | 元数据，换版本不兼容时需清空 |
| `priority_networks` | 空 | 多网卡时必设，如 `127.0.0.1/32` 或 `10.0.0.0/8` |
| `JAVA_OPTS` | `-Xmx8192m -XX:+UseG1GC ...` | 本机内存小时降低 `-Xmx`；调试加 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` |
| `sys_log_level` | INFO | 调试 Spark 方言翻译时可临时 DEBUG |

`output/be/conf/be.conf`:

| 键 | 默认 | 说明 |
|---|---|---|
| `be_port` | 9060 | |
| `be_http_port` | 8040 | |
| `heartbeat_service_port` | 9050 | **`ADD BACKEND` 用的是这个端口** |
| `brpc_port` | 8060 | |
| `starlet_port` | 9070 | 存算分离，忽略 |
| `storage_root_path` | `${STARROCKS_HOME}/storage`（默认，注释中说明需手动创建） | |
| `priority_networks` | 空 | 与 FE 同网段 |
| `JAVA_OPTS` | `--add-opens=...` | JNI/Java UDF 用 |

### 3.2 启动 / 停止 / 日志

```bash
# FE
output/fe/bin/start_fe.sh --daemon          # 前台去掉 --daemon；多 FE 用 --helper <ip:edit_log_port>
output/fe/bin/stop_fe.sh
# BE
output/be/bin/start_be.sh --daemon
output/be/bin/stop_be.sh
```

- 启动脚本会校验 JDK（`MIN_JDK_VERSION=17`，<21 警告）。
- 日志目录均为 `$STARROCKS_HOME/log`（`sys_log_dir`）:
  - FE: `output/fe/log/fe.log`（全量）、`fe.warn.log`（WARN+）、`fe.audit.log`（审计，可查完整 SQL）、`fe.out`（stdout/stderr）、`fe.gc.log.*`、`fe.dump.log` / `fe.plan.log`（见 `Config.java`）
  - BE: `output/be/log/be.INFO`、`be.WARNING`、`be.out`（来源 `bin/start_backend.sh` 的 `LOG_FILE=$LOG_DIR/be.out`；glog 文件名 [未验证]）
- 排错首选: `tail -f output/fe/log/fe.log`；Spark 方言翻译失败回落时，FE 日志里应有降级 warn（Trino 同构：`SqlParser.parseWithTrinoDialect` 里的 catch 分支，可参考）。

### 3.3 加 BE、连接、验证

```bash
mysql -h127.0.0.1 -P9030 -uroot --prompt="StarRocks > "
```
```sql
ALTER SYSTEM ADD BACKEND "127.0.0.1:9050";   -- heartbeat_service_port
SHOW BACKENDS\G                               -- Alive: true 即成功
SELECT current_version();
SET sql_dialect = 'trino';                    -- 现有先例；Spark 方言上线后同理 'spark'
```

（来源: `docs/en/deployment/deploy_manually.md` 第 239 行起；多 FE 用 `ALTER SYSTEM ADD FOLLOWER "<fe>:<edit_log_port>"`。）

### 3.4 一键最小脚本（单机 1 FE + 1 BE）

放在仓库外（如 `~/sr-dev/up.sh`），**用拷贝而不是直接用 `output/`**，避免被重新构建覆盖 [本脚本为本文新写，未实际执行 → 未验证]:

```bash
#!/usr/bin/env bash
set -euo pipefail
SRC=${SR_REPO:?set SR_REPO=/path/to/starrocks}/output
RUN=${SR_RUN:-$HOME/sr-dev/run}
mkdir -p "$RUN"
# 同步二进制，保留 conf/meta/storage/log
rsync -a --delete --exclude conf --exclude meta --exclude storage --exclude log "$SRC/fe/" "$RUN/fe/"
rsync -a --delete --exclude conf --exclude meta --exclude storage --exclude log "$SRC/be/" "$RUN/be/"
[ -f "$RUN/fe/conf/fe.conf" ] || { mkdir -p "$RUN/fe/conf"; cp -r "$SRC/fe/conf/." "$RUN/fe/conf/"; }
[ -f "$RUN/be/conf/be.conf" ] || { mkdir -p "$RUN/be/conf"; cp -r "$SRC/be/conf/." "$RUN/be/conf/"; }
grep -q '^priority_networks' "$RUN/fe/conf/fe.conf" || echo 'priority_networks = 127.0.0.1/32' >> "$RUN/fe/conf/fe.conf"
grep -q '^priority_networks' "$RUN/be/conf/be.conf" || echo 'priority_networks = 127.0.0.1/32' >> "$RUN/be/conf/be.conf"
mkdir -p "$RUN/be/storage"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
"$RUN/fe/bin/start_fe.sh" --daemon
until mysql -h127.0.0.1 -P9030 -uroot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
"$RUN/be/bin/start_be.sh" --daemon
mysql -h127.0.0.1 -P9030 -uroot -e 'ALTER SYSTEM ADD BACKEND "127.0.0.1:9050"' || true  # 重复添加会报错，可忽略
until mysql -h127.0.0.1 -P9030 -uroot -N -e 'show backends' | grep -q true; do sleep 2; done
echo "cluster ready: mysql -h127.0.0.1 -P9030 -uroot"
```

仅验证 FE 逻辑（Spark 方言翻译、`EXPLAIN`、`SHOW CREATE`）时，没有 BE 也能 `EXPLAIN`/分析（需要有 BE 才能建表 `replication_num=1`，所以 FE-only 验证主要靠 FE UT，见 §4.1）。

### 3.5 Hive Metastore + MinIO（外部 catalog / 差分测试）

**仓库内没有现成的 HMS + MinIO compose 脚本**（`docker/docker-compose/README.md` 只指向外部 demo 仓库）。可用资料：

1. `docs/en/quick_start/hudi.md`：使用 `StarRocks/demo` 仓库 `documentation-samples/hudi/docker-compose.yml`，含 `starrocks-fe/be`、`metastore_db`（Postgres）、`hive_metastore`、`minio`+`mc`、`spark-hudi`（带 Spark SQL）。这是最接近"Spark + HMS + MinIO + StarRocks 同一份数据"的现成环境，**直接适合 06 号文档的差分测试**。需要 Docker 5 GB 内存/4 CPU、20 GB 磁盘。
2. `docs/en/quick_start/iceberg.md`：内联 compose（`iceberg-rest` + `minio:RELEASE.2024-10-29T16-01-48Z`），无 HMS。
3. 外部 catalog 语法: `docs/en/data_source/catalog/hive_catalog.md`。示例（端点按你的 compose 修改）[未验证]:
   ```sql
   CREATE EXTERNAL CATALOG hive_cat PROPERTIES (
     "type"="hive",
     "hive.metastore.type"="hive",
     "hive.metastore.uris"="thrift://127.0.0.1:9083",
     "aws.s3.enable_ssl"="false",
     "aws.s3.enable_path_style_access"="true",
     "aws.s3.endpoint"="http://127.0.0.1:9000",
     "aws.s3.access_key"="minioadmin",
     "aws.s3.secret_key"="minioadmin");
   ```
4. SQL-tester 框架已预留 `[client.spark-client]`、`[client.hive-client]`、`[env.hive] hive_metastore_uris` 配置节（`test/conf/sr.conf`），且 `test/lib/spark_lib.py` 用 `pyhive.hive.Connection` 连 Spark Thrift Server——即可以在同一个 T 文件里同时对 Spark 与 StarRocks 执行语句做对照。具体 helper 用法请读 `test/lib/spark_lib.py` 与 `test/lib/sr_sql_lib.py` [未验证 端到端]。

---

## 4. 测试

总览见 [`../06-testing-strategy.md`](../06-testing-strategy.md)（金字塔与 CI 分层）。本节只给命令。

### 4.1 FE 单测 `run-fe-ut.sh`

```
./run-fe-ut.sh [--test <Class|Class#method>] [--filter <Class#method,...>] [--dry-run] [--coverage] [-j N]
```

- 实质执行 `mvn verify -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -DtrimStackTrace=false -D test="$TEST_NAME" -T $PARALLEL`，工作目录 `fe/`。
- 并行度 `-j N` 或环境变量 `FE_UT_PARALLEL`（默认 4），对应 surefire `forkCount`；JMockit 测试吃内存，内存 <32 GB 时用 `-j2`。
- 脚本会自动把 `QueryDumpRegressionTest,QueryDumpCaseRewriter` 加入排除。

```bash
# 单个测试类（全限定名）
./run-fe-ut.sh --test com.starrocks.connector.parser.trino.TrinoParserNotSupportTest
# 单个方法
./run-fe-ut.sh --test 'com.starrocks.connector.parser.trino.TrinoParserNotSupportTest#testXxx'   # 方法名以实际为准 [未验证]
# 一个包下全部 Trino 方言测试（surefire 通配）
./run-fe-ut.sh --test 'com.starrocks.connector.parser.trino.*'
# 跳过某个用例
./run-fe-ut.sh --filter com.starrocks.utframe.Demo#Test0
# 调试时直接用 Maven，速度更快（已编译后）
cd fe && mvn test -pl fe-core -Dtest=TrinoFunctionTransformTest -DfailIfNoTests=false
```

- Trino 套件位置（Spark 套件的模板）: `fe/fe-core/src/test/java/com/starrocks/connector/parser/trino/`（`TrinoTestBase`、`TrinoFunctionTransformTest`、`TrinoTPCDSTest`、`TrinoDialectDowngradeTest`、`TrinoParserNotSupportTest`、`TrinoInsertTest`）。通用基类 `PlanTestBase`、`UtFrameUtils`、`StarRocksAssert`（`handbook/domains/frontend.md`）。
- **覆盖率**: `./run-fe-ut.sh --coverage` 调用 `ant cover-test`，但 `fe/` 下**没有 `build.xml`**，该选项在当前代码树大概率不可用 [未验证]。可改用 Maven JaCoCo [未验证 `fe/pom.xml` 是否配置了 jacoco 插件，grep 未命中]；CI 侧覆盖率见 `.github/workflows/inspection-fe-coverage.yml`。
- 风格: `cd fe && mvn checkstyle:check -pl fe-core`（提交前必跑）。

### 4.2 BE 单测 `run-be-ut.sh`

```
./run-be-ut.sh [--test NAME] [--gtest_filter F] [--module M] [--build-target T] [--clean] [--with-gcov] [--without-java-ext] [-j N]
```

- 构建目录 `be/ut_build_${BUILD_TYPE}`，测试二进制 `…/test/starrocks_test`（以及 `starrocks_dw_test` 与模块化测试二进制）。
- **`--test NAME` 会被改写成 `NAME*`**（脚本 `TEST_NAME=${2}*`），即按 gtest 套件名前缀匹配；要精确到用例用 `--gtest_filter`。

```bash
# 某个套件
./run-be-ut.sh --without-java-ext --test VecMathFunctionsTest
# 精确到用例
./run-be-ut.sh --without-java-ext --gtest_filter 'VecMathFunctionsTest.truncateTest'
# 只编某个目标 + 模块（handbook/domains/backend.md 推荐的小循环）
./run-be-ut.sh --build-target <test_binary> --module <test_binary> --without-java-ext
# 已构建后可直接运行二进制
be/ut_build_Release/test/starrocks_test --gtest_filter='VecMathFunctionsTest.*'   # 路径依赖 BUILD_TYPE [未验证]
```

- 测试源码: `be/test/exprs/`（例 `math_functions_test.cpp`），Spark 函数的 BE 测试按设计放 `be/test/exprs/spark/*`（见 06 §1）。
- BE 覆盖率: `./run-be-ut.sh --with-gcov`（与 ASAN 互斥）。汇总报告（lcov/gcovr）需自行执行 [未验证]；CI 见 `.github/workflows/inspection-be-coverage.yml`。
- 提交前格式化: `build-support/clang-format-changed-check.sh`（仅检查相对 `origin/main` 的改动）或 `build-support/clang-format-changed.sh`（自动格式化）。

### 4.3 java-extensions 单测

```bash
./run-java-exts-ut.sh                       # 等价于 cd java-extensions && mvn test
cd java-extensions && mvn test -pl hive-reader -Dtest=HiveScannerTest   # 单测，见 java-extensions/AGENTS.md
cd java-extensions && mvn checkstyle:check
```

本项目一般不涉及；若做 Hive UDF 桥（设计 L4）会碰 `java-extensions/udf-extensions` 与 `fe/plugin/hive-udf`。

### 4.4 SQL 集成测试（`test/`）

框架与语法的权威文档: [`test/README.md`](../../../../../test/README.md)、[`test/AGENTS.md`](../../../../../test/AGENTS.md)、[`domains/sql-integration.md`](../../../../domains/sql-integration.md)。

**准备**:

```bash
cd test
python3 -m pip install -r requirements.txt     # 含 nose、pymysql、pyhive 等
```

**连接配置**: 编辑 `test/conf/sr.conf`。⚠ 实际文件使用 `[cluster]` 段（`host/port/user/password/http_port`），而 `test/README.md` 与 `test/AGENTS.md` 里示例写的是 `[mysql-client]`——**以 `test/conf/sr.conf` 现状为准**。本地集群默认值（127.0.0.1:9030、root、无密码、http 8030）已匹配 §3 的集群，通常无需改动。也可用 `--config <path>` 指定另一份配置，推荐团队各自维护 `~/sr-dev/sr.conf`。

**run.py 参数**（`python3 run.py -h` 为准）:

| 参数 | 含义 |
|---|---|
| `-d PATH` | 目录或文件，默认 `./sql` |
| `-v` / `-r` | 校验模式（默认，跑 R 文件）/ 录制模式（跑 T 文件，生成 R） |
| `-p` | 配合 `-r`，只更新执行到的 case，不覆盖文件其它 case |
| `-c N` / `-t S` | 并发（默认 8）/ 单 case 超时（默认 600s） |
| `-l` | 只列出 case，不执行 |
| `-a ATTR` / `-C cloud\|native` | 属性过滤（如 `sequential`）/ 集群类型（默认 native） |
| `--file_filter RE` / `--case_filter RE` | 按文件名 / case 名正则过滤 |
| `--skip_reruns` | 默认每个 case 要连续成功 3 次，加此参数只跑 1 次（本地迭代必加） |
| `--config PATH`、`--check-status` | 配置文件 / 运行前检查集群状态 |

**T / R 文件格式**（均无扩展名，位于 `test/sql/<suite>/{T,R}/<name>`）:

- T 文件 = 语句序列，每个 case 以 `-- name: <case_name>`（`[a-zA-Z0-9_-]+`）开头。
- R 文件 = 相同语句 + 每条查询后的 `-- result:` … `-- !result` 块，由录制模式生成，**不要手写**。
- 常用变量/标记: `${uuid0}`（唯一名）、`[ORDER]`、`[REGEX]`、`[UC]`（跳过校验）、`shell:`、`function:`、`@native/@cloud/@sequential/@slow` 等（语义以 `test/README.md` 为准）。
- 现成先例: `test/sql/test_trino_dialect/{T,R}/test_trino_dialect` 与 `test_trino_timestamp_with_time_zone`（文件内用 `set sql_dialect='trino';` 切换方言）。

**最小示例**：

```bash
# 校验现有 Trino 方言用例（先决条件：§3 的集群在跑）
cd test
python3 run.py -d sql/test_trino_dialect -v --skip_reruns
python3 run.py -d sql/test_trino_dialect -l           # 只列 case
```

**新增 `test/sql/test_spark_dialect`**：

```bash
mkdir -p test/sql/test_spark_dialect/T test/sql/test_spark_dialect/R
cat > test/sql/test_spark_dialect/T/test_spark_basic <<'SQL'
-- name: test_spark_basic
create database db_${uuid0};
use db_${uuid0};
create table t1 (c1 int, c2 string) duplicate key(c1)
    distributed by hash(c1) buckets 1 properties ("replication_num" = "1");
insert into t1 values (1, 'a'), (2, 'b');
set sql_dialect = 'spark';
select c1, c2 from t1 order by c1;
drop database db_${uuid0} force;
SQL
cd test
python3 run.py -d sql/test_spark_dialect/T/test_spark_basic -r      # 录制 → 生成 R/test_spark_basic
git diff --no-index /dev/null sql/test_spark_dialect/R/test_spark_basic | head -50   # 人工审查 R 文件！
python3 run.py -d sql/test_spark_dialect/R/test_spark_basic -v --skip_reruns        # 校验
```

录制纪律（本项目特别重要）:

1. **R 文件必须人工逐条对照 Spark 真实结果审查**，不能"录下来就当对"——否则会把静默错误固化成期望（违背"不静默给错结果"原则，见 README 验收标准）。有 Spark 环境时用 §3.5 的差分环境核对。
2. `set sql_dialect='spark'` 之后的语句才走新方言；case 结束 session 即销毁，无需还原。
3. `dialect` 之外的全局状态（`ADMIN SET FRONTEND CONFIG`）必须在 case 内恢复（`sql-integration.md` Sequential 一节）。
4. CI 有 `ci-sql-tester-case-files.yml` 检查 case 文件（R 与 T 配对等），脚本在外部 `ci-tool` 仓库，具体规则 [未验证]。

### 4.5 覆盖率小结

| 层 | 命令 | 状态 |
|---|---|---|
| FE | `./run-fe-ut.sh --coverage`（`ant cover-test`） | 当前树缺 `fe/build.xml`，[未验证] 可用性 |
| BE | `./run-be-ut.sh --with-gcov` | 可用；报告汇总需自备 lcov/gcovr [未验证] |
| 项目指标 | Spark golden 通过率、函数覆盖率、TPC-DS 99/99 | 见 06 §4；`tools/spark-compat/golden_runner.py` 目前**尚不存在**（设计中） |

---

## 5. CI 与 PR 规则

### 5.1 主要 workflow（`.github/workflows/`，共 ~58 个，下表只列与日常 PR 相关的）

| Workflow | 触发 | 作用 |
|---|---|---|
| `ci-pipeline.yml`（CI PIPELINE） | PR → main | 主流水线: Check Title/Linked Issue/Test Case/Approve → BE/FE 路径过滤 → **BE Architecture**（module boundary 脚本）、**Repo Handbook**（`check_repo_handbook.py`）、**Schema Compatibility**（thrift/proto 兼容检查）、**FE Code Style Check**（checkstyle，仅改了 java 时）、**FE UT**、**BE UT**、**Clang-Tidy**、BUILD → DEPLOY SR → **SQL-Tester**、ADMIT TEST、docgen |
| `ci-pipeline-branch.yml` | PR → `branch-*` | 发布分支版 |
| `pr-checker.yml`（PR CHECKER） | PR 开/改 | 标题前缀检查（`thehanimo/pr-title-checker`，配置 `.github/pr-title-checker-config.json`）、backport PR 标题格式 `(backport #N)`、自动打 `4.x` 版本标签 |
| `ci-clang-format.yml` | `be/src/**`、`be/test/**` 变更 | 自动格式化并 push 回 PR 分支（需 `secrets.PAT`） |
| `ci-proto-checker.yml` | `gensrc/proto|thrift/**` | 加 proto review 标签 |
| `ci-sql-tester-case-files.yml` | PR | SQL-Tester case 文件检查 |
| `ci-doc-checker.yml` / `ci-doc-needs-update.yml` / `ci-doc-param-drift.yml` / `ci-doc-translation-check.yml` / `ci-doc-translater.yml` | `docs/**`、`Config.java`、**`SessionVariable.java`**、`GlobalVariable.java`、`be/src/common/config.h` | 文档标签、参数文档缺失提示（informational，不阻塞；新增会话变量会收到评论）、中英日翻译状态 |
| `ci-vale.yml` | `docs/{en,zh,ja}/**` | Vale 文风 lint（配置 `docs/.vale.ini`） |
| `nix-flake.yml` | thirdparty/nix 相关路径 | `nix flake check` |
| `ci-merged.yml` | PR 合并 | 按版本标签用 Mergify 自动 backport |
| `backport-boxes.yml`、`ci-backport-checker.yml`、`contributed-backport-label.yml` | 标签/勾选 | backport 勾选框同步与通知 |
| `inspection-pipeline.yml` | cron 工作日 | 夜间巡检（含 sequential、slow、覆盖率） |
| `approve-checker.yml` | review 提交 | 审批规则 |

### 5.2 PR 规范

- **标题前缀**（强制，正则忽略大小写）: `[BugFix]` `[Feature]` `[Enhancement]` `[Refactor]` `[UT]` `[Doc]` `[Tool]`，或 `Revert ...`。例: `[Feature] Add spark sql dialect parser skeleton`。
- **commit message**: 英文、祈使句、简洁（`AGENTS.md` PR Contract；`CONTRIBUTING.md` 要求动词开头大写）；建议一个 PR 尽量一个 commit（`CONTRIBUTING.md`）。
- **模板** `.github/PULL_REQUEST_TEMPLATE.md`：`Why I'm doing` / `What I'm doing` / `Fixes #issue` / PR 类型 / 是否行为变更及类型（Interface/UI、Parameter、Policy、Feature removed、Misc）/ Checklist（测试、用户文档、backport）/ Bugfix cherry-pick 版本勾选（4.2/4.1/4.0）。必须填全。
- **Observability**: PR 描述需说明评估过哪些既有信号及保留/扩展情况（`AGENTS.md`）。
- **许可证头**: FE 由 `fe/checkstyle.xml` 的 `RegexpHeader` 强制，允许两种头——ASF 头（`// Licensed to the Apache Software Foundation (ASF) under one`）或 `// Copyright 2021-present StarRocks, Inc. All rights reserved.`。新文件请用后者；`fe/checkstyle-apache-header.txt` 为参考。BE 新文件同样加 StarRocks 头 [未验证 BE 是否有自动 header 检查]。**vendored Spark 语法文件**（Apache-2.0）要保留原头并更新 `NOTICE.txt`/`licenses/`（设计 01 §5 已注明）。
- **FE 风格**: `cd fe && mvn checkstyle:check -pl fe-core`；CI 用 `dbelyaev/action-checkstyle` 复制 `fe/checkstyle*` 执行。
- **BE 风格**: `.clang-format`；PR 里 `ci-clang-format` 会自动改写提交，本地先 `build-support/clang-format-changed.sh`。
- **文档**（`docs/AGENTS.md` = `docs/CLAUDE.md`）: 改 `docs/` 前必读；**en / zh / ja 三语同步**（ja 若不存在需说明）；frontmatter 必须含 `displayed_sidebar: docs`；围栏代码块必须标语言；不得凭记忆写技术细节、必须对照现有文档核实；Vale 通过（`ci-vale`）；移动页面需在 `docs/docusaurus/docusaurus.config.js` 的 `MOVED_DOCS` 加重定向。**新增 `sql_dialect=spark` 与 `spark_*` 会话变量属于用户可见变化，需同步 `docs/{en,zh}/sql-reference/System_variable.md`**（`ci-doc-param-drift` 会提示）。
- **handbook vs docs**: 设计/内部流程写 `handbook/`（本文所在处），用户可见行为写 `docs/`，见 [`policies/internal-vs-public-docs.md`](../../../../policies/internal-vs-public-docs.md)。`handbook/` 有 CI 校验: `python3 build-support/check_repo_handbook.py`（提交含 handbook 改动前本地跑；新增链接必须有效）。

### 5.3 Backport 标签

上游: 针对 main 的 `[BugFix]/[Enhancement]/[Doc]/[UT]` PR，`pr-checker` 自动打 `4.2/4.1/4.0` 等标签，合并后 `ci-merged.yml` 通过 Mergify（`@Mergifyio backport branch-X.Y`）开 backport PR，标题以 `(backport #N)` 结尾；冲突时打 `conflicts` 标签（`.mergify.yml`）。

### 5.4 对 fork 的适用性

| 项 | 在 fork 上 | 建议 |
|---|---|---|
| `runs-on: [self-hosted, ...]`（32 个 workflow，含 `ci-pipeline.yml` 的 FE UT/BE UT/BUILD/SQL-Tester） | **没有 runner → job 永远排队/不跑** | 在 fork 自建最小 workflow，如 `fork-ci.yml`：`ubuntu-latest` + `container: starrocks/dev-env-ubuntu:latest` 执行 `./run-fe-ut.sh --test <我们的套件>` 与 `mvn checkstyle:check -pl fe-core` [建议，未验证]；BE/SQL-Tester 起步阶段在本地跑 |
| 带 `github.repository == 'StarRocks/starrocks'` 条件的 job（ci-merged、backport-boxes、release-*、ci-sync…） | 自动跳过 | 无需处理；fork 无 `branch-4.x` 也无 Mergify，**backport 标签与勾选框不适用**。模板里的勾选框保留但留空 |
| 仅用 `ubuntu-latest` 的 workflow（`ci-vale`、`ci-doc-*`、`ci-proto-checker`、`nix-flake`、`ci-clang-format`） | 可运行；`ci-clang-format` 需要 `secrets.PAT` | 没有 PAT 会失败；可在 fork 的 Actions 设置里禁用无关 workflow，保留 vale/doc 检查 |
| 上游依赖的 secrets（Feishu、Slack、OSS、SonarCloud 等） | 无 | 对应 job 会失败/跳过，属预期 |
| `pull_request_target` workflow | 运行的是 **fork 默认分支上的版本** | 修改 workflow 要合到 fork 的 `main` 才生效 |
| PR 标题前缀、模板、commit 规范 | 仅靠人工/`pr-checker`（其 title-check 在 ubuntu-latest 上可运行） | **照旧遵守**，这样未来向上游贡献通用修复（如 SqlParser 重构）时无需返工 |
| CLA（`cla-assistant`，`CONTRIBUTING.md`） | 向上游提 PR 才需要 | fork 内部不需要 |

---

## 6. Fork 维护

### 6.1 remotes 与分支模型

当前仓库只有 `origin = https://github.com/Egeek2025/starrocks`（已核对 `git remote -v`；本地仅有 `main` 与 `docs/spark-sql-compat-design`）。

```bash
git remote add upstream https://github.com/StarRocks/starrocks.git
git remote set-url --push upstream DISABLED     # 防误推
git config rerere.enabled true                  # 记住冲突解决，反复 rebase 时极其有用
git config merge.conflictstyle zdiff3
git fetch upstream --tags
```

建议三层分支:

```
upstream/main ──(ff-only 镜像)──▶ main            # fork 的 main 不放任何自有提交
                                   │
                                   └─▶ spark-compat   # 长期集成分支：所有 Spark 特性提交，定期 rebase 到 main
                                          ├─ feat/spark-parser-skeleton      # 短命特性分支，PR → spark-compat
                                          ├─ feat/spark-func-alias-l1
                                          └─ fix/...
                                   release/spark-x.y  # 打包/发布用，从 spark-compat 切，cherry-pick 修复
```

- `main` 保持与上游一致：`git switch main && git merge --ff-only upstream/main && git push origin main`。注意：当前设计文档分支 `docs/spark-sql-compat-design` 的提交（`485ef510`）只应进入 `spark-compat`，不进入 `main`。
- 所有开发 PR 目标分支 = `spark-compat`（不是 `main`）。

### 6.2 同步节奏与命令

建议**每周一次**（最迟每两周）+ 每次大版本分支（上游 `branch-4.x`）切出时：

```bash
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main
git switch spark-compat
git tag -f pre-sync-$(date +%Y%m%d) spark-compat          # 回滚点
git rebase main                                           # 或：git rebase --onto upstream/main <旧 base> spark-compat
# 解决冲突 → git add → git rebase --continue
./build.sh --fe -j8 && ./run-fe-ut.sh --test 'com.starrocks.connector.parser.*'   # 同步后冒烟
git push --force-with-lease origin spark-compat
```

- **rebase vs merge**：`spark-compat` 是"补丁栈"性质，用 rebase 保持线性、便于识别我们相对上游的差异（`git log main..spark-compat`）。代价是需要 force-push，务必 `--force-with-lease`，并约定特性分支在 `spark-compat` 被 rebase 后用 `git rebase --onto` 跟进。若团队 >5 人且特性分支多，可改为"每周 `git merge main` 进 `spark-compat`"——更安全但历史有合并噪声，二选一后写入团队约定。
- 每次同步后跑一次 §6.4 的"差异体检"，并对 `spark-compat` 跑 FE 全量 UT（至少夜间）。

### 6.3 冲突热点与降冲突策略

| 文件（路径） | 为什么是热点 | 策略 |
|---|---|---|
| `fe/fe-core/src/main/java/com/starrocks/sql/parser/SqlParser.java` | 上游任何方言/限流/token 逻辑改动；`parse()`（约 L80-91）有 `if trino … else …` 分支 | 我们只做**一处**小改：委托 `SqlDialectRegistry`（见 01 §5）；其余代码全放新包 `sql/parser/dialect/`、`connector/parser/spark/`。把"重构 trino 分支"与"新增 spark"拆成两个 PR，前者可尝试上游化 |
| `fe/fe-core/src/main/java/com/starrocks/qe/SessionVariable.java`（>3000 行，上游极高频变动，且 `ci-doc-param-drift` 监控） | 所有人都往里加变量；`sql_dialect` 在 L1027/L3271 | `spark_*` 变量**集中在一个连续块**（常量与字段各一块，加 `// ---- Spark compat BEGIN/END ----` 注释），放在文件中相对稳定的位置，避免插在高频区；把 `SET spark.*` 映射逻辑放新类 `SparkConfMapper`，不进 `SessionVariable`；变量个数尽量少 |
| `gensrc/script/functions.py`（1913 行，上游持续追加函数） | 列表式追加，天然冲突 | 在 `vectorized_functions` 列表**末尾**（`]` 之前）追加独立块，id 用单独号段（经核对当前文件未使用 8 位 `19xxxxxx`；落地前再用 `python3 gensrc/script/gen_functions.py` 验证无重复，重复会 `exit(1)`）；每个提交只加一类函数，冲突时易重放 |
| `fe/fe-grammar/src/main/antlr/com/starrocks/grammar/StarRocks.g4`（及 `StarRocksLex.g4`） | 上游加语法/关键字频繁 | **设计上不修改**（Spark 用独立 vendored 语法）。若确需改（如保留字调整），单独成 PR，且每次同步后做 `mvn -pl fe-grammar,fe-parser -am install` 验证 ANTLR 无歧义 |
| `fe/fe-core/src/main/java/com/starrocks/sql/analyzer/ExpressionAnalyzer.java` | 函数解析/类型推断核心，上游常改 | 只加 `SparkFunctionRewriter` **钩子调用点**（01 §5 标 ✎），重写逻辑全在新类；钩子用 `if (policy.isSpark())` 守卫，默认路径字节码零差异 |
| `Field.java` / `Scope.java` / `QueryAnalyzer.java` / `SelectAnalyzer.java`（大小写策略接入点） | 同上 | 通过 `CaseSensitivityPolicy` 接口收口，改动限于调用点 |
| `fe/fe-core/src/main/java/com/starrocks/connector/parser/trino/*`（把公共类上移到 `common/`） | 上游若同时改 trino | 上移（`git mv`）易产生 rename/modify 冲突：**尽量晚做**，或先提 PR 到上游；rebase 时启用 `git config merge.renames true` |
| `docs/{en,zh,ja}/sql-reference/System_variable.md`、`release_notes` | 文档高频改动 | 只追加小节，且集中在 doc PR |
| `.github/workflows/*` | 上游频繁改 | **fork 不修改上游已有 workflow**，自有 CI 放新文件 `fork-*.yml` |

通用原则: **新增文件多、改动既有文件少且小**；每处触碰上游文件的地方加统一标记（如 `// SPARK-COMPAT:`），便于 `git grep SPARK-COMPAT` 审计和冲突后复核。

### 6.4 同步后的体检清单

```bash
git diff --stat main...spark-compat | tail -1                          # 总体差异规模
git diff --stat main...spark-compat -- fe/fe-core/src/main            # 触碰上游文件的清单
git grep -n "SPARK-COMPAT" -- fe gensrc be | wc -l                    # 钩子数量应缓慢增长
python3 build-support/check_repo_handbook.py
python3 build-support/check_gensrc_schema_compatibility.py --mode changed --base origin/main   # 应无改动
cd fe && mvn checkstyle:check -pl fe-core
```

### 6.5 提交/PR 约定（fork 内）

- 分支名: `feat/spark-<topic>`、`fix/spark-<topic>`、`doc/spark-<topic>`、`sync/upstream-YYYYMMDD`（同步专用）。
- 标题沿用上游前缀并加范围词，便于过滤: `[Feature] Spark dialect: translate SELECT/CTE`、`[Enhancement] Spark function alias L1: nvl2, ifnull`、`[UT] Spark golden runner skeleton`。
- 每个 PR: 一个主题；FE 改动附 FE UT；函数改动附 `func_mapping.csv` 对应行更新；有用户可见行为附 `test/sql/test_spark_dialect` case 与 `docs/` 变更；PR 描述里写"默认方言零变化"的验证方式（现有 FE UT 全绿）。
- **行为变更必须 gated**：任何会影响默认方言的修改，PR 必须说明并经评审（项目验收标准"默认方言零回归"）。
- 可上游化的通用改动（dialect 注册表、bugfix）单独分支、基于 `main`（非 `spark-compat`）开发，再 cherry-pick 回 `spark-compat`，降低日后冲突。
- 合并方式: `spark-compat` 上 squash 或 rebase-merge 均可，但**统一一种**；禁止在 `spark-compat` 上 merge commit（会破坏 rebase 流程）。

---

## 7. 两周上手清单

约定：每天 ≈ 3h 阅读 + 5h 动手；`验收` 为当天结束时应能展示的结果。时间紧可压缩，但 D1–D3 的环境验证不要跳过。

### 第 1 周：环境、构建、测试、读懂 Trino 先例

**D1 — 读 + 环境**
- 读: `AGENTS.md`、`handbook/index.md` 及 `domains/*`、`policies/*`（本文头部表格）；`../README.md`、`../01-architecture.md`。
- 做: 装 Docker；`docker pull starrocks/dev-env-ubuntu:latest`；`./docker-dev.sh shell` 进容器确认 `java -version`=17+、`gcc --version`≥12.1、`mvn -v`。
- 验收: 容器内 `echo $STARROCKS_THIRDPARTY` 有值；`git remote add upstream …` 与 `rerere` 配置完成（§6.1）。

**D2 — 构建 FE**
- 读: 本文 §2；`fe/README.md`、`fe/AGENTS.md` 前半。
- 做: `./build.sh --fe -j8`（首次会下载 Maven 依赖，较慢）；检查 `output/fe/lib/starrocks-fe.jar`；`cd fe && mvn checkstyle:check -pl fe-core`。IDEA 按 §1.6 导入 `fe/`。
- 验收: IDE 中 `SqlParser.java` 无红线，能跳转到 `AstBuilder`。

**D3 — 构建 BE + 起集群**
- 读: §3；`be/AGENTS.md` 概览。
- 做: `./build.sh --be --without-java-ext -j16`（首次约数小时 [未验证]，可周中后台跑，或改用团队共享 BE 包）；用 §3.4 脚本起 1FE+1BE；`mysql` 建库建表。
- 验收: `SHOW BACKENDS` Alive=true；`SET sql_dialect='trino'; select element_at([1,2],1);` 成功。

**D4 — 跑 FE UT 与 Trino 方言测试**
- 读: `fe/fe-core/src/test/java/com/starrocks/connector/parser/trino/TrinoTestBase.java`、`TrinoFunctionTransformTest.java`；`handbook/domains/frontend.md` Test 节。
- 做: `./run-fe-ut.sh -j4 --test 'com.starrocks.connector.parser.trino.*'`；再单跑 `TrinoDialectDowngradeTest`；故意改坏一个期望值观察失败输出（别提交）。
- 验收: 能口述 `PlanTestBase` / `UtFrameUtils.createMinStarRocksCluster` 的作用，并定位 Trino 测试里 `analyzeSuccess` 的用法。

**D5 — 跑 SQL-Tester 的 Trino 用例 + 读 Trino 实现**
- 读: `test/README.md` 全文、`test/AGENTS.md`；源码 `SqlParser.parse`、`connector/parser/trino/{TrinoParser,AstBuilder,FunctionCallTransformer,Trino2SRFunctionCallTransformer}.java`；设计 `../02-spark-dialect-parser.md`。
- 做: `cd test && pip install -r requirements.txt && python3 run.py -d sql/test_trino_dialect -v --skip_reruns`；再用 `-r` 把 `test_trino_dialect` 录到临时副本，`diff` 与原 R 文件，理解录制流程（别提交差异）。
- 验收: 能解释 T/R 文件差别，以及为什么 R 文件要人工审查。

### 第 2 周：函数、会话变量、测试脚手架、PR 全流程

**D6 — 读函数体系 + 设计**
- 读: `../03-function-adapter.md`、`func_mapping.csv` 前 50 行、`gensrc/script/functions.py` 头部注释（id 规则）、`gensrc/AGENTS.md`、`handbook/domains/generated-and-extensions.md`。
- 做: 在 `functions.py` 里读 `pi`（id 10010）→ `be/src/exprs/math_functions.{h,cpp}`（`DEFINE_VECTORIZED_FN(pi)`）→ `be/test/exprs/math_functions_test.cpp`，画出"FE 注册表 → BE 实现 → 测试"链路。
- 验收: 能说明 `functions.py` 改动后 FE/BE 各自在何时重新生成（§2.3）。

**D7 — 动手 1：添加 dummy 内置函数**
- 目标: 新增 `spark_dummy_one()`（返回 INT 1），仅本地验证，**用完丢弃分支**。
- 步骤:
  1. `gensrc/script/functions.py` 末尾追加 `[19000001, "spark_dummy_one", True, False, "INT", [], "MathFunctions::spark_dummy_one"]`（紧邻现有同类行格式；id 号段见 §6.3）。
  2. `be/src/exprs/math_functions.h` 增加 `DEFINE_VECTORIZED_FN(spark_dummy_one);`，`math_functions.cpp` 里实现（参照 `MathFunctions::pi`，L202 起）。
  3. `be/test/exprs/math_functions_test.cpp` 增加 `TEST_F(VecMathFunctionsTest, sparkDummyOneTest)`。
  4. 重新 `./build.sh --fe --be --without-java-ext -j16`，重启集群，`select spark_dummy_one();`。
  5. `./run-be-ut.sh --without-java-ext --gtest_filter 'VecMathFunctionsTest.sparkDummyOneTest'`。
- 验收: SQL 返回 1，BE 单测通过；故意写重复 id，确认 `gen_functions.py` 报 `Duplicated function id`。

**D8 — 动手 2：添加 dummy 会话变量**
- 读: `SessionVariable.java` 中 `SQL_DIALECT`（L1027）/`ENABLE_DIALECT_DOWNGRADE`（L1030）常量与 `@VarAttr` 字段（L3271–3274）；`fe/fe-core/src/test/java/com/starrocks/qe/SessionVariableTest.java`；`../05-semantics-session-catalog.md`。
- 做: 新增 `public static final String SPARK_DUMMY_FLAG = "spark_dummy_flag";` 与 `@VarAttr(name = SPARK_DUMMY_FLAG) private boolean sparkDummyFlag = false;` + getter/setter；在 `SessionVariableTest` 加断言（`SET spark_dummy_flag=true` 后可读到）；`./run-fe-ut.sh --test com.starrocks.qe.SessionVariableTest`；起集群验证 `SHOW VARIABLES LIKE 'spark_dummy%'`。
- 验收: UT 通过；体会 `ci-doc-param-drift` 会因此评论缺文档（若开 PR）。

**D9 — 动手 3：建 `SparkTestBase` 空壳 + SQL-Tester 用例**
- 读: `../06-testing-strategy.md` §2.1–2.3；`TrinoTestBase`。
- 做: 在 `fe/fe-core/src/test/java/com/starrocks/connector/parser/spark/` 新建 `SparkTestBase`（复制 Trino 版并去除 trino 特有设置）+ 一个仅断言 `sql_dialect` 默认值的 smoke test；按 §4.4 创建 `test/sql/test_spark_dialect/T/test_spark_basic` 并录制/校验（此时 `sql_dialect='spark'` 还不会被识别，观察当前行为——预期为回落默认解析或报错，记录结果）。
- 验收: 新增测试 FE UT 通过；R 文件已人工审查。

**D10 — Fork 流程演练 + 文档规范**
- 读: §5、§6；`docs/AGENTS.md`；`CONTRIBUTING.md`；`.github/PULL_REQUEST_TEMPLATE.md`。
- 做: `git fetch upstream` 后在 `spark-compat` 上演练一次 rebase（可用 D7/D8 的临时分支，故意制造 `functions.py` 末尾冲突并解决）；用 PR 模板向 fork 的 `spark-compat` 开一个 `[Tool]` 小 PR（比如本文勘误），走一遍标题检查与模板；跑 `python3 build-support/check_repo_handbook.py`。
- 验收: PR 标题检查通过；能说清 fork 上哪些 CI 会跑、哪些不会（§5.4）。

**第 2 周结束评审**（与 owner 15 分钟）：
- 能独立：构建、起集群、跑任意单个 FE/BE/SQL 测试；
- 能回答："改 `functions.py` 后要重编什么？""默认方言零变化如何证明？""哪些文件是冲突热点、怎么降风险？"；
- 领取第一个真实任务（建议从 `func_mapping.csv` 的 C 类别名 L1 或 `SparkFunctionAliasRegistry` 开始，风险低、可快速闭环）。

---

## 附录 A：速查表

```bash
# 构建
./build.sh --fe -j8 | ./build.sh --be --without-java-ext -j16 | BUILD_TYPE=Debug ./build.sh --be
./build-in-docker.sh --fe --be --clean | ./docker-dev.sh shell
# 测试
./run-fe-ut.sh --test <FQCN[#method]> [-j N]
./run-be-ut.sh --without-java-ext --gtest_filter 'Suite.Case'      # --test 是前缀匹配(NAME*)
./run-java-exts-ut.sh
cd test && python3 run.py -d sql/<suite>[/T|R/<file>] [-r|-v] --skip_reruns [--case_filter RE]
# 风格
cd fe && mvn checkstyle:check -pl fe-core
build-support/clang-format-changed-check.sh
python3 build-support/check_repo_handbook.py
# 集群
output/fe/bin/start_fe.sh --daemon ; output/be/bin/start_be.sh --daemon
mysql -h127.0.0.1 -P9030 -uroot -e 'ALTER SYSTEM ADD BACKEND "127.0.0.1:9050"'
```

## 附录 B：本文发现的文档/脚本不一致（建议后续修正或知悉）

1. `env.sh` JDK 下限检查为 11，而 FE 实际 `release=17`、`start_fe.sh` 下限 17（§1.2）。
2. `test/README.md`、`test/AGENTS.md` 的 `sr.conf` 示例用 `[mysql-client]` 段，实际 `test/conf/sr.conf` 为 `[cluster]` 段（§4.4）。
3. `be/CMakeLists.txt:114` 的 `CMAKE_EXPORT_COMPILECOMMANDS` 疑似拼写错误；实际依赖 `build.sh`/`run-be-ut.sh` 的命令行 `-D`（§1.6）。
4. `run-fe-ut.sh --coverage` 依赖 `ant cover-test`，但 `fe/` 下无 `build.xml`（§4.1）。
5. `CONTRIBUTING.md` 的 "code structure / coding style / unit test / best practices" 均为 TBC，实际规则分散在 `AGENTS.md` 体系中。
6. 本仓库 git 历史仅 2 个提交（浅克隆/导出），无法据 `git log` 统计各热点文件的上游改动频率；热点判断基于文件规模与职责 [未验证 频率]。
