#!/usr/bin/env bash
# Differential test of the native C++ glbk date core against the Java reference implementation.
#   ./build_and_test.sh            full oracle (~2.5M lines), plain and ASAN/UBSAN builds
#   QUICK=1 ./build_and_test.sh    sparse oracle (~230k lines) for a fast smoke run
#   BENCH=1 ./build_and_test.sh    also build and run bench.cpp
# Exit status is non-zero on any mismatch. Needs: JDK 8+, Maven (or a pre-built target/classes), g++ with C++23.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(cd "$HERE/.." && pwd)"
SRC_ROOT="$(cd "$HERE/../../.." && pwd)/be/src"
CORE="$SRC_ROOT/exprs/glbk_date_core.cpp"
BUILD="$HERE/build"
CXX="${CXX:-g++}"
CXXFLAGS_COMMON=(-std=gnu++23 -Wall -Wextra -I"$SRC_ROOT")
mkdir -p "$BUILD"

echo "== 1. generate oracle.tsv from the Java reference"
cd "$MODULE"
GEN_ARGS=("$HERE/oracle.tsv")
[[ "${QUICK:-0}" == "1" ]] && GEN_ARGS+=(--quick)
if mvn -B -q test-compile; then
    CP="target/classes:target/test-classes"
else
    # e.g. no network for the test dependencies: the generator only needs the main classes
    echo "mvn test-compile failed; falling back to 'mvn compile' + javac of the generator" >&2
    mvn -B -q compile
    mkdir -p "$BUILD/gen"
    javac -nowarn -cp target/classes -d "$BUILD/gen" src/test/java/com/glbk/starrocks/udf/NativeOracleGenerator.java
    CP="target/classes:$BUILD/gen"
fi
time java -cp "$CP" com.glbk.starrocks.udf.NativeOracleGenerator "${GEN_ARGS[@]}"

echo "== 2. build (-O2) and run"
$CXX "${CXXFLAGS_COMMON[@]}" -O2 -Werror "$HERE/oracle_test.cpp" "$CORE" -o "$HERE/oracle_test"
"$HERE/oracle_test" "$HERE/oracle.tsv"

echo "== 3. build (ASAN + UBSAN) and run"
$CXX "${CXXFLAGS_COMMON[@]}" -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
    "$HERE/oracle_test.cpp" "$CORE" -o "$HERE/oracle_test_asan"
"$HERE/oracle_test_asan" "$HERE/oracle.tsv"

if [[ "${BENCH:-0}" == "1" ]]; then
    echo "== 4. benchmark"
    $CXX "${CXXFLAGS_COMMON[@]}" -O2 "$HERE/bench.cpp" "$CORE" -o "$HERE/bench"
    "$HERE/bench"
fi
echo "OK: native implementation matches the Java reference"
