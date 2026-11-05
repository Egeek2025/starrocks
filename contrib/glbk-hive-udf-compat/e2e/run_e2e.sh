#!/usr/bin/env bash
# End-to-end test of the glbk UDF jar on a real StarRocks cluster.
#   ./run_e2e.sh [csv] [options]
#   csv                       default ../samples/samples.csv (relative to this dir); needs columns `sql` and the expected column
#   --expected-col NAME       default new_result          (markers: NULL = SQL NULL, ERROR = query must fail)
#   --sql-col NAME            default sql
#   --register-sql FILE       default ../sql/register.sql
#   --setup-sql FILE          extra SQL run after registration, before the checks (repeatable); default smoke_setup.sql only for smoke.csv
#   --skip-blank-expected     skip rows whose expected cell is empty
#   --set VAR=VALUE           session variable for the checks (repeatable), e.g. --set enable_column_expr_predicate=false
#   --no-build                use existing target/*.jar          (--jar FILE: use that jar)
#   --no-register             only run the checks
#   -v                        print passing rows too
# Env: SR_HOST(127.0.0.1) SR_PORT(9030) SR_USER(root) SR_PASSWORD
#      JAR_HOST (address the FE/BE use to reach this machine's HTTP server; default 127.0.0.1 = docker --network host)
#      HTTP_PORT (first port tried for the jar server, default 18088)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD="$(cd "$HERE/.." && pwd)"
RUN="$HERE/.run"; SERVE="$RUN/serve"; mkdir -p "$SERVE"

CSV=""; EXPECTED=""; SQLCOL=sql; REG="$MOD/sql/register.sql"; BUILD=1; REGISTER=1; JAR=""; EXTRA=(); SETUPS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --expected-col) EXPECTED="$2"; shift 2 ;;
    --sql-col) SQLCOL="$2"; shift 2 ;;
    --register-sql) REG="$2"; shift 2 ;;
    --setup-sql) SETUPS+=("$2"); shift 2 ;;
    --skip-blank-expected) EXTRA+=(--skip-blank-expected); shift ;;
    --set) EXTRA+=(--set "$2"); shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --no-register) REGISTER=0; shift ;;
    --jar) JAR="$2"; BUILD=0; shift 2 ;;
    -v) EXTRA+=(-v); shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) CSV="$1"; shift ;;
  esac
done
CSV="${CSV:-$MOD/samples/samples.csv}"
if [ -z "$EXPECTED" ]; then if [ "$(basename "$CSV")" = smoke.csv ]; then EXPECTED=expected; else EXPECTED=new_result; fi; fi
[ -f "$CSV" ] || { echo "csv not found: $CSV" >&2; exit 2; }
CSV="$(cd "$(dirname "$CSV")" && pwd)/$(basename "$CSV")"
# the table used by smoke.csv is created by smoke_setup.sql
if [ "$(basename "$CSV")" = smoke.csv ] && [ ${#SETUPS[@]} -eq 0 ]; then SETUPS=("$HERE/smoke_setup.sql"); fi

PY=python3
$PY -c 'import pymysql' 2>/dev/null || { echo "installing pymysql" >&2; $PY -m pip install -q pymysql; }

if [ "$BUILD" = 1 ]; then
  echo "== building jar" >&2
  (cd "$MOD" && mvn -B -q package -DskipTests)
fi
if [ -z "$JAR" ]; then JAR="$(ls -t "$MOD"/target/glbk-hive-udf-compat-*.jar | grep -v -e -sources -e -javadoc | head -1)"; fi
[ -f "$JAR" ] || { echo "jar not found" >&2; exit 2; }
MD5="$(md5sum "$JAR" | cut -d' ' -f1)"
# unique file name per content: BE/FE cache UDF jars by URL, so a rebuilt jar must get a new URL
JARNAME="$(basename "${JAR%.jar}")-${MD5:0:8}.jar"
cp -f "$JAR" "$SERVE/$JARNAME"
echo "== jar $JAR md5=$MD5 -> $JARNAME" >&2

# HTTP server (kept running after the script; FE/BE re-download the jar when needed)
port_ok() { curl -fsS -o /dev/null "http://127.0.0.1:$1/$JARNAME" 2>/dev/null; }
HTTP_PORT="${HTTP_PORT:-18088}"
if [ -f "$RUN/http.port" ] && port_ok "$(cat "$RUN/http.port")"; then
  HTTP_PORT="$(cat "$RUN/http.port")"
else
  while (exec 3<>"/dev/tcp/127.0.0.1/$HTTP_PORT") 2>/dev/null; do HTTP_PORT=$((HTTP_PORT+1)); done
  nohup $PY -m http.server "$HTTP_PORT" --bind 0.0.0.0 --directory "$SERVE" >"$RUN/http.log" 2>&1 &
  echo $! >"$RUN/http.pid"; echo "$HTTP_PORT" >"$RUN/http.port"
  for _ in $(seq 1 20); do port_ok "$HTTP_PORT" && break; sleep 0.5; done
  port_ok "$HTTP_PORT" || { echo "http server failed, see $RUN/http.log" >&2; exit 1; }
fi
JAR_URL="http://${JAR_HOST:-127.0.0.1}:$HTTP_PORT/$JARNAME"
echo "== jar url $JAR_URL" >&2

ARGS=(--host "${SR_HOST:-127.0.0.1}" --port "${SR_PORT:-9030}" --user "${SR_USER:-root}" --password "${SR_PASSWORD:-}"
      --csv "$CSV" --expected-col "$EXPECTED" --sql-col "$SQLCOL" "${EXTRA[@]}")
if [ "$REGISTER" = 1 ]; then ARGS+=(--register-sql "$REG" --jar-url "$JAR_URL" --md5 "$MD5"); fi
for s in "${SETUPS[@]}"; do ARGS+=(--setup-sql "$s"); done
exec $PY "$HERE/e2e_runner.py" "${ARGS[@]}"
