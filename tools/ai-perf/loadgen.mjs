#!/usr/bin/env node
// SSE concurrency-ladder load generator for the AI gateway harness.
//
// Closed-loop model: hold exactly --concurrency streams in flight, starting a
// replacement as soon as one finishes. That is what "max concurrent streams per
// node" means; an open-loop arrival model would answer a different question.
//
// Two disciplines are built in rather than left to the operator:
//   * The token handler only records timestamps - no JSON parsing per event, so
//     the client cannot become the thing being measured.
//   * The client reports its OWN event-loop lag and CPU, and T0's excess over
//     the nominal profile is the harness resolution floor. Any "added latency"
//     below that floor is noise, not a result.

import fs from 'node:fs';
import path from 'node:path';

function parseArgs(argv) {
  const args = {
    baseUrl: 'http://127.0.0.1:18080',
    path: '/v1/chat/completions',
    concurrency: 100,
    holdMs: 30_000,
    rampMs: 2_000,
    warmupMs: 10_000,
    tokens: 64,
    ttftMs: 200,
    tpotMs: 25,
    model: 'mock-model',
    label: 'run',
    out: null,
    stream: true,
    includeUsage: true,
    header: [],
    promptChars: 64,
    timeoutMs: 120_000,
  };
  for (let i = 2; i < argv.length; i += 1) {
    const key = argv[i];
    const next = () => argv[++i];
    switch (key) {
      case '--base-url': args.baseUrl = next(); break;
      case '--path': args.path = next(); break;
      case '--concurrency': args.concurrency = Number(next()); break;
      case '--hold-ms': args.holdMs = Number(next()); break;
      case '--ramp-ms': args.rampMs = Number(next()); break;
      case '--warmup-ms': args.warmupMs = Number(next()); break;
      case '--tokens': args.tokens = Number(next()); break;
      case '--ttft-ms': args.ttftMs = Number(next()); break;
      case '--tpot-ms': args.tpotMs = Number(next()); break;
      case '--model': args.model = next(); break;
      case '--label': args.label = next(); break;
      case '--out': args.out = next(); break;
      case '--header': args.header.push(next()); break;
      case '--prompt-chars': args.promptChars = Number(next()); break;
      case '--timeout-ms': args.timeoutMs = Number(next()); break;
      case '--no-stream': args.stream = false; break;
      case '--no-usage': args.includeUsage = false; break;
      default: throw new Error(`unknown argument: ${key}`);
    }
  }
  return args;
}

const args = parseArgs(process.argv);

// ---- client self-monitoring (proves the loadgen is not the bottleneck) ----
const lagSamples = [];
let lastTick = performance.now();
const lagTimer = setInterval(() => {
  const now = performance.now();
  lagSamples.push(Math.max(0, now - lastTick - 100));
  lastTick = now;
  if (lagSamples.length > 3600) lagSamples.shift();
}, 100);
lagTimer.unref();

function sortedPercentile(values, p) {
  if (values.length === 0) return 0;
  const sorted = Float64Array.from(values).sort();
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[idx];
}

function dist(values) {
  if (values.length === 0) return null;
  const sum = values.reduce((a, b) => a + b, 0);
  return {
    n: values.length,
    mean: +(sum / values.length).toFixed(3),
    p50: +sortedPercentile(values, 50).toFixed(3),
    p90: +sortedPercentile(values, 90).toFixed(3),
    p99: +sortedPercentile(values, 99).toFixed(3),
    p999: +sortedPercentile(values, 99.9).toFixed(3),
    max: +sortedPercentile(values, 100).toFixed(3),
  };
}

const extraHeaders = {};
for (const pair of args.header) {
  const idx = pair.indexOf(':');
  if (idx < 0) throw new Error(`--header expects "Name: value", got: ${pair}`);
  extraHeaders[pair.slice(0, idx).trim()] = pair.slice(idx + 1).trim();
}

// The mock's latency profile must be driven from here, not from its env defaults:
// otherwise the "nominal" baseline in the report describes a profile the upstream
// never emitted and every excess figure is fiction. Real providers ignore these.
const profileHeaders = {
  'x-mock-tokens': String(args.tokens),
  'x-mock-ttft-ms': String(args.ttftMs),
  'x-mock-tpot-ms': String(args.tpotMs),
};

// Echoed back by the mock so a profile mismatch is caught instead of silently
// folding into the results.
let profileEcho = null;

const requestBody = JSON.stringify({
  model: args.model,
  stream: args.stream,
  ...(args.stream && args.includeUsage ? { stream_options: { include_usage: true } } : {}),
  messages: [{ role: 'user', content: 'x'.repeat(args.promptChars) }],
});

