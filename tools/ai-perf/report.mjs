#!/usr/bin/env node
// Turn harness output into the performance budget table.
//
// Admission rule: a level enters the budget table only if it has a verdict file
// and that verdict says valid. Levels the gate rejected are listed with their
// reasons; levels that predate the gate have no verdict at all and are treated as
// unverified - plausible-looking numbers are exactly what the gate exists to
// distrust, so absence of a verdict is not absence of a problem.
//
// usage: node report.mjs [--out results/budget-table.md] [--include-legacy]

import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const getFlag = (name) => args.includes(name);
const getOpt = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const OUT = getOpt('--out', 'results/budget-table.md');
const RESULTS = getOpt('--results', 'results');
const INCLUDE_LEGACY = getFlag('--include-legacy');

const readJson = (p) => {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch {
    return null;
  }
};

const levels = [];
for (const file of fs.readdirSync(RESULTS)) {
  const m = file.match(/^(.+)-c(\d+)\.summary\.json$/);
  if (!m) continue;
  const [, label, level] = m;
  const base = path.join(RESULTS, `${label}-c${level}`);
  const summary = readJson(`${base}.summary.json`);
  if (!summary) continue;
  levels.push({
    label,
    level: Number(level),
    summary,
    verdict: readJson(`${base}.verdict.json`),
    before: readJson(`${base}.before.json`),
    after: readJson(`${base}.after.json`),
    fingerprint: readJson(path.join(RESULTS, `${label}.fingerprint.json`)),
  });
}
levels.sort((a, b) => a.label.localeCompare(b.label) || a.level - b.level);

const admissible = levels.filter((l) => l.verdict?.valid === true);
const rejected = levels.filter((l) => l.verdict && l.verdict.valid === false);
const unverified = levels.filter((l) => !l.verdict);

// Comparability: tiers may only be compared within one build identity.
const identities = new Map();
for (const l of admissible) {
  const fp = l.fingerprint;
  const key = fp ? `${fp.gatewayJarMtime}|${fp.gatewayProcessStart}` : 'unknown';
  if (!identities.has(key)) identities.set(key, []);
  identities.get(key).push(`${l.label}-c${l.level}`);
}

const mb = (x) => (x == null ? '-' : (x / 1048576).toFixed(1));
const num = (x, d = 1) => (x == null || Number.isNaN(x) ? '-' : Number(x).toFixed(d));

function row(l) {
  const s = l.summary;
  const b = l.before?.jvm;
  const a = l.after?.jvm;
  const o = l.verdict?.observed || {};
  return [
    l.label,
    l.level,
    num(s.throughput?.tokensPerSec, 0),
    num(s.throughput?.streamsPerSec, 1),
    num(s.ttftExcessOverNominalMs?.p50),
    num(s.ttftExcessOverNominalMs?.p99),
    num(s.intervalExcessOverNominalMs?.p99),
    num(s.intervalExcessOverNominalMs?.p999),
    num(s.concurrency?.errorRate, 4),
    s.streamIntegrity?.truncatedStreams ?? '-',
    b && a ? `${mb(b.heapUsedBytes)}→${mb(a.heapUsedBytes)}` : '-',
    b && a ? `${mb(b.directBufferBytes)}→${mb(a.directBufferBytes)}` : '-',
    b && a ? `${a.gcPauseCount - b.gcPauseCount}/${num(a.gcPauseMaxSec * 1000, 0)}ms` : '-',
    num(o.gatewayCpuCores, 2),
    num(o.gatewayCpuMsPerSseEvent, 3),
    num(o.peakSystemCpu, 3),
    num(s.clientHealth?.eventLoopLagMs?.p99, 1),
  ];
}

const HEADER = [
  'tier', 'c', 'tok/s', 'streams/s',
  'TTFTexc p50 ms', 'TTFTexc p99 ms', 'intExc p99 ms', 'intExc p999 ms',
  'errRate', 'trunc', 'heap MB', 'direct MB', 'GC n/max',
  'gwCPU cores', 'gw ms/event', 'peakSysCPU', 'clientLag p99 ms',
];

