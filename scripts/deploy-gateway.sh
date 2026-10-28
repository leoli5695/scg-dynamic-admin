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

# The uid:gid the container actually runs as, so nothing here has to hardcode
# the `www` account name that happens to exist on this host today.
container_ids() {
  local runas; runas=$(docker inspect -f '{{.Config.User}}' "$(container_for "$1")")
  local uid=${runas%%:*} gid=${runas##*:}
  [ -n "$uid" ] || { uid=0; gid=0; }
  echo "$uid $gid"
}

# A jar that writes under ${user.dir} at startup - the quota ledger and usage
# outbox default to ${user.dir}/data/... - takes the whole Spring context down
# if it cannot create those directories. When the module dir is owned by the app
# user it can create them itself; when it is root-owned (which is what keeps the
# root:600 env file out of the app user's reach) the data dir has to already
# exist and be owned by the app user. Deploying without this check turns into a
# 120s health timeout and a rollback, with nothing saying why.
preflight_data_dir() {
  local mod=$1 dir; dir=$(dir_for "$mod")
  local uid gid
  read -r uid gid <<<"$(container_ids "$mod")"
  if [ "$(stat -c %u "$dir")" = "$uid" ]; then
    return 0
  fi
  if [ ! -d "$dir/data" ]; then
    log "[$mod] preflight: $dir is not writable by uid $uid, creating $dir/data"
    install -d -o "$uid" -g "$gid" -m 755 "$dir/data"
  elif [ "$(stat -c %u "$dir/data")" != "$uid" ]; then
    die "[$mod] preflight: $dir/data is owned by $(stat -c '%u:%g' "$dir/data") but the container runs as $uid:$gid"
  fi
}

# `docker restart` keeps the same container, so the failed start's output is
# still in its log. Print the part that explains the failure before rolling
# back - otherwise a health timeout tells the operator nothing.
dump_failure_logs() {
  local mod=$1 ctr; ctr=$(container_for "$mod")
  local hits
  hits=$(docker logs --since "$((WAIT_S + 60))s" "$ctr" 2>&1 \
    | grep -E "APPLICATION FAILED TO START|Caused by:|Error creating bean|AccessDeniedException|UnsatisfiedDependency|BeanCreationException|Description:|Action:" \
    | grep -viE "nacos|NotifyCenter" | tail -25)
  log "[$mod] --- failure diagnostics from $ctr ---"
  if [ -n "$hits" ]; then
    printf '%s\n' "$hits" | sed "s/^/[$mod]   /"
  else
    docker logs --tail 40 "$ctr" 2>&1 | sed "s/^/[$mod]   /"
  fi
}

exec 9>"$LOCK"
flock -n 9 || die "another deploy is running"

kind=${1:-}; shift || true

deploy_jar() {
  local mod=$1 src=$2
  local dir; dir=$(dir_for "$mod")
  local jar=$dir/app.jar
  local ctr; ctr=$(container_for "$mod")
  local bak uid gid
  [ -f "$src" ] || die "source jar not found: $src"
  [ "$(head -c2 "$src")" = "PK" ] || die "$src is not a zip/jar"
  [ -f "$jar" ] || die "no deployed jar at $jar - wrong module name?"
  preflight_data_dir "$mod"
  bak=$jar.bak-$(date +%Y%m%d-%H%M%S)

  log "[$mod] backup $jar -> $bak"
  cp -p "$jar" "$bak"

  log "[$mod] install $(basename "$src") ($(stat -c %s "$src") bytes)"
  read -r uid gid <<<"$(container_ids "$mod")"
  install -o "$uid" -g "$gid" -m 644 "$src" "$jar"

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
  [ "$code" = "200" ] || { dump_failure_logs "$mod"; rollback_jar "$mod" "$bak"; die "[$mod] health never returned 200"; }

  status=$(docker inspect -f '{{.State.Status}}' "$ctr")
  started_epoch=$(date -d "$(docker inspect -f '{{.State.StartedAt}}' "$ctr")" +%s)
  [ "$status" = running ] || { dump_failure_logs "$mod"; rollback_jar "$mod" "$bak"; die "[$mod] container is $status"; }

  # The assertion that was missing from every previous deploy.
  if [ "$started_epoch" -le "$jar_epoch" ]; then
    rollback_jar "$mod" "$bak"
    die "[$mod] JVM started at $started_epoch but jar landed at $jar_epoch - old bytecode would be running"
  fi

  log "[$mod] OK jar=$(date -d @$jar_epoch '+%T') jvm=$(date -d @$started_epoch '+%T') health=200"
}

rollback_jar() {
  local mod=$1 bak=$2 dir; dir=$(dir_for "$mod")
  local ctr; ctr=$(container_for "$mod")
  local uid gid code i
  log "[$mod] ROLLBACK to $bak"
  read -r uid gid <<<"$(container_ids "$mod")"
  install -o "$uid" -g "$gid" -m 644 "$bak" "$dir/app.jar"
  docker restart "$ctr" >/dev/null || true
  # Poll rather than sleep a fixed 10s: a cold start takes ~20s here, so the old
  # version printed health=000 after a rollback that had actually worked and sent
  # the operator chasing a second outage that did not exist.
  code=000
  for i in $(seq 1 $((WAIT_S / 3))); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$(health_url "$mod")" || true)
    [ "$code" = "200" ] && break
    sleep 3
  done
  log "[$mod] rolled back, health=$code"
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
