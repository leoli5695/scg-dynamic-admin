import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { monitorEventLoopDelay } from 'node:perf_hooks';
import { measure } from './load.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const opts = { levels: '1,16,64', repetitions: 2, warmupMs: 3000, holdMs: 8000, java: 'java', classpathFile: null,
  cases: null, basicCoalescing: 'true',
  output: path.join(here, 'results', new Date().toISOString().replaceAll(':', '-')) };
for (let i = 2; i < process.argv.length; i += 2) {
  const keys = { '--java': 'java', '--classpath-file': 'classpathFile', '--out': 'output', '--levels': 'levels',
    '--repetitions': 'repetitions', '--warmup-ms': 'warmupMs', '--hold-ms': 'holdMs',
    '--cases': 'cases', '--basic-coalescing': 'basicCoalescing' };
  const key = keys[process.argv[i]];
  if (!key || process.argv[i + 1] === undefined) throw new Error(`Unknown/missing argument ${process.argv[i]}`);
  opts[key] = ['repetitions', 'warmupMs', 'holdMs'].includes(key) ? Number(process.argv[i + 1]) : process.argv[i + 1];
}
const levels = opts.levels.split(',').map(Number);
if (!opts.classpathFile || levels.some(v => !Number.isInteger(v) || v < 1 || v > 256)
  || !Number.isInteger(opts.repetitions) || opts.repetitions < 1 || opts.warmupMs < 1000 || opts.holdMs < 3000) {
  throw new Error('Require classpath file, concurrency 1..256, repetitions >=1, warmup >=1000ms and hold >=3000ms');
}
if (!['true', 'false'].includes(opts.basicCoalescing)) throw new Error('--basic-coalescing must be true or false');
fs.mkdirSync(opts.output, { recursive: true });
const children = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function start(command, args, name, env = {}) {
  const child = spawn(command, args, { cwd: root, env: { ...process.env, ...env }, windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'] });
  children.push(child);
  const log = fs.createWriteStream(path.join(opts.output, `${name}.log`));
  child.stdout.pipe(log); child.stderr.pipe(log);
  child.on('error', error => { child.startError = error; });
  return child;
}
function ensureRunning(child, name) {
  if (child.startError || child.exitCode !== null) throw new Error(`${name} exited; see ${name}.log (${child.startError || child.exitCode})`);
}
const report = { createdAt: new Date().toISOString(), sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root }).toString().trim(),
  productionDiffSha256: crypto.createHash('sha256').update(execFileSync('git', ['diff', 'HEAD', '--', 'my-gateway/src/main', 'license-client/src/main'], { cwd: root })).digest('hex'),
  host: { platform: process.platform, release: os.release(), logicalProcessors: os.availableParallelism(),
    cpuModel: os.cpus()[0]?.model, totalMemoryBytes: os.totalmem(), node: process.version },
  model: 'closed-loop fixed concurrency; loopback; three separate processes sharing one host; HTTP/1.1; no TLS',
  gatewayLimits: { xmsMiB: 512, xmxMiB: 512, maxDirectMiB: 256, activeProcessorCount: 4, osCpuQuota: false },
  limitations: ['Local baseline, not production capacity or an SLO.', 'The CPU count is a JVM setting, not an OS CPU quota.',
    'No Redis/Nacos/admin network dependencies, access logs, trace export, semantic cache, WAF or response cache in this fixture.',
    'Filter-chain tracking stays enabled; its slow-filter warning output is suppressed to avoid per-request console noise.',
    'SSE fixture is deterministic: 50ms first content, 16 content frames, 10ms cadence, then DONE. No paid model calls.',
    'Gateway SSE enables AI_PROXY provider routing, body handling and key injection; provider keys are supplied locally to model a warm key cache.',
    'Authentication uses a reused JWT (warm validation cache), API key, and BCrypt cost=10 with success-result caching disabled.',
    'Big body is a 1MiB pass-through POST; it is not a WAF/JSON-transform benchmark.',
    'Gateway memory is sampled heap/nonheap/direct buffers, not OS RSS.',
    'P99 with fewer than 1000 completed latency samples is explicitly marked low-sample.',
    'Do not subtract separate-run percentiles to derive gateway overhead. Compare distributions and repeat variation.',
    'Generator/upstream saturation flags invalidate capacity claims at that level.'],
  options: { levels, repetitions: opts.repetitions, warmupMs: opts.warmupMs, holdMs: opts.holdMs,
    basicCoalescing: opts.basicCoalescing === 'true', basicSuccessCacheTtlMs: 0 }, runs: [] };

