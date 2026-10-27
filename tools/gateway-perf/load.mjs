import { monitorEventLoopDelay, performance } from 'node:perf_hooks';
import http from 'node:http';

function requestHttp(url, { method, body, headers, signal, agent }) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, { method, headers, signal, agent }, response => {
      const read = async () => {
        const chunks = [];
        for await (const chunk of response) chunks.push(chunk);
        return Buffer.concat(chunks);
      };
      resolve({ ok: response.statusCode >= 200 && response.statusCode < 300, status: response.statusCode,
        headers: { get: name => response.headers[name.toLowerCase()] }, body: response,
        arrayBuffer: read, json: async () => JSON.parse((await read()).toString('utf8')) });
    });
    req.on('error', reject);
    req.end(body);
  });
}

export function distribution(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = p => sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)];
  return { n: sorted.length, p50: percentile(.5), p95: percentile(.95), p99: percentile(.99), max: sorted.at(-1),
    mean: sorted.reduce((a, b) => a + b, 0) / sorted.length };
}

export async function measure({ url, concurrency, warmupMs, holdMs, headers = {}, scenario, metricsUrls = [], timeoutMs = 15000, lagLimitMs = 20 }) {
  const start = performance.now();
  const begin = start + warmupMs;
  const end = begin + holdMs;
  const latencies = [], ttft = [], tpot = [];
  const statuses = {}, errors = {}, resources = [];
  let attempted = 0, succeeded = 0, failed = 0, completedInWindow = 0, monitoringErrors = 0;
  const body = scenario === 'body' ? Buffer.alloc(1024 * 1024, 120)
    : scenario === 'sse' ? JSON.stringify({ model: 'baseline-model', stream: true, messages: [{ role: 'user', content: 'hello' }] }) : undefined;
  const lag = monitorEventLoopDelay({ resolution: 10 });
  const agent = new http.Agent({ keepAlive: true, maxSockets: concurrency, maxFreeSockets: concurrency });
  lag.enable();
  let cpuStart;
  const cpuTimer = setTimeout(() => { cpuStart = process.cpuUsage(); lag.reset(); }, warmupMs);
  let sampling = false;
  const sample = async () => {
    if (sampling) return;
    sampling = true;
    try {
      for (const [name, endpoint] of metricsUrls) {
        const response = await fetch(endpoint, { signal: AbortSignal.timeout(3000) });
        if (!response.ok) throw new Error(`metrics HTTP ${response.status}`);
        resources.push({ name, offsetMs: performance.now() - begin, ...await response.json() });
      }
    } catch { monitoringErrors++; }
    finally { sampling = false; }
  };
  await sample();
  const sampler = setInterval(sample, 1000);
  async function request() {
    const sent = performance.now();
    const measured = sent >= begin && sent < end;
    if (measured) attempted++;
    let status;
    try {
      const response = await requestHttp(url, { method: body ? 'POST' : 'GET', body, agent,
        headers: { ...(body ? { 'content-type': scenario === 'body' ? 'application/octet-stream' : 'application/json',
          'content-length': Buffer.byteLength(body) } : {}), ...headers },
        signal: AbortSignal.timeout(timeoutMs) });
      status = response.status;
      if (!response.ok) { await response.arrayBuffer(); throw new Error(`HTTP_${response.status}`); }
      if (response.headers.get('x-baseline-fixture') !== 'v1') throw new Error('WRONG_UPSTREAM');
      if (scenario === 'sse') {
        if (!response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('NOT_SSE');
        const decoder = new TextDecoder();
        let pending = '', first, previous, done = false, count = 0;
        const intervals = [];
        for await (const chunk of response.body) {
          pending += decoder.decode(chunk, { stream: true });
          let boundary;
          while ((boundary = pending.indexOf('\n\n')) >= 0) {
            const frame = pending.slice(0, boundary); pending = pending.slice(boundary + 2);
            if (frame === 'data: [DONE]') { done = true; continue; }
            const text = frame.startsWith('data: ') ? frame.slice(6) : '';
            if (text && JSON.parse(text).choices?.[0]?.delta?.content) {
              const now = performance.now();
              first ??= now;
              if (previous !== undefined) intervals.push(now - previous);
              previous = now; count++;
            }
          }
        }
        if (!done || count !== 16) throw new Error('TRUNCATED_SSE');
        if (measured) { ttft.push(first - sent); tpot.push(...intervals); }
      } else if (scenario === 'body') {
        const reply = await response.json();
        if (reply.receivedBytes !== body.length) throw new Error('BODY_LENGTH_MISMATCH');
      } else {
        const reply = await response.json();
        if (reply.ok !== true || reply.payload?.length !== 1000) throw new Error('BODY_MISMATCH');
      }
      const finished = performance.now();
      if (finished >= begin && finished < end) completedInWindow++;
      if (measured) { succeeded++; latencies.push(finished - sent); }
    } catch (error) {
      if (measured) { failed++; errors[error.name === 'TimeoutError' ? 'TIMEOUT' : error.message] = (errors[error.name === 'TimeoutError' ? 'TIMEOUT' : error.message] || 0) + 1; }
    } finally {
      if (measured && status) statuses[status] = (statuses[status] || 0) + 1;
    }
  }
  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (performance.now() < end) await request();
  }));
  agent.destroy();
  const cpuEnd = process.cpuUsage(cpuStart);
  clearTimeout(cpuTimer); clearInterval(sampler);
  while (sampling) await new Promise(resolve => setTimeout(resolve, 10));
  const client = { cpuCoresIncludingDrain: (cpuEnd.user + cpuEnd.system) / 1e6 / ((performance.now() - begin) / 1000),
    eventLoopP99Ms: lag.percentile(99) / 1e6, rssBytes: process.memoryUsage().rss };
  lag.disable();
  const metrics = {};
  for (const [name] of metricsUrls) {
    const points = resources.filter(point => point.name === name && point.offsetMs >= 0 && point.offsetMs <= holdMs);
    const first = points[0], last = points.at(-1);
    metrics[name] = { samples: points.length,
      meanCpuCores: points.length > 1 ? (last.processCpuNs - first.processCpuNs) / 1e6 / (last.timestampMs - first.timestampMs) : null,
      peakHeapUsedBytes: points.length ? Math.max(...points.map(p => p.heapUsedBytes || 0)) || null : null,
      peakDirectBytes: points.length ? Math.max(...points.map(p => p.directBytes || 0)) : null,
      peakRssBytes: points.length ? Math.max(...points.map(p => p.rssBytes || 0)) || null : null,
      gcCount: points.length > 1 && last.gcCount !== undefined ? last.gcCount - first.gcCount : null,
      gcMs: points.length > 1 && last.gcMs !== undefined ? last.gcMs - first.gcMs : null,
      peakEventLoopP99Ms: points.length ? Math.max(...points.map(p => p.eventLoopP99Ms || 0)) : null };
  }
  return { concurrency, warmupMs, holdMs, attempted, succeeded, failed, completedInWindow,
    successRps: completedInWindow / (holdMs / 1000), errorRate: attempted ? failed / attempted : null,
    latencyMs: distribution(latencies), ttftMs: distribution(ttft), tpotMs: distribution(tpot),
    statuses, errors, client, metrics, monitoringErrors, resources,
    p99LowSampleWarning: latencies.length < 1000,
    lagLimitMs, environmentLimited: client.cpuCoresIncludingDrain > .8 || client.eventLoopP99Ms > lagLimitMs
      || (metrics.upstream?.meanCpuCores || 0) > .8 || (metrics.upstream?.peakEventLoopP99Ms || 0) > lagLimitMs };
}
