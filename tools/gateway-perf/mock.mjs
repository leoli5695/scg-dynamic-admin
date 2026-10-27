import http from 'node:http';
import { monitorEventLoopDelay } from 'node:perf_hooks';

const lag = monitorEventLoopDelay({ resolution: 10 });
lag.enable();
let requests = 0;
let active = 0;
const ordinary = Buffer.from(JSON.stringify({ ok: true, payload: 'x'.repeat(1000) }));
const server = http.createServer((req, res) => {
  if (req.url === '/__baseline/metrics') {
    const cpu = process.cpuUsage();
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ timestampMs: Date.now(), pid: process.pid, processCpuNs: (cpu.user + cpu.system) * 1000,
      rssBytes: process.memoryUsage().rss, eventLoopP99Ms: lag.percentile(99) / 1e6, requests, active }));
    lag.reset();
    return;
  }
  requests++;
  active++;
  res.on('close', () => { active--; });
  let received = 0;
  req.on('data', chunk => { received += chunk.length; });
  req.on('end', () => {
    res.setHeader('x-baseline-fixture', 'v1');
    if (req.url.startsWith('/baseline/sse') || req.url.endsWith('/chat/completions')) {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store' });
      let token = 0;
      let timer;
      res.on('close', () => clearTimeout(timer));
      const next = () => {
        if (res.destroyed) return;
        if (token === 16) { res.end('data: [DONE]\n\n'); return; }
        const frame = `data: ${JSON.stringify({ choices: [{ delta: { content: `token${token++}` } }] })}\n\n`;
        const schedule = () => { timer = setTimeout(next, 10); };
        if (res.write(frame)) schedule();
        else res.once('drain', schedule);
      };
      timer = setTimeout(next, 50);
      return;
    }
    const body = req.url.startsWith('/baseline/body') ? Buffer.from(JSON.stringify({ receivedBytes: received })) : ordinary;
    res.writeHead(200, { 'content-type': 'application/json', 'content-length': body.length });
    res.end(body);
  });
});
server.keepAliveTimeout = 120000;
server.listen(0, '127.0.0.1', () => console.log(JSON.stringify({ port: server.address().port, pid: process.pid })));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
