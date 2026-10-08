/**
 * AI Copilot API.
 * Endpoints aligned with controller/AiCopilotController.java (/api/copilot).
 * All endpoints return raw bodies (no ApiResponse envelope).
 */
import httpClient, { RequestOptions } from './http';
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
 * explicitly confirmed it in the UI. This is the only path that executes
 * confirmation-required tools; the model can never self-confirm.
 */
export const executeCopilotAction = (
  body: { toolName: string; arguments: Record<string, unknown> },
  options?: RequestOptions,
) => httpClient.post<ExecuteActionResult>(`${BASE}/execute-action`, body, options);

/** DELETE /chat/{sessionId} — clear a chat session. */
export const clearCopilotChat = (sessionId: string, options?: RequestOptions) =>
  httpClient.delete<ClearChatResult>(`${BASE}/chat/${encodeURIComponent(sessionId)}`, options);

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
