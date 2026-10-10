#!/usr/bin/env bash
# Run one harness tier across a concurrency ladder, snapshotting gateway JVM
# metrics before and after each step.
#
# usage: run-tier.sh <LABEL> <BASE_URL> <PATH> <CONCURRENCY> [CONCURRENCY...]
#
# Profile and window come from the environment so every tier is measured under
# an identical nominal load - the whole point is that differences between tiers
# are attributable to the tier, not to the knobs:
#   TOKENS TTFT TPOT HOLD_MS WARMUP_MS RAMP_MS TIMEOUT_MS MODEL HEADERS
#
# NOTE: pass 127.0.0.1, never "localhost". On this box localhost resolves to
# ::1 first and the gateway listens on IPv4 only, which shows up as a wall of
# ENOTFOUND / UND_ERR_CONNECT_TIMEOUT that looks exactly like a gateway failure.
set -uo pipefail

LABEL="${1:?label required}"
BASE_URL="${2:?base url required}"
REQ_PATH="${3:?request path required}"
shift 3
LEVELS=("$@")
[ ${#LEVELS[@]} -gt 0 ] || { echo "at least one concurrency level required"; exit 1; }

TOKENS="${TOKENS:-64}"
TTFT="${TTFT:-200}"
TPOT="${TPOT:-25}"
HOLD_MS="${HOLD_MS:-12000}"
WARMUP_MS="${WARMUP_MS:-4000}"
RAMP_MS="${RAMP_MS:-2000}"
TIMEOUT_MS="${TIMEOUT_MS:-30000}"
MODEL="${MODEL:-mock-model}"
HEADERS="${HEADERS:-}"
OUTDIR="${OUTDIR:-results}"

mkdir -p "$OUTDIR"

# Build identity. Two tiers are only comparable if the gateway binary, its start
# time and the repo revision are identical - a concurrent session rebuilding or
# restarting the gateway mid-ladder silently invalidates the comparison, and the
# numbers still look plausible. Capture it so the report can refuse to compare.
GATEWAY_METRICS_URL="${GATEWAY_METRICS_URL:-http://127.0.0.1/actuator/prometheus}"
node -e '
const { execSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const out = process.argv[1];
const metricsUrl = process.argv[2];
const jar = process.argv[3] || "";
const sh = (cmd) => { try { return execSync(cmd, { encoding: "utf8" }).trim(); } catch { return null; } };
(async () => {
  let gatewayStart = null;
  try {
    const text = await (await fetch(metricsUrl)).text();
    const m = text.match(/^process_start_time_seconds\{[^}]*\}\s+([0-9.E+]+)/m);
    if (m) gatewayStart = new Date(Number.parseFloat(m[1]) * 1000).toISOString();
  } catch { /* gateway unreachable - recorded as null, which is itself a red flag */ }
  fs.writeFileSync(out, JSON.stringify({
    capturedAt: new Date().toISOString(),
    tierTag: process.argv[4] || null,
    gitSha: sh("git rev-parse HEAD"),
    gitDirtyFiles: Number(sh("git status --porcelain | grep -c .") || 0),
    gatewayJar: jar || null,
    gatewayJarMtime: jar && fs.existsSync(jar) ? fs.statSync(jar).mtime.toISOString() : null,
    gatewayProcessStart: gatewayStart,
    host: { platform: os.platform(), release: os.release(), cpus: os.cpus().length, totalMemMb: Math.round(os.totalmem() / 1048576) },
    node: process.version,
  }, null, 2));
})();
' "${OUTDIR}/${LABEL}.fingerprint.json" "$GATEWAY_METRICS_URL" "${GATEWAY_JAR:-../../my-gateway/target/my-gateway-1.0.0.jar}" "${TIER_TAG:-}"
echo "fingerprint: $(node -e 'const j=require("./'"$OUTDIR"'/'"$LABEL"'.fingerprint.json");console.log("sha="+String(j.gitSha).slice(0,7),"dirty="+j.gitDirtyFiles,"gwStart="+j.gatewayProcessStart,"jar="+j.gatewayJarMtime)')"

header_args=()
if [ -n "$HEADERS" ]; then
  IFS=';' read -ra pairs <<< "$HEADERS"
  for pair in "${pairs[@]}"; do header_args+=(--header "$pair"); done
fi

digest() {
  local level="$1"
  node -e '
const fs = require("fs");
const level = process.argv[1];
const read = (p) => JSON.parse(fs.readFileSync(p, "utf8"));
const j = read(`'"$OUTDIR"'/'"$LABEL"'-c${level}.summary.json`);
const beforeSnap = read(`'"$OUTDIR"'/'"$LABEL"'-c${level}.before.json`);
const afterSnap = read(`'"$OUTDIR"'/'"$LABEL"'-c${level}.after.json`);
const b = beforeSnap.jvm;
const a = afterSnap.jvm;
if (beforeSnap.gatewayProcessStartSec !== afterSnap.gatewayProcessStartSec) {
  console.log(`        !!! GATEWAY RESTARTED MID-TIER (${beforeSnap.gatewayProcessStartSec} -> ${afterSnap.gatewayProcessStartSec}) - THIS LEVEL IS INVALID`);
}
const mb = (x) => (x / 1048576).toFixed(1);
const f = (o, k) => (o && o[k] !== undefined && o[k] !== null ? o[k] : "-");
const c = j.concurrency;
console.log(
  `${j.label} c=${String(c.requested).padStart(4)} inFlight=${String(c.avgInFlight).padStart(6)}` +
  ` ok=${String(c.completed).padStart(5)} fail=${String(c.failed).padStart(4)} errRate=${c.errorRate}` +
  ` drainAbort=${c.shutdownAborts} trunc=${j.streamIntegrity.truncatedStreams}` +
  ` | tok/s=${String(j.throughput.tokensPerSec).padStart(7)} streams/s=${j.throughput.streamsPerSec}`);
console.log(
  `        TTFTexc p50=${f(j.ttftExcessOverNominalMs, "p50")} p99=${f(j.ttftExcessOverNominalMs, "p99")}` +
  ` | intExc p50=${f(j.intervalExcessOverNominalMs, "p50")} p99=${f(j.intervalExcessOverNominalMs, "p99")}` +
  ` p999=${f(j.intervalExcessOverNominalMs, "p999")}`);
console.log(
  `        heap ${mb(b.heapUsedBytes)}->${mb(a.heapUsedBytes)}MB` +
  ` direct ${mb(b.directBufferBytes)}->${mb(a.directBufferBytes)}MB (bufs ${b.directBufferCount}->${a.directBufferCount})` +
  ` threads ${b.threadsLive}->${a.threadsLive}` +
  ` GC +${a.gcPauseCount - b.gcPauseCount} pauses/${(a.gcPauseSumSec - b.gcPauseSumSec).toFixed(3)}s max=${a.gcPauseMaxSec.toFixed(3)}s` +
  ` alloc +${mb(a.gcAllocatedBytesTotal - b.gcAllocatedBytesTotal)}MB` +
  ` | clientLag p99=${j.clientHealth.eventLoopLagMs.p99} profileOK=${j.profileCheck.matches}`);
const errs = Object.entries(c.errors || {});
if (errs.length) console.log(`        errors: ${errs.map(([k, v]) => `${k}=${v}`).join(" ")}`);
' "$level"
}

for level in "${LEVELS[@]}"; do
  echo "--- ${LABEL} c=${level} ---"
  node metrics.mjs --label "${LABEL}-c${level}-before" --out "${OUTDIR}/${LABEL}-c${level}.before.json" > /dev/null
  # MSYS_NO_PATHCONV is scoped to this one command on purpose: Git Bash rewrites
  # a leading-slash argument like /perf/t1 into C:/Program Files/Git/perf/t1,
  # which turns the request URL into an unresolvable host (ENOTFOUND). Exporting
  # it globally breaks mvn classpaths and taskkill //PID elsewhere in this repo.
  MSYS_NO_PATHCONV=1 node loadgen.mjs --base-url "$BASE_URL" --path "$REQ_PATH" --concurrency "$level" \
    --hold-ms "$HOLD_MS" --warmup-ms "$WARMUP_MS" --ramp-ms "$RAMP_MS" --timeout-ms "$TIMEOUT_MS" \
    --tokens "$TOKENS" --ttft-ms "$TTFT" --tpot-ms "$TPOT" --model "$MODEL" \
    --label "${LABEL}-c${level}" --out "${OUTDIR}/${LABEL}-c${level}.jsonl" \
    "${header_args[@]}" > "${OUTDIR}/${LABEL}-c${level}.summary.json" 2>"${OUTDIR}/${LABEL}-c${level}.err"
  node metrics.mjs --label "${LABEL}-c${level}-after" --out "${OUTDIR}/${LABEL}-c${level}.after.json" > /dev/null
  digest "$level" || { echo "        digest failed:"; head -3 "${OUTDIR}/${LABEL}-c${level}.err"; }
done