function save() {
  fs.writeFileSync(path.join(opts.output, 'results.json'), JSON.stringify(report, null, 2));
  const lines = ['# Gateway performance baseline', '', `Commit: \`${report.sourceCommit}\``,
    `Environment: ${report.host.cpuModel}; ${report.host.logicalProcessors} logical CPUs; Node ${report.host.node}; Java ${report.gateway?.java || '?'}.`,
    'Gateway JVM: 512 MiB heap, 256 MiB max direct memory, ActiveProcessorCount=4 (no OS CPU quota).', '',
    '| Case | Concurrency | Repeat | Success req/s | P50 ms | P95 ms | P99 ms | Errors | TTFT P99 ms | TPOT P99 ms | Gateway CPU cores | Heap peak MiB | Direct peak MiB | Notes |',
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|'];
  const n = v => v === null || v === undefined ? '—' : Number(v).toFixed(2);
  for (const run of report.runs) {
    lines.push(`| ${run.case} | ${run.concurrency} | ${run.repeat} | ${n(run.successRps)} | ${n(run.latencyMs?.p50)} | ${n(run.latencyMs?.p95)} | ${n(run.latencyMs?.p99)} | ${run.failed}/${run.attempted} | ${n(run.ttftMs?.p99)} | ${n(run.tpotMs?.p99)} | ${n(run.metrics.gateway?.meanCpuCores)} | ${n(run.metrics.gateway?.peakHeapUsedBytes / 1048576)} | ${n(run.metrics.gateway?.peakDirectBytes / 1048576)} | ${[run.p99LowSampleWarning ? 'low N for P99' : '', run.environmentLimited ? 'generator/upstream limit' : '', run.monitoringErrors ? 'metrics gaps' : ''].filter(Boolean).join('; ')} |`);
  }
  lines.push('', '## Scope and interpretation', '', ...report.limitations.map(text => `- ${text}`), '',
    'Throughput counts validated successful completions inside the measurement window, including warmup-originated requests finishing inside it. Latency/error samples include requests started inside the measurement window and drained afterwards. Warmup latency is excluded. Per-run distributions and per-second resource snapshots are in results.json; individual request timings are not retained.', '');
  fs.writeFileSync(path.join(opts.output, 'report.md'), lines.join('\n'));
}

try {
  const mock = start(process.execPath, [path.join(here, 'mock.mjs')], 'upstream');
  let upstreamPort;
  let mockOutput = '';
  mock.stdout.on('data', chunk => { mockOutput += chunk; try { upstreamPort = JSON.parse(mockOutput.trim().split('\n')[0]).port; } catch {} });
  for (let i = 0; !upstreamPort && i < 100; i++) { ensureRunning(mock, 'upstream'); await sleep(100); }
  if (!upstreamPort) throw new Error('Upstream startup timed out');
  const upstream = `http://127.0.0.1:${upstreamPort}`;
  const readyPath = path.resolve(opts.output, 'ready.json');
  fs.rmSync(readyPath, { force: true });
  const classpath = fs.readFileSync(opts.classpathFile, 'utf8').trim().replace(/^\uFEFF/, '');
  const gateway = start(opts.java, ['-Xms512m', '-Xmx512m', '-XX:MaxDirectMemorySize=256m', '-XX:ActiveProcessorCount=4',
    '-XX:+UseG1GC', '-Dfile.encoding=UTF-8', '-cp', classpath, 'com.leoli.gateway.perf.GatewayBaselineApplication',
    '--server.port=0', '--server.address=127.0.0.1', '--gateway.center.type=baseline', '--gateway.jwt.revocation.enabled=false',
    '--gateway.config-sync.interval-ms=3600000', '--logging.level.root=WARN', '--logging.level.com.leoli.gateway=WARN',
    `--gateway.auth.basic.coalesce-enabled=${opts.basicCoalescing}`, '--gateway.auth.basic.success-cache-ttl-ms=0',
    '--logging.level.com.leoli.gateway.monitor.FilterChainTracker=ERROR',
    '--logging.level.org.springframework.cloud.gateway=WARN'], 'gateway',
  { BASELINE_UPSTREAM_URL: upstream, BASELINE_READY_FILE: readyPath });
  for (let i = 0; !fs.existsSync(readyPath) && i < 1200; i++) { ensureRunning(gateway, 'gateway'); await sleep(100); }
  if (!fs.existsSync(readyPath)) throw new Error('Gateway startup timed out; see gateway.log');
  const ready = JSON.parse(fs.readFileSync(readyPath));
  report.gateway = Object.fromEntries(Object.entries(ready).filter(([key]) => key !== 'jwt'));
  const base = `http://127.0.0.1:${ready.port}`;
  const auth = { jwt: { authorization: `Bearer ${ready.jwt}` }, 'api-key': { 'x-api-key': 'baseline-only-api-key' },
    basic: { authorization: `Basic ${Buffer.from('baseline:baseline-only-password').toString('base64')}` } };
  // Check valid and invalid authentication and the real route before admitting load.
  for (const name of ['ordinary', 'jwt', 'api-key', 'basic']) {
    const response = await fetch(`${base}/baseline/${name}`, { headers: auth[name] || {} });
    if (response.status !== 200 || (await response.json()).ok !== true) throw new Error(`Preflight failed: ${name}`);
    if (auth[name]) {
      const denied = await fetch(`${base}/baseline/${name}`); await denied.arrayBuffer();
      if (denied.status !== 401) throw new Error(`Authentication preflight failed: ${name}`);
    }
  }
  let cases = [['direct-ordinary', 'ordinary', upstream], ['gateway-ordinary', 'ordinary', base],
    ['gateway-jwt', 'jwt', base], ['gateway-api-key', 'api-key', base], ['gateway-basic', 'basic', base],
    ['direct-body', 'body', upstream], ['gateway-body', 'body', base], ['direct-sse', 'sse', upstream], ['gateway-sse', 'sse', base]];
  if (opts.cases) {
    const selected = opts.cases.split(',');
    if (selected.some(name => !cases.some(([label]) => label === name))) throw new Error('Unknown case in --cases');
    cases = cases.filter(([label]) => selected.includes(label));
  }
  report.options.cases = cases.map(([label]) => label);
  const idleLag = monitorEventLoopDelay({ resolution: 10 });
  idleLag.enable(); await sleep(3000); idleLag.disable();
  const upstreamIdle = await (await fetch(`${upstream}/__baseline/metrics`)).json();
  const lagLimitMs = Math.max(20, idleLag.percentile(99) / 1e6 + 10, upstreamIdle.eventLoopP99Ms + 10);
  report.calibration = { clientIdleEventLoopP99Ms: idleLag.percentile(99) / 1e6,
    upstreamIdleEventLoopP99Ms: upstreamIdle.eventLoopP99Ms, lagLimitMs };
  if (lagLimitMs > 100) throw new Error('Idle host timing is unstable (>100ms); rerun in a quiet window');
  console.log('PREWARM gateway routes and JIT before recording measurements');
  for (const [scenario, holdMs] of [['ordinary', 15000], ['jwt', 5000], ['api-key', 5000], ['basic', 3000], ['body', 3000], ['sse', 3000]]) {
    if (opts.cases && !cases.some(([, name]) => name === scenario)) continue;
    const warm = await measure({ url: `${base}/baseline/${scenario}`, concurrency: scenario === 'basic' ? 4 : 16,
      scenario, headers: auth[scenario] || {}, warmupMs: 1000,
      holdMs: scenario === 'basic' && opts.cases ? 20000 : holdMs, lagLimitMs });
    if (warm.failed) throw new Error(`Prewarm validation failed for ${scenario}: ${JSON.stringify(warm.errors)}`);
  }
  console.log(`READY ${base}; reports: ${opts.output}`);
  for (const concurrency of levels) {
    for (let repeat = 1; repeat <= opts.repetitions; repeat++) {
      for (const [label, scenario, host] of cases) {
        ensureRunning(gateway, 'gateway'); ensureRunning(mock, 'upstream');
        const result = await measure({ url: `${host}/baseline/${scenario}`, concurrency, scenario,
          headers: auth[scenario] || {}, warmupMs: opts.warmupMs, holdMs: opts.holdMs,
          lagLimitMs,
          metricsUrls: [['gateway', `${base}/__baseline/metrics`], ['upstream', `${upstream}/__baseline/metrics`]] });
        report.runs.push({ case: label, repeat, ...result }); save();
        console.log(`${label} c=${concurrency} repeat=${repeat}: ${result.successRps.toFixed(1)} req/s, p99=${result.latencyMs?.p99.toFixed(2)}ms errors=${result.failed}/${result.attempted}${result.environmentLimited ? ' ENVIRONMENT-LIMITED' : ''}`);
        await sleep(300);
      }
    }
  }
  report.completedAt = new Date().toISOString(); save();
  console.log(`COMPLETE ${path.resolve(opts.output, 'report.md')}`);
} catch (error) {
  report.failure = error.stack; save();
  console.error(error.stack); process.exitCode = 1;
} finally {
  for (const child of children.reverse()) {
    if (child.exitCode === null) child.kill('SIGTERM');
  }
  fs.rmSync(path.join(opts.output, 'ready.json'), { force: true });
}
