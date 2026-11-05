# glbk 日期函数 C++ 原生实现：差分测试

`be/src/exprs/glbk_date_core.{h,cpp}` 是 `GlDates` / `DateFormats`（Java 正式版）的 C++ 移植，只依赖标准库。
本目录用 Java 实现当“标准答案”，逐行比对 C++ 的结果（值、NULL、报错三种结局都要一致）。

| 文件 | 作用 |
|---|---|
| `../src/test/java/com/glbk/starrocks/udf/NativeOracleGenerator.java` | 调用 Java 实现，生成 `oracle.tsv`（约 250 万行，几秒） |
| `oracle_test.cpp` | 读 `oracle.tsv`，调用 C++ 入口函数比对；有差异时打印前 N 条并以非 0 退出 |
| `bench.cpp` | 每次调用的耗时（ns）：预编译的 `Pattern`/`Offsets` 对比整函数入口 |
| `build_and_test.sh` | 一键：生成 oracle，普通 `-O2` 编译并运行，再用 ASAN+UBSAN 编译并运行 |

```bash
cd contrib/glbk-hive-udf-compat/native
./build_and_test.sh              # 完整
QUICK=1 ./build_and_test.sh      # 冒烟（约 23 万行）
BENCH=1 ./build_and_test.sh      # 额外跑 bench
```

## oracle.tsv 格式

每行用制表符分隔：`函数名, 参数个数, 参数1..N, 期望结果`。
- 参数：`\N` 表示 SQL NULL；否则是文本，`\\ \t \n \r` 转义，其它控制字符写成 `\xHH`，非 ASCII 为原始 UTF-8。
- 期望结果：`N` = NULL，`E` = 报错（`IllegalArgumentException` 对应 C++ 的 `GlParamError`），`V<值>` = 返回值。

## 覆盖范围

所有函数 x 参数个数 0..6；闰年、1/9999 年边界、非法日期、各种分隔符和全角/阿拉伯数字；offsets（加减、设置、合并解析、
int 溢出、空白、尾随逗号、各种非法写法）；`SimpleDateFormat` 每个字母 x 个数 1..6、引号、非法字母、超长重复；
dtype/interval 的大小写、空白、NULL；全部 `samples/samples.csv`（除 `gl_now`）；周相关字母逐日覆盖若干年份；
固定种子的随机日期、offsets 和格式串。

## 注意

- `oracle.tsv`、测试程序和 `build/` 不入库（见 `.gitignore`）。
- 生成器按 Java 8 语法编写（pom 里 `release 8`）。无法联网下载测试依赖时，脚本会退回到 `mvn compile` + `javac`。
- `gl_now` 依赖 BE 的 JVM 时区，不做原生实现，也不在这里测试。
