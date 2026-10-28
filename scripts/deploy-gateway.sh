#!/bin/bash
# Deploy a jar or the console dist to this host, and PROVE the new code is what
# is running afterwards.
#
#   deploy-gateway.sh jar admin|gateway <path-to-new-jar>
#   deploy-gateway.sh ui  <path-to-new-dist-dir>
#
# Why this exists: the jars are bind-mounted into the containers, so swapping
# app.jar without restarting the container leaves the old bytecode in the JVM
# and the deploy looks successful. Every previous deploy did the restart by
# hand; nothing verified it happened. This script makes the restart mandatory
# and asserts, after the fact, that the JVM started strictly after the jar hit
# the disk - and rolls back if the new jar fails its health check.
#
# Jar-only swaps use `docker restart`; env changes still need the per-module
# run.sh, which recreates the container (see the note in run.sh).
set -eu

LOCK=/var/lock/deploy-gateway.lock
WAIT_S=120

health_url() {
  case $1 in
    admin)   echo http://127.0.0.1:9090/actuator/health ;;
    gateway) echo http://127.0.0.1:8080/actuator/health ;;
  esac
}

# Module aliases are short; the on-disk dirs and container names are not.
dir_for() {
  case $1 in
    admin)   echo /opt/gateway-admin ;;
    gateway) echo /opt/my-gateway ;;
  esac
}

container_for() {
  case $1 in
    admin)   echo gateway-admin ;;
    gateway) echo my-gateway ;;
  esac
}

log() { echo "$(date '+%F %T') $*"; }
die() { log "ABORT: $*" >&2; exit 1; }

exec 9>"$LOCK"
flock -n 9 || die "another deploy is running"

kind=${1:-}; shift || true

deploy_jar() {
  local mod=$1 src=$2
  local dir; dir=$(dir_for "$mod")
  local jar=$dir/app.jar
  local ctr; ctr=$(container_for "$mod")
  local bak
  [ -f "$src" ] || die "source jar not found: $src"
  [ "$(head -c2 "$src")" = "PK" ] || die "$src is not a zip/jar"
  [ -f "$jar" ] || die "no deployed jar at $jar - wrong module name?"
  bak=$jar.bak-$(date +%Y%m%d-%H%M%S)

  log "[$mod] backup $jar -> $bak"
  cp -p "$jar" "$bak"

  log "[$mod] install $(basename "$src") ($(stat -c %s "$src") bytes)"
  install -o www -g www -m 644 "$src" "$jar"

  log "[$mod] restart container $ctr"
  docker restart "$ctr" >/dev/null

  local jar_epoch started_epoch status i code
  jar_epoch=$(stat -c %Y "$jar")

  log "[$mod] wait for health $(health_url "$mod") (up to ${WAIT_S}s)"
  for i in $(seq 1 $((WAIT_S / 3))); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$(health_url "$mod")" || true)
    [ "$code" = "200" ] && break
    sleep 3
  done
  [ "$code" = "200" ] || { rollback_jar "$mod" "$bak"; die "[$mod] health never returned 200"; }

  status=$(docker inspect -f '{{.State.Status}}' "$ctr")
  started_epoch=$(date -d "$(docker inspect -f '{{.State.StartedAt}}' "$ctr")" +%s)
  [ "$status" = running ] || { rollback_jar "$mod" "$bak"; die "[$mod] container is $status"; }

  # The assertion that was missing from every previous deploy.
  if [ "$started_epoch" -le "$jar_epoch" ]; then
    rollback_jar "$mod" "$bak"
    die "[$mod] JVM started at $started_epoch but jar landed at $jar_epoch - old bytecode would be running"
  fi

  log "[$mod] OK jar=$(date -d @$jar_epoch '+%T') jvm=$(date -d @$started_epoch '+%T') health=200"
}

rollback_jar() {
  local mod=$1 bak=$2
  log "[$mod] ROLLBACK to $bak"
  install -o www -g www -m 644 "$bak" "$(dir_for "$mod")/app.jar"
  docker restart "$(container_for "$mod")" >/dev/null || true
  sleep 10
  log "[$mod] rolled back, health=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$(health_url "$mod")" || true)"
}

deploy_ui() {
  local src=$1 base=/opt/gateway-ui
  [ -d "$src" ] && [ -f "$src/index.html" ] || die "source dist lacks index.html: $src"
  local ts; ts=$(date +%Y%m%d-%H%M%S)

  log "[ui] stage new dist"
  rm -rf "$base/dist.new-$ts"
  cp -r "$src" "$base/dist.new-$ts"

  log "[ui] swap dist (previous kept as dist.old)"
  rm -rf "$base/dist.old"
  mv "$base/dist" "$base/dist.old"
  mv "$base/dist.new-$ts" "$base/dist"

  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:80/ || true)
  if [ "$code" != "200" ]; then
    log "[ui] ROLLBACK"
    rm -rf "$base/dist"
    mv "$base/dist.old" "$base/dist"
    die "[ui] nginx returned $code after swap"
  fi
  log "[ui] OK nginx=200"
}

case "$kind" in
  jar) [ $# -eq 2 ] || die "usage: deploy-gateway.sh jar admin|gateway <jar>"; deploy_jar "$1" "$2" ;;
  ui)  [ $# -eq 1 ] || die "usage: deploy-gateway.sh ui <dist-dir>";        deploy_ui  "$1" ;;
  *)   die "usage: deploy-gateway.sh jar admin|gateway <jar> | ui <dist-dir>" ;;
esac
log "DEPLOY DONE"