const results = {
  ttft: [],
  intervals: [],
  streamDuration: [],
  events: [],
  bytes: [],
};
const errors = new Map();
let opened = 0;
let completed = 0;
let failed = 0;
let inflight = 0;
let inflightSum = 0;
let inflightSamples = 0;
let usageEventsSeen = 0;
let doneEventsSeen = 0;
let truncatedStreams = 0;
let shutdownAborts = 0;
let stopped = false;
// Streams still open when the hold window ends are aborted so shutdown is
// prompt; they are counted separately and excluded from the error rate, since
// the abort is the harness stopping, not the system under test failing.
const activeControllers = new Set();

const outStream = args.out
  ? (fs.mkdirSync(path.dirname(path.resolve(args.out)), { recursive: true }),
    fs.createWriteStream(args.out, { flags: 'w' }))
  : null;

function recordError(kind) {
  failed += 1;
  errors.set(kind, (errors.get(kind) || 0) + 1);
}

// Cheap content-token detection: no JSON parse per event, only a substring probe.
function isContentEvent(text) {
  const at = text.indexOf('"content":"');
  return at >= 0 && text[at + 11] !== '"';
}

async function runOneStream(runStartedAt, warmupCutoff) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), args.timeoutMs);
  const intended = performance.now();
  const record = {
    label: args.label,
    startOffsetMs: +(intended - runStartedAt).toFixed(1),
    warm: intended - runStartedAt >= warmupCutoff,
  };
  inflight += 1;
  opened += 1;
  activeControllers.add(controller);
  try {
    const res = await fetch(args.baseUrl + args.path, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...profileHeaders, ...extraHeaders },
      body: requestBody,
      signal: controller.signal,
    });
    record.status = res.status;
    if (profileEcho === null) {
      profileEcho = {
        tokens: res.headers.get('x-mock-echo-tokens'),
        ttftMs: res.headers.get('x-mock-echo-ttft-ms'),
        tpotMs: res.headers.get('x-mock-echo-tpot-ms'),
      };
    }
    if (!res.ok) {
      recordError(`http_${res.status}`);
      await res.body?.cancel?.().catch(() => {});
      record.error = `http_${res.status}`;
      return record;
    }
    if (!args.stream) {
      const text = await res.text();
      const end = performance.now();
      record.durationMs = +(end - intended).toFixed(2);
      record.bytes = text.length;
      record.hasUsage = text.includes('"usage":{');
      if (record.warm) {
        results.ttft.push(record.durationMs);
        results.bytes.push(record.bytes);
      }
      return record;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    let firstContentAt = null;
    let prevEventAt = null;
    let eventCount = 0;
    let byteCount = 0;
    let sawDone = false;
    const localIntervals = [];
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const now = performance.now();
      byteCount += value.byteLength;
      buf += decoder.decode(value, { stream: true });
      let cut;
      while ((cut = buf.indexOf('\n\n')) >= 0) {
        const event = buf.slice(0, cut);
        buf = buf.slice(cut + 2);
        if (!event.startsWith('data:')) continue;
        eventCount += 1;
        if (event.includes('[DONE]')) {
          doneEventsSeen += 1;
          sawDone = true;
        }
        if (event.includes('"usage":{')) usageEventsSeen += 1;
        if (firstContentAt === null && isContentEvent(event)) firstContentAt = now;
        if (prevEventAt !== null) localIntervals.push(now - prevEventAt);
        prevEventAt = now;
      }
    }
    const end = performance.now();
    record.durationMs = +(end - intended).toFixed(2);
    record.events = eventCount;
    record.bytes = byteCount;
    record.ttftMs = firstContentAt === null ? null : +(firstContentAt - intended).toFixed(2);
    record.sawDone = sawDone;
    if (record.warm) {
      if (!sawDone) truncatedStreams += 1;
      if (record.ttftMs !== null) results.ttft.push(record.ttftMs);
      // Not push(...arr): a long stream can exceed the argument-count limit.
      for (let i = 0; i < localIntervals.length; i += 1) results.intervals.push(localIntervals[i]);
      results.streamDuration.push(record.durationMs);
      results.events.push(eventCount);
      results.bytes.push(byteCount);
    }
    completed += 1;
    return record;
  } catch (err) {
    if (stopped) {
      shutdownAborts += 1;
      record.error = 'shutdown_abort';
      return record;
    }
    const kind = err?.name === 'AbortError' ? 'timeout_abort' : `${err?.cause?.code || err?.name || 'error'}`;
    recordError(kind);
    record.error = kind;
    return record;
  } finally {
    clearTimeout(timeout);
    activeControllers.delete(controller);
    inflight -= 1;
    if (outStream && record.warm) outStream.write(`${JSON.stringify(record)}\n`);
  }
}

