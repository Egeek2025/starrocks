#!/usr/bin/env bash
# Single-node StarRocks (official all-in-one image) for the glbk UDF e2e harness.
#   ./cluster.sh start|stop|status|logs|mysql [sql]
# Env: SR_IMAGE (default starrocks/allin1-ubuntu:4.0.17), SR_CONTAINER (default starrocks-e2e),
#      BE_TZ (default Asia/Shanghai), BE_LOCALE_OPTS (extra JVM -D opts, e.g. "-Duser.language=en -Duser.country=US")
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$HERE/.run"; mkdir -p "$RUN"
SR_IMAGE="${SR_IMAGE:-starrocks/allin1-ubuntu:4.0.17}"
SR_CONTAINER="${SR_CONTAINER:-starrocks-e2e}"
BE_TZ="${BE_TZ:-Asia/Shanghai}"
BE_LOCALE_OPTS="${BE_LOCALE_OPTS:-}"
SRHOME=/data/deploy/starrocks
SUP="supervisorctl -c $SRHOME/supervisor/supervisord.conf"

log() { echo "[cluster] $*" >&2; }

ensure_dockerd() {
  if docker info >/dev/null 2>&1; then return; fi
  log "docker daemon not running, starting dockerd (no iptables/bridge: container uses --network host)"
  # dockerd pulls images through the agent proxy: it inherits HTTPS_PROXY and SSL_CERT_FILE from this shell.
  : "${SSL_CERT_FILE:=/root/.ccr/ca-bundle.crt}"; export SSL_CERT_FILE
  nohup dockerd --iptables=false --bridge=none >"$RUN/dockerd.log" 2>&1 &
  echo $! >"$RUN/dockerd.pid"
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && return; sleep 1; done
  log "dockerd did not come up, see $RUN/dockerd.log"; exit 1
}

mysql_in() { docker exec -i "$SR_CONTAINER" mysql -h127.0.0.1 -P9030 -uroot "$@"; }

patch_be_conf() {
  # 1) JVM time zone (+ optional locale) for the BE-embedded JVM that runs Java UDFs.
  # 2) The sandbox hard limit is nofile=20000 (cannot pass --ulimit nofile=60000+), so lower BE's fd check.
  docker exec "$SR_CONTAINER" bash -c "
    set -e; f=$SRHOME/be/conf/be.conf
    grep -q 'Duser.timezone' \$f || sed -i 's|^JAVA_OPTS=\"|JAVA_OPTS=\"-Duser.timezone=$BE_TZ $BE_LOCALE_OPTS |' \$f
    grep -q '^min_file_descriptor_number' \$f || echo 'min_file_descriptor_number = 1024' >> \$f
    grep -E '^(JAVA_OPTS|min_file_descriptor_number)' \$f"
}

wait_ready() {
  for i in $(seq 1 120); do
    if mysql_in -N -e 'show backends' 2>/dev/null | grep -q -w true; then log "FE+BE ready"; return 0; fi
    sleep 2
  done
  log "cluster not ready after 240s"; exit 1
}

start() {
  ensure_dockerd
  docker image inspect "$SR_IMAGE" >/dev/null 2>&1 || { log "pulling $SR_IMAGE"; docker pull "$SR_IMAGE"; }
  if docker inspect "$SR_CONTAINER" >/dev/null 2>&1; then
    [ "$(docker inspect -f '{{.State.Running}}' "$SR_CONTAINER")" = true ] || docker start "$SR_CONTAINER" >/dev/null
  else
    docker run -d --name "$SR_CONTAINER" --network host \
      -v "${SR_CONTAINER}-fe-meta:$SRHOME/fe/meta" -v "${SR_CONTAINER}-be-storage:$SRHOME/be/storage" \
      "$SR_IMAGE" >/dev/null
  fi
  # wait for supervisord, then patch BE conf and (re)start BE (it FATALs on the first start because of the fd check)
  for _ in $(seq 1 30); do docker exec "$SR_CONTAINER" $SUP status >/dev/null 2>&1 && break; sleep 1; done
  sleep 10
  patch_be_conf
  docker exec "$SR_CONTAINER" $SUP restart beservice || true
  # enable_udf is NOT mutable at runtime (4.0); the image's fe.conf already has enable_udf = true. Verify it.
  for _ in $(seq 1 30); do mysql_in -e 'select 1' >/dev/null 2>&1 && break; sleep 2; done
  mysql_in -N -e "ADMIN SHOW FRONTEND CONFIG LIKE 'enable_udf'" | grep -q true \
    || { log "enable_udf is not true: add 'enable_udf = true' to $SRHOME/fe/conf/fe.conf in the container and restart feservice"; exit 1; }
  wait_ready
  status
}

status() {
  docker inspect -f 'container {{.Name}} running={{.State.Running}}' "$SR_CONTAINER" 2>&1 || true
  mysql_in -e 'select current_version(); show frontends\G' 2>/dev/null | grep -E 'current_version|4\.|^ *(IP|QueryPort|Alive):' || true
  echo "connect: mysql -h127.0.0.1 -P9030 -uroot   (FE HTTP 8030, BE HTTP 8040)"
}

case "${1:-}" in
  start) start ;;
  stop) docker stop "$SR_CONTAINER" ;;
  rm) docker rm -f "$SR_CONTAINER"; docker volume rm "${SR_CONTAINER}-fe-meta" "${SR_CONTAINER}-be-storage" || true ;;
  status) status ;;
  logs) docker exec "$SR_CONTAINER" bash -c "tail -n ${2:-80} $SRHOME/be/log/be.out $SRHOME/be/log/be.INFO $SRHOME/fe/log/fe.log" ;;
  mysql) shift; mysql_in "$@" ;;
  *) echo "usage: $0 start|stop|rm|status|logs [n]|mysql [args]"; exit 2 ;;
esac
