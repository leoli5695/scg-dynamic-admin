#!/usr/bin/env node
// Mock LLM upstream: OpenAI-compatible /v1/chat/completions with a parameterised
// latency profile. Its job is to be the harness ceiling, so it must stay far
// lighter than the gateway under test - zero dependencies, precomputed payloads,
// and it reports its own event-loop lag so an invalid T0 baseline is visible
// rather than silently folded into the gateway's numbers.
//
// Profile comes from env defaults, overridable per request via x-mock-* headers
// (headers survive the gateway untouched, unlike body fields).

import http from 'node:http';

const PORT = Number(process.env.MOCK_PORT || 18080);
const HOST = process.env.MOCK_HOST || '127.0.0.1';
const DEF_TTFT_MS = Number(process.env.MOCK_TTFT_MS || 200);
const DEF_TOKENS = Number(process.env.MOCK_TOKENS || 64);
const DEF_TPOT_MS = Number(process.env.MOCK_TPOT_MS || 25);

const state = {
  inflight: 0,
  requests: 0,
  streams: 0,
  tokensSent: 0,
  backpressureWrites: 0,
  aborted: 0,
  startedAt: Date.now(),
};

// Event-loop lag: if the mock cannot keep its own timers, every downstream
// percentile is measuring the mock rather than the gateway.
const lagSamples = [];
let lastTick = process.hrtime.bigint();
const lagTimer = setInterval(() => {
  const now = process.hrtime.bigint();
  const elapsedMs = Number(now - lastTick) / 1e6;
  lastTick = now;
  lagSamples.push(Math.max(0, elapsedMs - 100));
  if (lagSamples.length > 600) lagSamples.shift();
}, 100);
lagTimer.unref();

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[idx];
}

function numHeader(req, name, fallback) {
  const raw = req.headers[name];
  const value = Number(raw);
  return raw !== undefined && Number.isFinite(value) && value >= 0 ? value : fallback;
}

function sseEvent(obj) {
  return `data: ${JSON.stringify(obj)}\n\n`;
}

function chunkEnvelope(id, model, delta, finishReason, usage) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }],
    usage: usage ?? null,
  };
}

function usageEnvelope(id, model, promptTokens, completionTokens) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [],
    usage: {
      prompt_tokens: promptTokens,
      completion_tokens: completionTokens,
      total_tokens: promptTokens + completionTokens,
    },
  };
}

function readBody(req, limit = 1 << 20) {
  return new Promise((resolve, reject) => {
    const parts = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(Object.assign(new Error('body too large'), { statusCode: 413 }));
        req.destroy();
        return;
      }
      parts.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')));
    req.on('error', reject);
  });
}

function sendJson(res, statusCode, payload) {
  const body = Buffer.from(JSON.stringify(payload));
  res.writeHead(statusCode, {
    'content-type': 'application/json',
    'content-length': body.length,
  });
  res.end(body);
}

// Deterministic content so repeated runs are comparable and cache effects do not
// vary between tiers.
function tokenText(i) {
  return i === 0 ? 'Response' : ` tok${i}`;
}