const table = (rows) => {
  if (!rows.length) return '_(none)_\n';
  const lines = [
    `| ${HEADER.join(' | ')} |`,
    `|${HEADER.map(() => '---').join('|')}|`,
    ...rows.map((r) => `| ${r.join(' | ')} |`),
  ];
  return `${lines.join('\n')}\n`;
};

const fp = levels.find((l) => l.fingerprint)?.fingerprint || {};
const nominal = levels.find((l) => l.summary?.config)?.summary.config || {};

const out = [];
out.push('# AI gateway performance budget');
out.push('');
out.push(`Generated ${new Date().toISOString()} by \`tools/ai-perf/report.mjs\`.`);
out.push('');
out.push('## Scope and standing caveats');
out.push('');
out.push(`- Nominal upstream profile: TTFT ${nominal.ttftMs} ms, ${nominal.tokens} tokens, TPOT ${nominal.tpotMs} ms. All latency figures are **excess over that nominal profile**, never a difference between two runs' percentiles (percentiles are not subtractable).`);
out.push(`- Host: ${fp.host?.platform} ${fp.host?.release}, ${fp.host?.cpus} cores, ${fp.host?.totalMemMb} MB RAM, node ${fp.node}.`);
out.push('- **Single-host runs are relative only.** Load generator, mock upstream, gateway JVM, admin JVM, Vite, MySQL and Nacos share one box, so every tier includes co-tenant contention. These numbers must not be published as capacity or as an absolute added-latency SLO.');
out.push('- **Resolution floor on this box is 5-12 ms** (measured: warm keep-alive TTFT excess 5-12 ms, cold connection +75 ms). Any SLO tighter than that - e.g. "added TTFT p99 <= 5 ms" - is not measurable here and needs a quiet Linux host.');
out.push('- Admission gate: a level is rejected when peak `system_cpu_usage` >= 0.95, or loadgen `clientLag p99` >= 100 ms, or the gateway restarted mid-level, or the upstream profile echo mismatched, or no warm stream completed.');
out.push('');
out.push('## Build identity');
out.push('');
out.push(`- git sha: \`${fp.gitSha || 'unknown'}\` (dirty files at capture: ${fp.gitDirtyFiles ?? '?'})`);
out.push(`- gateway jar mtime: ${fp.gatewayJarMtime || 'unknown'}; gateway process start: ${fp.gatewayProcessStart || 'unknown'}`);
if (identities.size > 1) {
  out.push('');
  out.push(`> **Warning: admissible levels span ${identities.size} distinct build identities - do not compare across them.**`);
  for (const [key, members] of identities) out.push(`> - \`${key}\`: ${members.join(', ')}`);
}
out.push('');
out.push(`## Budget table (${admissible.length} admissible level${admissible.length === 1 ? '' : 's'})`);
out.push('');
out.push(table(admissible.map(row)));
if (!admissible.length) {
  out.push('No level has passed the admission gate yet, so there is no budget to report.');
  out.push('');
}
out.push(`## Rejected by the gate (${rejected.length})`);
out.push('');
if (rejected.length) {
  out.push('| tier | c | reason |');
  out.push('|---|---|---|');
  for (const l of rejected) out.push(`| ${l.label} | ${l.level} | ${l.verdict.reasons.join('; ')} |`);
  out.push('');
} else {
  out.push('_(none)_');
  out.push('');
}
out.push(`## Unverified - predate the gate (${unverified.length})`);
out.push('');
out.push('These have no verdict file, so nothing checked machine saturation, loadgen starvation, mid-run gateway restarts or profile echo. They are **excluded from the budget table** by default; pass `--include-legacy` to list them as reference only.');
out.push('');
if (INCLUDE_LEGACY && unverified.length) {
  out.push(table(unverified.map(row)));
  out.push('_Reference only: measured before the admission gate existed. Several are known to have been taken on a saturated machine._');
  out.push('');
}
fs.mkdirSync(path.dirname(path.resolve(OUT)), { recursive: true });
fs.writeFileSync(OUT, `${out.join('\n')}\n`);
console.log(`wrote ${OUT}`);
console.log(`  admissible=${admissible.length} rejected=${rejected.length} unverified=${unverified.length} buildIdentities=${identities.size}`);
