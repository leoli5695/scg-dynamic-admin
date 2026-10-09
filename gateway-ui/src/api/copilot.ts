/**
 * AI Copilot API.
 * Endpoints aligned with controller/AiCopilotController.java (/api/copilot).
 * All endpoints return raw bodies (no ApiResponse envelope).
 */
import httpClient, { ApiError, RequestOptions } from './http';
import type {
  AiProvider,
  ApiKeyValidationResult,
  ApplyRouteResult,
  ClearChatResult,
  ConceptExplanation,
  CopilotChatRequest,
  CopilotChatResponse,
  DebugAnalysisResult,
  ExecuteActionResult,
  KnowledgeChunkPage,
  KnowledgeSearchResult,
  KnowledgeStats,
  OptimizationResult,
  RouteGenerationResult,
  SaveConfigResult,
  ValidateRouteResult,
} from '../types/copilot';

const BASE = '/api/copilot';

/** POST /chat — send a chat message (intent auto-detected by backend). */
export const sendCopilotChat = (
  body: CopilotChatRequest,
  options?: RequestOptions,
) => httpClient.post<CopilotChatResponse>(`${BASE}/chat`, body, options);

/**
 * POST /execute-action — run an AI-proposed write operation after the user has
 * explicitly confirmed it in the UI. The server executes the action it stored for
 * the one-time `ticket` (never the client-supplied arguments), so this is the only
 * path that runs confirmation-required tools and the model can never self-confirm.
 */
export const executeCopilotAction = (
  body: { ticket: string; toolName?: string },
  options?: RequestOptions,
) => httpClient.post<ExecuteActionResult>(`${BASE}/execute-action`, body, options);

/** DELETE /chat/{sessionId} — clear a chat session. */
export const clearCopilotChat = (sessionId: string, options?: RequestOptions) =>
  httpClient.delete<ClearChatResult>(`${BASE}/chat/${encodeURIComponent(sessionId)}`, options);

/** POST /feedback — record 👍/👎 (with Q&A snapshot) for an assistant answer. */
export const submitCopilotFeedback = (
  body: { sessionId?: string; question?: string; answer?: string; rating: 'UP' | 'DOWN'; comment?: string },
  options?: RequestOptions,
) => httpClient.post<{ success: boolean; id?: number }>(`${BASE}/feedback`, body, options);

/** POST /generate-route — generate a route config from a description. */
export const generateRoute = (
  body: { description: string; instanceId?: string; provider?: string; model?: string },
  options?: RequestOptions,
) => httpClient.post<RouteGenerationResult>(`${BASE}/generate-route`, body, options);

/** POST /analyze-error — AI diagnosis of an error message. */
export const analyzeError = (
  body: { errorMessage: string; instanceId?: string; provider?: string; model?: string },
  options?: RequestOptions,
) => httpClient.post<DebugAnalysisResult>(`${BASE}/analyze-error`, body, options);

