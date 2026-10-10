#!/usr/bin/env bash
# Run one harness tier across a concurrency ladder, snapshotting gateway JVM
# metrics before and after each step and sampling machine CPU throughout.
#
# usage: run-tier.sh <LABEL> <BASE_URL> <PATH> <CONCURRENCY> [CONCURRENCY...]
#    or: run-tier.sh --preflight [SECONDS]     # is the box quiet enough to measure?
#
# Profile and window come from the environment so every tier is measured under
# an identical nominal load - the point is that differences between tiers are
# attributable to the tier, not to the knobs:
#   TOKENS TTFT TPOT HOLD_MS WARMUP_MS RAMP_MS TIMEOUT_MS MODEL HEADERS
#
# ADMISSION GATE. A level is marked INVALID and must not enter the budget table if
#   * machine-wide system_cpu_usage peaks at >= MAX_SYS_CPU (default 0.95), or
#   * the loadgen's own event-loop lag p99 >= MAX_CLIENT_LAG_P99 (default 100ms), or
#   * the gateway restarted mid-level, or the upstream profile echo mismatched, or
#     not a single warm stream completed.
# Rationale: on a shared box the co-tenant load (other JVMs, Vite, MySQL, Nacos in
# WSL, a concurrent session's maven builds) saturates the machine long before the
# gateway does. Those runs still produce plausible-looking numbers - measured once
# at 345 tok/s with 2023ms client lag - so the gate has to be mechanical.
#
# NOTE: pass 127.0.0.1, never "localhost". On this box localhost resolves to ::1
# first and the gateway listens on IPv4 only, which shows up as a wall of
# ENOTFOUND / UND_ERR_CONNECT_TIMEOUT that looks exactly like a gateway failure.
set -uo pipefail

# Resolve relative to the script so OUTDIR/results and the node -e argv paths line
# up regardless of the caller's cwd (and so bash /tmp vs node E:\tmp never mixes).
cd "$(dirname "$0")"

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
GATEWAY_METRICS_URL="${GATEWAY_METRICS_URL:-http://127.0.0.1/actuator/prometheus}"
GATEWAY_JAR="${GATEWAY_JAR:-../../my-gateway/target/my-gateway-1.0.0.jar}"
MAX_SYS_CPU="${MAX_SYS_CPU:-0.95}"
MAX_CLIENT_LAG_P99="${MAX_CLIENT_LAG_P99:-100}"
CPU_SAMPLE_SEC="${CPU_SAMPLE_SEC:-2}"

cpu_sample_line() {
  curl -s --max-time 3 "$GATEWAY_METRICS_URL" \
    | awk '/^system_cpu_usage\{/{s=$2} /^process_cpu_usage\{/{g=$2} END{if(s!="")printf "%.4f %.4f\n",s,g}'
}

# ---------------------------------------------------------------- preflight
if [ "${1:-}" = "--preflight" ]; then
  seconds="${2:-12}"
  echo "preflight: sampling machine CPU for ${seconds}s (idle box expected)"
  echo "  gate: system_cpu_usage must stay below ${MAX_SYS_CPU}"
  # Not mktemp: node resolves a bash /tmp path as drive-relative (E:\tmp), so the
  # sampler output has to live somewhere both sides agree on.
  tmp=".preflight-cpu.$$.txt"
  : > "$tmp"
  i=0
  while [ "$i" -lt "$seconds" ]; do
    line="$(cpu_sample_line)"
    [ -n "$line" ] && echo "$line" >> "$tmp"
    sleep "$CPU_SAMPLE_SEC"
    i=$((i + CPU_SAMPLE_SEC))
  done
  node -e '
const fs = require("fs");
const max = Number(process.argv[1]);
const rows = fs.readFileSync(process.argv[2], "utf8").trim().split("\n").filter(Boolean)
  .map((l) => l.split(" ").map(Number));
if (!rows.length) { console.log("  no samples - is the gateway up?"); process.exit(2); }
const sys = rows.map((r) => r[0]);
const gw = rows.map((r) => r[1]);
const peak = Math.max(...sys);
const mean = sys.reduce((a, b) => a + b, 0) / sys.length;
console.log(`  samples=${rows.length} sysCPU mean=${mean.toFixed(3)} peak=${peak.toFixed(3)} | gatewayCPU mean=${(gw.reduce((a, b) => a + b, 0) / gw.length).toFixed(3)}`);
if (peak >= max) {
  console.log(`  QUIET WINDOW: NO - peak ${peak.toFixed(3)} >= ${max}. Find what else is running (a concurrent session building, Vite, WSL) before measuring.`);
  process.exit(1);
}
console.log(`  QUIET WINDOW: YES - headroom to ${max} is ${(max - peak).toFixed(3)}.`);
' "$MAX_SYS_CPU" "$tmp"
  rc=$?
  rm -f "$tmp"
  exit $rc