const inflightSampler = setInterval(() => {
  inflightSum += inflight;
  inflightSamples += 1;
}, 200);
inflightSampler.unref();

const runStartedAt = performance.now();
const warmupCutoff = args.warmupMs;
const rampStepMs = args.concurrency > 1 ? args.rampMs / args.concurrency : 0;

async function main() {
  const workers = [];
  for (let slot = 0; slot < args.concurrency; slot += 1) {
    workers.push((async () => {
      if (rampStepMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, slot * rampStepMs));
      }
      while (!stopped) {
        await runOneStream(runStartedAt, warmupCutoff);
      }
    })());
  }

  await new Promise((resolve) => setTimeout(resolve, args.warmupMs + args.holdMs));
  stopped = true;
  for (const controller of activeControllers) controller.abort();
  await Promise.all(workers);
}

const cpuStart = process.cpuUsage();
await main();
const cpuEnd = process.cpuUsage(cpuStart);
clearInterval(inflightSampler);
clearInterval(lagTimer);

const wallMs = performance.now() - runStartedAt;
const warmSeconds = Math.max(0.001, (args.holdMs) / 1000);
const nominalTtft = args.ttftMs;
const nominalTpot = args.tpotMs;

const ttftDist = dist(results.ttft);
const intervalDist = dist(results.intervals);
const excessIntervals = results.intervals.map((v) => v - nominalTpot);
const excessIntervalDist = dist(excessIntervals);

const summary = {
  label: args.label,
  config: { ...args, header: extraHeaders },
  // Guards against the harness measuring a profile the upstream never emitted.
  profileCheck: {
    requested: { tokens: args.tokens, ttftMs: args.ttftMs, tpotMs: args.tpotMs },
    echoedByUpstream: profileEcho,
    matches: profileEcho === null
      ? null
      : String(profileEcho.tokens) === String(args.tokens)
        && String(profileEcho.tpotMs) === String(args.tpotMs)
        && String(profileEcho.ttftMs) === String(args.ttftMs),
  },
  wallMs: Math.round(wallMs),
  measuredWindowSec: +warmSeconds.toFixed(2),
  concurrency: {
    requested: args.concurrency,
    avgInFlight: inflightSamples ? +(inflightSum / inflightSamples).toFixed(1) : 0,
    opened,
    completed,
    failed,
    shutdownAborts,
    errorRate: failed + completed ? +(failed / (failed + completed)).toFixed(5) : 0,
    errors: Object.fromEntries(errors),
  },
  throughput: {
    streamsPerSec: +(results.streamDuration.length / warmSeconds).toFixed(2),
    tokensPerSec: +(results.intervals.length / warmSeconds).toFixed(1),
  },
  ttftMs: ttftDist,
  // The two numbers that matter: how far the observed values sit above the
  // nominal profile the mock was asked to emit.
  ttftExcessOverNominalMs: ttftDist
    ? { nominal: nominalTtft, p50: +(ttftDist.p50 - nominalTtft).toFixed(2), p99: +(ttftDist.p99 - nominalTtft).toFixed(2), max: +(ttftDist.max - nominalTtft).toFixed(2) }
    : null,
  intervalMs: intervalDist,
  intervalExcessOverNominalMs: excessIntervalDist
    ? { nominal: nominalTpot, p50: +excessIntervalDist.p50.toFixed(2), p99: +excessIntervalDist.p99.toFixed(2), p999: +excessIntervalDist.p999.toFixed(2), max: +excessIntervalDist.max.toFixed(2) }
    : null,
  streamIntegrity: {
    usageEventsSeen,
    doneEventsSeen,
    warmStreams: results.streamDuration.length,
    truncatedStreams,
    eventsPerStream: dist(results.events)?.mean ?? null,
  },
  clientHealth: {
    eventLoopLagMs: {
      p50: +sortedPercentile(lagSamples, 50).toFixed(2),
      p99: +sortedPercentile(lagSamples, 99).toFixed(2),
      max: +sortedPercentile(lagSamples, 100).toFixed(2),
    },
    cpuSec: { user: +(cpuEnd.user / 1e6).toFixed(2), system: +(cpuEnd.system / 1e6).toFixed(2) },
    heapUsedMb: +(process.memoryUsage().heapUsed / 1048576).toFixed(1),
  },
};

if (outStream) await new Promise((resolve) => outStream.end(resolve));
console.log(JSON.stringify(summary, null, 2));