/** GET /optimizations/{instanceId} — optimization suggestions. */
export const getOptimizations = (
  instanceId: string,
  params?: { provider?: string; model?: string },
  options?: RequestOptions,
) => httpClient.get<OptimizationResult>(`${BASE}/optimizations/${encodeURIComponent(instanceId)}`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /explain — explain a gateway concept. */
export const explainConcept = (
  params: { concept: string; instanceId?: string; provider?: string; model?: string },
  options?: RequestOptions,
) => httpClient.get<ConceptExplanation>(`${BASE}/explain`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /providers — configured AI providers (raw list). */
export const getAiProviders = (options?: RequestOptions) =>
  httpClient.get<AiProvider[]>(`${BASE}/providers`, options);

/** GET /providers/{provider}/models — available models (raw string list). */
export const getAiProviderModels = (provider: string, options?: RequestOptions) =>
  httpClient.get<string[]>(`${BASE}/providers/${encodeURIComponent(provider)}/models`, options);

/** POST /validate — validate an API key. */
export const validateApiKey = (
  body: { provider: string; apiKey: string; baseUrl?: string | null },
  options?: RequestOptions,
) => httpClient.post<ApiKeyValidationResult>(`${BASE}/validate`, body, options);

/** POST /config — persist provider/model/apiKey configuration. */
export const saveAiConfig = (
  body: { provider: string; model: string; apiKey: string; baseUrl?: string | null },
  options?: RequestOptions,
) => httpClient.post<SaveConfigResult>(`${BASE}/config`, body, options);

/** POST /validate-route — validate a generated route JSON. */
export const validateRouteJson = (routeJson: string, options?: RequestOptions) =>
  httpClient.post<ValidateRouteResult>(`${BASE}/validate-route`, { routeJson }, options);

/** POST /apply-route — create the route from a validated JSON. */
export const applyRouteJson = (
  body: { routeJson: string; instanceId: string },
  options?: RequestOptions,
) => httpClient.post<ApplyRouteResult>(`${BASE}/apply-route`, body, options);

// ============== Streaming chat (SSE over POST via fetch) ==============

/** Payload of a `status` SSE event. */
export interface CopilotStreamStatus {
  stage: string;
  intent?: string;
  language?: string;
  iteration?: number;
}

/** Payload of a `tool` SSE event. */
export interface CopilotStreamTool {
  toolName: string;
  status: 'running' | 'success' | 'error' | 'confirm';
  durationMs?: number;
}

export interface CopilotStreamHandlers {
  onStatus?: (data: CopilotStreamStatus) => void;
  onTool?: (data: CopilotStreamTool) => void;
  /** Token-level增量：最终答案的文本片段，按到达顺序回调（打字机效果）。 */
  onDelta?: (text: string) => void;
  /** Terminal event: same shape as the non-streaming /chat response. */
  onMessage: (data: CopilotChatResponse) => void;
  onError?: (message: string) => void;
  signal?: AbortSignal;
}

/**
 * POST /chat/stream — stream an AI Copilot reply over Server-Sent Events.
 * EventSource can't do POST, so this uses fetch + a streaming body reader and
 * reuses the same bearer-token auth as httpClient. Each SSE frame is
 * `event:<name>\ndata:<json>\n\n`; frames are dispatched to the handlers.
 */
export async function sendCopilotChatStream(
  body: CopilotChatRequest,
  handlers: CopilotStreamHandlers,
): Promise<void> {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '';
  const token = localStorage.getItem('token');

  const resp = await fetch(`${baseURL}/api/copilot/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: handlers.signal,
  });

  if (resp.status === 401) {
    localStorage.removeItem('token');
    if (!window.location.pathname.startsWith('/login')) {
      window.location.href = '/login';
    }
    throw new ApiError('Unauthorized', 401);
  }
  if (!resp.ok || !resp.body) {
    let detail = `HTTP ${resp.status}`;
    try {
      const text = await resp.text();
      if (text) detail = text.slice(0, 300);
    } catch {
      // ignore body-read failure
    }
    throw new ApiError(detail, resp.status);
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  const dispatchFrame = (frame: string) => {
    let event = 'message';
    const dataLines: string[] = [];
    for (const raw of frame.split('\n')) {
      const line = raw.replace(/\r$/, '');
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (dataLines.length === 0) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(dataLines.join('\n'));
    } catch {
      return;
    }
    switch (event) {
      case 'status':
        handlers.onStatus?.(parsed as CopilotStreamStatus);
        break;
      case 'tool':
        handlers.onTool?.(parsed as CopilotStreamTool);
        break;
      case 'delta':
        handlers.onDelta?.((parsed as { text?: string })?.text ?? '');
        break;
      case 'error':
        handlers.onError?.((parsed as { message?: string })?.message || 'AI error');
        break;
      default:
        handlers.onMessage(parsed as CopilotChatResponse);
        break;
    }
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx = buffer.indexOf('\n\n');
    while (idx >= 0) {
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      if (frame.trim()) dispatchFrame(frame);
      idx = buffer.indexOf('\n\n');
    }
  }
  if (buffer.trim()) dispatchFrame(buffer);
}

// ============== Knowledge base (RAG) management ==============

/** GET /knowledge/stats — 知识库概览统计。 */
export const getKnowledgeStats = (options?: RequestOptions) =>
  httpClient.get<KnowledgeStats>(`${BASE}/knowledge/stats`, options);

/** GET /knowledge/chunks — 分页列出分块。 */
export const listKnowledgeChunks = (
  params: { type?: string; page?: number; size?: number },
  options?: RequestOptions,
) => httpClient.get<KnowledgeChunkPage>(`${BASE}/knowledge/chunks`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** POST /knowledge/ingest — 摄取一篇文档（分块+嵌入+入库）。 */
export const ingestKnowledge = (
  body: { sourceType?: string; sourceRef?: string; title?: string; content: string },
  options?: RequestOptions,
) => httpClient.post<{ success: boolean; chunks?: number; message?: string }>(
  `${BASE}/knowledge/ingest`, body, options);

/** POST /knowledge/reindex — 重建内存向量索引。 */
export const reindexKnowledge = (options?: RequestOptions) =>
  httpClient.post<{ success: boolean; indexedChunks: number }>(`${BASE}/knowledge/reindex`, undefined, options);

/** DELETE /knowledge/chunks/{id} — 删除单个分块。 */
export const deleteKnowledgeChunk = (id: number, options?: RequestOptions) =>
  httpClient.delete<{ success: boolean }>(`${BASE}/knowledge/chunks/${id}`, options);

/** POST /knowledge/search — 测试检索（验证召回质量）。 */
export const searchKnowledge = (
  body: { query: string; topK?: number; sourceType?: string },
  options?: RequestOptions,
) => httpClient.post<KnowledgeSearchResult>(`${BASE}/knowledge/search`, body, options);
