import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const [offPath, onPath, output] = process.argv.slice(2);
if (!output) throw new Error('Usage: node compare-basic.mjs <off-results.json> <on-results.json> <report.md>');
const off = JSON.parse(fs.readFileSync(offPath, 'utf8'));
const on = JSON.parse(fs.readFileSync(onPath, 'utf8'));
for (const report of [off, on]) {
  assert.ok(report.completedAt && !report.failure);
  assert.equal(report.options.basicSuccessCacheTtlMs, 0);
  assert.equal(report.runs.length, report.options.levels.length * report.options.repetitions);
  assert.ok(report.runs.every(r => r.case === 'gateway-basic' && r.monitoringErrors === 0));
}
assert.equal(off.options.basicCoalescing, false);
assert.equal(on.options.basicCoalescing, true);
assert.equal(off.productionDiffSha256, on.productionDiffSha256);
assert.deepEqual(off.gatewayLimits, on.gatewayLimits);
assert.deepEqual(off.options.levels, on.options.levels);
assert.equal(off.gateway.java, on.gateway.java);
const fmt = n => n.toFixed(2);
const range = values => `${fmt(Math.min(...values))}–${fmt(Math.max(...values))}`;
const rows = [];
for (const concurrency of off.options.levels) {
  const a = off.runs.filter(r => r.concurrency === concurrency);
  const b = on.runs.filter(r => r.concurrency === concurrency);
  rows.push({ concurrency,
    offRps: a.map(r => r.successRps), onRps: b.map(r => r.successRps),
    offWorstP99Ms: Math.max(...a.map(r => r.latencyMs.p99)), onWorstP99Ms: Math.max(...b.map(r => r.latencyMs.p99)),
    offCpuCores: a.map(r => r.metrics.gateway.meanCpuCores), onCpuCores: b.map(r => r.metrics.gateway.meanCpuCores),
    conservativeThroughputRatio: Math.min(...b.map(r => r.successRps)) / Math.max(...a.map(r => r.successRps)),
    errors: [...a, ...b].reduce((sum, r) => sum + r.failed, 0),
    lowP99Samples: [...a, ...b].some(r => r.p99LowSampleWarning),
    environmentLimited: [...a, ...b].some(r => r.environmentLimited) });
}
const lines = ['# Basic BCrypt 并发校验合并对照', '',
  '- 同一台主机、Java/JVM 配置、生产源码差分指纹和压测参数；仅切换 `gateway.auth.basic.coalesce-enabled`。',
  '- 两组都使用同一固定账号重复请求、BCrypt cost=10，成功结果缓存 TTL=0。优化不会降低 BCrypt 强度，也不靠跨请求成功缓存取得结果。',
  `- 每组并发 ${off.options.levels.join('/')}，每档 ${off.options.repetitions} 次，预热 ${off.options.warmupMs / 1000}s、测量 ${off.options.holdMs / 1000}s；测量前各有 20s BCrypt 预热。`,
  `- Java ${off.gateway.java}；堆 512MiB，直接内存上限 256MiB，ActiveProcessorCount=4（不是 OS CPU 配额）。`, '',
  '| 并发 | 关闭合并 req/s | 开启合并 req/s | 关闭 P99 ms | 开启 P99 ms | 关闭 CPU 核数 | 开启 CPU 核数 | 保守吞吐倍数 | 失败 |',
  '|---:|---:|---:|---:|---:|---:|---:|---:|---:|'];
for (const row of rows) {
  lines.push(`| ${row.concurrency} | ${range(row.offRps)} | ${range(row.onRps)} | ${fmt(row.offWorstP99Ms)} | ${fmt(row.onWorstP99Ms)} | ${range(row.offCpuCores)} | ${range(row.onCpuCores)} | ${fmt(row.conservativeThroughputRatio)}x | ${row.errors} |`);
}
lines.push('', 'P99 列为两次重复中较大值；保守倍数为开启组最低吞吐 / 关闭组最高吞吐，不是统计置信区间。低并发 Basic 样本少，P99 仅作初步观测。', '',
  '## 适用边界', '',
  '- 只合并尚未完成且凭证完全一致的校验，每个请求仍独立做路由授权。任务完成后移出共享表；默认没有后续成功缓存。',
  '- 改密、算法变化、凭证停用不能复用旧校验；错误密码不写成功缓存；不同策略不混用。',
  '- 隔离请求及大量不同账号/密码仍要支付 BCrypt 成本，不能把这里的同凭证吞吐套用于所有认证流量。',
  '- 成功短缓存另有可选开关，默认关闭，最多 5s；本次两组都未开启。',
  '- 本机闭环测试不代表生产容量，未覆盖 TLS、真实 Redis/Nacos/管理端、独立压力机和长期稳态。', '',
  '## 复现', '', '```powershell',
  "./tools/gateway-perf/run.ps1 -Cases 'gateway-basic' -BasicCoalescing false -Levels '1,16,64' -Repetitions 2 -OutputDirectory './tools/gateway-perf/results/basic-off'",
  "./tools/gateway-perf/run.ps1 -Cases 'gateway-basic' -BasicCoalescing true -Levels '1,16,64' -Repetitions 2 -OutputDirectory './tools/gateway-perf/results/basic-on'",
  'node tools/gateway-perf/compare-basic.mjs <off-results.json> <on-results.json> <report.md>', '```', '');
fs.mkdirSync(path.dirname(path.resolve(output)), { recursive: true });
fs.writeFileSync(output, lines.join('\n'));
const trim = report => ({ ...report, runs: report.runs.map(({ resources, ...run }) => run) });
fs.writeFileSync(output.replace(/\.md$/i, '') + '.json', JSON.stringify({ comparison: rows, off: trim(off), on: trim(on) }, null, 2));
console.log(path.resolve(output));