fi

LABEL="${1:?label required}"
BASE_URL="${2:?base url required}"
REQ_PATH="${3:?request path required}"
shift 3
LEVELS=("$@")
[ ${#LEVELS[@]} -gt 0 ] || { echo "at least one concurrency level required"; exit 1; }

mkdir -p "$OUTDIR"

header_args=()
if [ -n "$HEADERS" ]; then
  IFS=';' read -ra pairs <<< "$HEADERS"
  for pair in "${pairs[@]}"; do header_args+=(--header "$pair"); done
fi

# Build identity. Two tiers are only comparable if the gateway binary, its start
# time and the repo revision are identical - a concurrent session rebuilding or
# restarting the gateway mid-ladder silently invalidates the comparison, and the
# numbers still look plausible. Capture it so the report can refuse to compare.
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
  let systemCpu = null;
  try {
    const text = await (await fetch(metricsUrl)).text();
    const m = text.match(/^process_start_time_seconds\{[^}]*\}\s+([0-9.E+]+)/m);
    if (m) gatewayStart = new Date(Number.parseFloat(m[1]) * 1000).toISOString();
    const c = text.match(/^system_cpu_usage\{[^}]*\}\s+([0-9.E+]+)/m);
    if (c) systemCpu = Number.parseFloat(c[1]);
  } catch { /* gateway unreachable - recorded as null, which is itself a red flag */ }
  fs.writeFileSync(out, JSON.stringify({
    capturedAt: new Date().toISOString(),
    tierTag: process.argv[4] || null,
    gitSha: sh("git rev-parse HEAD"),
    gitDirtyFiles: Number(sh("git status --porcelain | grep -c .") || 0),
    gatewayJar: jar || null,
    gatewayJarMtime: jar && fs.existsSync(jar) ? fs.statSync(jar).mtime.toISOString() : null,
    gatewayProcessStart: gatewayStart,
    systemCpuUsageAtStart: systemCpu,
    host: { platform: os.platform(), release: os.release(), cpus: os.cpus().length, totalMemMb: Math.round(os.totalmem() / 1048576) },
    node: process.version,
  }, null, 2));
})();
' "${OUTDIR}/${LABEL}.fingerprint.json" "$GATEWAY_METRICS_URL" "$GATEWAY_JAR" "${TIER_TAG:-}"
echo "fingerprint: $(node -e 'const j=require("./'"$OUTDIR"'/'"$LABEL"'.fingerprint.json");console.log("sha="+String(j.gitSha).slice(0,7),"tag="+j.tierTag,"gwStart="+j.gatewayProcessStart,"jar="+j.gatewayJarMtime,"sysCPU="+j.systemCpuUsageAtStart)')"

verdict() {
  local level="$1"
  node -e '
const fs = require("fs");
const [level, label, outdir, maxSysCpu, maxLag] = process.argv.slice(1);
const readJson = (p) => { try { return JSON.parse(fs.readFileSync(p, "utf8")); } catch { return null; } };
const base = `${outdir}/${label}-c${level}`;
const summary = readJson(`${base}.summary.json`);
const before = readJson(`${base}.before.json`);
const after = readJson(`${base}.after.json`);
const cpuRows = (() => {
  try {
    return fs.readFileSync(`${base}.cpu.txt`, "utf8").trim().split("\n").filter(Boolean)
      .map((l) => l.split(" ").map(Number));
  } catch { return []; }
})();
const reasons = [];
if (!summary) reasons.push("loadgen produced no summary");
const sys = cpuRows.map((r) => r[0]);
const gwc = cpuRows.map((r) => r[1]);
const peakSys = sys.length ? Math.max(...sys) : null;
const meanSys = sys.length ? sys.reduce((a, b) => a + b, 0) / sys.length : null;
const meanGw = gwc.length ? gwc.reduce((a, b) => a + b, 0) / gwc.length : null;
if (peakSys === null) reasons.push("no CPU samples (gateway metrics unreachable?)");
else if (peakSys >= Number(maxSysCpu)) reasons.push(`machine CPU saturated: peak system_cpu_usage=${peakSys.toFixed(3)} >= ${maxSysCpu}`);
const lag = summary && summary.clientHealth ? summary.clientHealth.eventLoopLagMs.p99 : null;
if (lag !== null && lag >= Number(maxLag)) reasons.push(`loadgen starved: clientLag p99=${lag}ms >= ${maxLag}ms`);
if (summary && summary.profileCheck && summary.profileCheck.matches === false) reasons.push("upstream profile echo mismatch");
if (before && after && before.gatewayProcessStartSec !== after.gatewayProcessStartSec) reasons.push("gateway restarted mid-level");
if (summary && summary.streamIntegrity && summary.streamIntegrity.warmStreams === 0) reasons.push("no warm samples (hold window too short for the stream duration?)");
const verdict = {
  label, level: Number(level), valid: reasons.length === 0, reasons,
  gate: { maxSysCpu: Number(maxSysCpu), maxClientLagP99: Number(maxLag) },
  observed: {
    cpuSamples: cpuRows.length, peakSystemCpu: peakSys, meanSystemCpu: meanSys, meanGatewayCpu: meanGw,
    gatewayCpuCores: meanGw === null ? null : +(meanGw * (require("os").cpus().length)).toFixed(2),
    clientLagP99Ms: lag,
    gatewayCpuMsPerSseEvent: (meanGw && summary && summary.throughput.tokensPerSec)
      ? +((meanGw * require("os").cpus().length) / summary.throughput.tokensPerSec * 1000).toFixed(3) : null,
  },
};
fs.writeFileSync(`${base}.verdict.json`, JSON.stringify(verdict, null, 2));
const head = verdict.valid ? "VERDICT: VALID" : "VERDICT: INVALID";
console.log(`        ${head}${verdict.valid ? "" : " - " + reasons.join("; ")}`);
console.log(`        gate: peakSysCPU=${peakSys === null ? "-" : peakSys.toFixed(3)}/${maxSysCpu} meanGwCPU=${meanGw === null ? "-" : meanGw.toFixed(3)} clientLagP99=${lag}/${maxLag}ms gwMsPerEvent=${verdict.observed.gatewayCpuMsPerSseEvent}`);
' "$level" "$LABEL" "$OUTDIR" "$MAX_SYS_CPU" "$MAX_CLIENT_LAG_P99"
}

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
  ` | profileOK=${j.profileCheck.matches}`);
