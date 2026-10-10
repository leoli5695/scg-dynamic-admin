#!/usr/bin/env node
// Prometheus snapshot for one harness tier. Take one before and one after a
// ladder run: gauges give the level (heap, direct buffers, threads), counters
// give the delta (GC pauses, bytes allocated, requests served).
//
// RSS is deliberately absent - Micrometer does not export
// process_resident_memory_bytes on Windows. For a Netty gateway the meaningful
// footprint is heap + direct buffer memory, which is what this captures, so the
// "memory per 1k streams" budget is expressed in those terms.

import fs from 'node:fs';
import path from 'node:path';

function parseArgs(argv) {
  const args = { url: 'http://localhost/actuator/prometheus', label: 'snapshot', out: null };
  for (let i = 2; i < argv.length; i += 1) {
    const key = argv[i];
    const next = () => argv[++i];
    if (key === '--url') args.url = next();
    else if (key === '--label') args.label = next();
    else if (key === '--out') args.out = next();
    else throw new Error(`unknown argument: ${key}`);
  }
  return args;
}

const args = parseArgs(process.argv);

const text = await (await fetch(args.url)).text();

// name -> array of {labels, value}
const series = new Map();
for (const line of text.split('\n')) {
  if (!line || line.startsWith('#')) continue;
  const brace = line.indexOf('{');
  const name = brace < 0 ? line.slice(0, line.indexOf(' ')) : line.slice(0, brace);
  const rest = brace < 0 ? line.slice(line.indexOf(' ') + 1) : line.slice(line.lastIndexOf('}') + 2);
  const value = Number(rest.trim());
  if (!Number.isFinite(value)) continue;
  const labels = brace < 0 ? '' : line.slice(brace + 1, line.lastIndexOf('}'));
  if (!series.has(name)) series.set(name, []);
  series.get(name).push({ labels, value });
}

function sum(name, labelFilter) {
  const entries = series.get(name) || [];
  return entries
    .filter((e) => (labelFilter ? labelFilter(e.labels) : true))
    .reduce((acc, e) => acc + e.value, 0);
}

function has(name, labelFilter) {
  return (series.get(name) || []).some((e) => (labelFilter ? labelFilter(e.labels) : true));
}

const label = (k, v) => (s) => s.includes(`${k}="${v}"`);

const snapshot = {
  label: args.label,
  takenAtMs: Date.now(),
  // A restart mid-ladder silently invalidates the tier; comparing this across
  // the before/after snapshots catches it instead of publishing mixed numbers.
  gatewayProcessStartSec: sum('process_start_time_seconds'),
  // Machine-wide, not just the gateway: the admission gate rejects a level when
  // the box itself is saturated, because then the numbers measure co-tenant load
  // rather than the thing under test.
  systemCpuUsage: sum('system_cpu_usage'),
  jvm: {
    heapUsedBytes: sum('jvm_memory_used_bytes', label('area', 'heap')),
    nonHeapUsedBytes: sum('jvm_memory_used_bytes', label('area', 'nonheap')),
    directBufferBytes: sum('jvm_buffer_memory_used_bytes', label('id', 'direct')),
    directBufferCount: sum('jvm_buffer_count_buffers', label('id', 'direct')),
    threadsLive: sum('jvm_threads_live_threads'),
    gcPauseCount: sum('jvm_gc_pause_seconds_count'),
    gcPauseSumSec: sum('jvm_gc_pause_seconds_sum'),
    gcPauseMaxSec: sum('jvm_gc_pause_seconds_max'),
    gcAllocatedBytesTotal: sum('jvm_gc_memory_allocated_bytes_total'),
    gcOverheadPercent: sum('jvm_gc_overhead_percent'),
    processCpuUsage: sum('process_cpu_usage'),
  },
  http: {
    requestCount: sum('http_server_requests_seconds_count'),
    requestSumSec: sum('http_server_requests_seconds_sum'),
    requestMaxSec: sum('http_server_requests_seconds_max'),
    activeRequests: sum('http_server_requests_active_seconds_active_count'),
  },
  executors: Object.fromEntries(
    (series.get('executor_active_threads') || []).map((e) => {
      const name = (e.labels.match(/name="([^"]+)"/) || [, 'unknown'])[1];
      return [name, { active: e.value, queued: sum('executor_queued_tasks', label('name', name)) }];
    }),
  ),
  availability: {
    filterChainMetricsExported: has('filter_execution_seconds_count') || has('gateway_filter_seconds_count'),
  },
};

const json = JSON.stringify(snapshot, null, 2);
if (args.out) {
  fs.mkdirSync(path.dirname(path.resolve(args.out)), { recursive: true });
  fs.writeFileSync(args.out, json);
}
console.log(json);
