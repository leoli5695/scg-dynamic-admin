import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { measure, distribution } from './load.mjs';

async function fixture(handler, action) {
  const server = http.createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try { await action(`http://127.0.0.1:${server.address().port}`); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}

test('percentiles use nearest rank and expose sample counts', () => {
  const result = distribution(Array.from({ length: 100 }, (_, i) => i + 1));
  assert.equal(result.n, 100); assert.equal(result.p50, 50); assert.equal(result.p95, 95); assert.equal(result.p99, 99);
  assert.equal(distribution([]), null);
});

test('HTTP errors are counted rather than reported as successful throughput', async () => {
  await fixture((req, res) => { res.writeHead(503); res.end('overloaded'); }, async url => {
    const result = await measure({ url, concurrency: 1, warmupMs: 30, holdMs: 150, scenario: 'ordinary' });
    assert.equal(result.succeeded, 0); assert.equal(result.successRps, 0);
    assert.ok(result.failed > 0); assert.equal(result.errorRate, 1); assert.ok(result.errors.HTTP_503 > 0);
  });
});

test('large request sends and validates the complete 1MiB body', async () => {
  await fixture((req, res) => {
    let bytes = 0;
    req.on('data', chunk => { bytes += chunk.length; });
    req.on('end', () => {
      res.setHeader('x-baseline-fixture', 'v1'); res.end(JSON.stringify({ receivedBytes: bytes }));
    });
  }, async url => {
    const result = await measure({ url, concurrency: 1, warmupMs: 30, holdMs: 150, scenario: 'body' });
    assert.ok(result.succeeded > 0); assert.equal(result.failed, 0);
  });
});

test('SSE requires complete frame count and DONE, not merely HTTP 200', async () => {
  await fixture((req, res) => {
    req.resume();
    req.on('end', () => {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'x-baseline-fixture': 'v1' });
      res.end('data: {"choices":[{"delta":{"content":"one"}}]}\n\n');
    });
  }, async url => {
    const result = await measure({ url, concurrency: 1, warmupMs: 30, holdMs: 150, scenario: 'sse' });
    assert.equal(result.succeeded, 0); assert.ok(result.errors.TRUNCATED_SSE > 0);
  });
});