async function handleChat(req, res) {
  let raw;
  try {
    raw = await readBody(req);
  } catch (err) {
    sendJson(res, err.statusCode || 400, { error: { message: err.message } });
    return;
  }

  let body = {};
  try {
    body = raw ? JSON.parse(raw) : {};
  } catch {
    sendJson(res, 400, { error: { message: 'invalid JSON body' } });
    return;
  }

  const model = typeof body.model === 'string' ? body.model : 'mock-model';
  const ttftMs = numHeader(req, 'x-mock-ttft-ms', DEF_TTFT_MS);
  const tokens = Math.max(0, Math.round(numHeader(req, 'x-mock-tokens', DEF_TOKENS)));
  const tpotMs = numHeader(req, 'x-mock-tpot-ms', DEF_TPOT_MS);
  const isStream = body.stream === true;
  const includeUsage = body.stream_options?.include_usage === true;
  const id = `chatcmpl-mock-${state.requests}`;
  // Stable, body-derived prompt count: varies realistically, reproducible.
  const promptTokens = Math.max(1, Math.ceil(raw.length / 4));

  state.requests += 1;

  if (!isStream) {
    const totalMs = ttftMs + tokens * tpotMs;
    setTimeout(() => {
      let content = '';
      for (let i = 0; i < tokens; i += 1) content += tokenText(i);
      state.tokensSent += tokens;
      sendJson(res, 200, {
        id,
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model,
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: {
          prompt_tokens: promptTokens,
          completion_tokens: tokens,
          total_tokens: promptTokens + tokens,
        },
      });
    }, totalMs);
    return;
  }

  state.streams += 1;
  state.inflight += 1;
  req.socket?.setNoDelay?.(true);
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache, no-transform',
    connection: 'keep-alive',
    'x-mock-echo-tokens': String(tokens),
    'x-mock-echo-ttft-ms': String(ttftMs),
    'x-mock-echo-tpot-ms': String(tpotMs),
  });

  const write = (text) => {
    if (res.writableEnded || res.destroyed) return false;
    if (!res.write(text)) state.backpressureWrites += 1;
    return true;
  };

  // Absolute schedule with drift correction: a naive chained setTimeout(tpot)
  // accumulates timer slack and would smear the inter-token distribution the
  // harness is trying to measure.
  const startNs = process.hrtime.bigint();
  const timers = new Set();
  const at = (offsetMs, fn) => {
    const target = startNs + BigInt(Math.round(offsetMs * 1e6));
    const delay = Math.max(0, Number(target - process.hrtime.bigint()) / 1e6);
    const handle = setTimeout(() => {
      timers.delete(handle);
      fn();
    }, delay);
    timers.add(handle);
    return handle;
  };

  const finish = () => {
    for (const handle of timers) clearTimeout(handle);
    timers.clear();
    state.inflight -= 1;
    if (!res.writableEnded) res.end();
  };

  res.on('close', () => {
    if (timers.size > 0) {
      state.aborted += 1;
      finish();
    }
  });

  at(ttftMs, () => {
    write(sseEvent(chunkEnvelope(id, model, { role: 'assistant', content: '' }, null)));
    for (let i = 0; i < tokens; i += 1) {
      at(ttftMs + (i + 1) * tpotMs, () => {
        state.tokensSent += 1;
        write(sseEvent(chunkEnvelope(id, model, { content: tokenText(i) }, null)));
      });
    }
    const doneAt = ttftMs + (tokens + 1) * tpotMs;
    at(doneAt, () => {
      write(sseEvent(chunkEnvelope(id, model, { content: '' }, 'stop')));
      if (includeUsage) {
        write(sseEvent(usageEnvelope(id, model, promptTokens, tokens)));
      }
      write('data: [DONE]\n\n');
      finish();
    });
  });
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/mock/stats') {
    const sorted = [...lagSamples].sort((a, b) => a - b);
    sendJson(res, 200, {
      ...state,
      uptimeSec: Math.round((Date.now() - state.startedAt) / 1000),
      eventLoopLagMs: {
        p50: percentile(sorted, 50),
        p99: percentile(sorted, 99),
        max: sorted.length ? sorted[sorted.length - 1] : 0,
        samples: sorted.length,
      },
    });
    return;
  }
  if (req.method === 'GET' && (req.url === '/health' || req.url === '/v1/models')) {
    sendJson(res, 200, { status: 'ok', object: 'list', data: [{ id: 'mock-model' }] });
    return;
  }
  if (req.method === 'POST' && req.url?.split('?')[0] === '/v1/chat/completions') {
    handleChat(req, res).catch((err) => {
      if (!res.headersSent) sendJson(res, 500, { error: { message: err.message } });
      else res.end();
    });
    return;
  }
  sendJson(res, 404, { error: { message: `no route for ${req.method} ${req.url}` } });
});

server.keepAliveTimeout = 120_000;
server.headersTimeout = 130_000;
server.requestTimeout = 0;

server.listen(PORT, HOST, () => {
  console.log(
    `mock-llm listening on http://${HOST}:${PORT} ` +
      `(ttft=${DEF_TTFT_MS}ms tokens=${DEF_TOKENS} tpot=${DEF_TPOT_MS}ms)`,
  );
});