const errs = Object.entries(c.errors || {});
if (errs.length) console.log(`        errors: ${errs.map(([k, v]) => `${k}=${v}`).join(" ")}`);
' "$level"
}

for level in "${LEVELS[@]}"; do
  echo "--- ${LABEL} c=${level} ---"
  base="${OUTDIR}/${LABEL}-c${level}"
  node metrics.mjs --label "${LABEL}-c${level}-before" --out "${base}.before.json" > /dev/null

  # Sampler in the background, loadgen in the foreground. The reverse (background
  # loadgen + foreground `wait`) deadlocked in practice and left 0-byte results.
  cpuf="${base}.cpu.txt"
  : > "$cpuf"
  total_sec=$(( (WARMUP_MS + HOLD_MS) / 1000 + 10 ))
  (
    i=0
    while [ "$i" -lt "$total_sec" ]; do
      line="$(cpu_sample_line)"
      [ -n "$line" ] && echo "$line" >> "$cpuf"
      sleep "$CPU_SAMPLE_SEC"
      i=$((i + CPU_SAMPLE_SEC))
    done
  ) &
  cpu_pid=$!

  # MSYS_NO_PATHCONV is scoped to this one command on purpose: Git Bash rewrites
  # a leading-slash argument like /perf/t1 into C:/Program Files/Git/perf/t1,
  # which turns the request URL into an unresolvable host (ENOTFOUND). Exporting
  # it globally breaks mvn classpaths and taskkill //PID elsewhere in this repo.
  MSYS_NO_PATHCONV=1 node loadgen.mjs --base-url "$BASE_URL" --path "$REQ_PATH" --concurrency "$level" \
    --hold-ms "$HOLD_MS" --warmup-ms "$WARMUP_MS" --ramp-ms "$RAMP_MS" --timeout-ms "$TIMEOUT_MS" \
    --tokens "$TOKENS" --ttft-ms "$TTFT" --tpot-ms "$TPOT" --model "$MODEL" \
    --label "${LABEL}-c${level}" --out "${base}.jsonl" \
    "${header_args[@]}" > "${base}.summary.json" 2>"${base}.err"

  kill "$cpu_pid" 2>/dev/null
  wait "$cpu_pid" 2>/dev/null
  node metrics.mjs --label "${LABEL}-c${level}-after" --out "${base}.after.json" > /dev/null

  digest "$level" || { echo "        digest failed:"; head -3 "${base}.err"; }
  verdict "$level"
done

echo "--- ${LABEL} summary ---"
node -e '
const fs = require("fs");
const [label, outdir] = process.argv.slice(1);
const levels = fs.readdirSync(outdir).filter((f) => f.startsWith(`${label}-c`) && f.endsWith(".verdict.json"));
let invalid = 0;
for (const f of levels.sort()) {
  const v = JSON.parse(fs.readFileSync(`${outdir}/${f}`, "utf8"));
  if (!v.valid) invalid += 1;
  console.log(`  c=${String(v.level).padStart(4)} ${v.valid ? "VALID  " : "INVALID"} ${v.reasons.join("; ")}`);
}
console.log(invalid ? `  ${invalid} of ${levels.length} levels INVALID - excluded from the budget table` : `  all ${levels.length} levels valid`);
' "$LABEL" "$OUTDIR"
