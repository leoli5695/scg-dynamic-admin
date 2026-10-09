/**
 * AI Copilot domain types.
 * Aligned with controller/AiCopilotController.java + AiConfigController.java
 * and service/AiCopilotService result classes (ChatResponse, RouteGenerationResult,
 * DebugAnalysis, OptimizationResult — all serialized via toMap()).
 *
 * NOTE: /api/copilot/* endpoints return raw Map/List bodies (no ApiResponse
 * envelope); only /api/ai/analyze/timerange uses the envelope.
 */

/** GET /api/copilot/providers item (AiProviderConfig entity projection). */
export interface AiProvider {
  id: number;
  provider: string;
  providerName: string;
  region: string;
  model: string;
  isValid: boolean;
}

/** POST /api/copilot/chat body (ChatRequest DTO). */
export interface CopilotChatRequest {
  sessionId?: string;
  message: string;
  /** Optional intent hint; omitted so the backend auto-detects intent. */
  context?: string;
  instanceId?: string;
  provider?: string;
  model?: string;
}

/** POST /api/copilot/chat response (ChatResponse.toMap() + sessionId). */
export interface CopilotChatResponse {
  success: boolean;
  response?: string;
  error?: string;
  /** Honest evidence backing the answer (replaces the old fake confidence %). */
  evidence?: CopilotEvidence;
  sessionId: string;
  /** Present when the AI proposed a write operation that needs human confirmation. */
  pendingAction?: CopilotPendingAction;
}

/** What live data (if any) backs an AI answer. */
export interface CopilotEvidence {
  level: 'DATA_BACKED' | 'PARTIAL' | 'NO_DATA';
  note: string;
  toolCount: number;
  dataTypes: string[];
  failedTools: string[];
}

/** A write action proposed by the AI, awaiting explicit user confirmation. */
export interface CopilotPendingAction {
  /**
   * One-time ticket issued by the backend. On confirm, the client sends only this
   * ticket; the server executes the toolName/arguments it stored for it, so the
   * payload below cannot be tampered with to run a different action.
   */
  ticket: string;
  toolName: string;
  arguments: Record<string, unknown>;
  preview?: Record<string, unknown>;
}

/** POST /api/copilot/execute-action response. */
export interface ExecuteActionResult {
  success: boolean;
  message?: string;
  data?: unknown;
}

/** DELETE /api/copilot/chat/{sessionId} response. */
export interface ClearChatResult {
  success: boolean;
  message: string;
}

/** POST /api/copilot/generate-route response (RouteGenerationResult.toMap()). */
export interface RouteGenerationResult {
  success: boolean;
  config?: string;
  error?: string;
}

/** POST /api/copilot/analyze-error response (DebugAnalysis.toMap()). */
export interface DebugAnalysisResult {
  success: boolean;
  analysis?: string;
  error?: string;
  evidence?: CopilotEvidence;
}

/** GET /api/copilot/optimizations/{instanceId} response (OptimizationResult.toMap()). */
export interface OptimizationResult {
  success: boolean;
  suggestions?: string;
  error?: string;
}

/** GET /api/copilot/explain response. */
export interface ConceptExplanation {
  success: boolean;
  concept: string;
  explanation: string;
}

/** POST /api/copilot/validate response. */
export interface ApiKeyValidationResult {
  valid: boolean;
  provider: string;
}

/** POST /api/copilot/config response. */
export interface SaveConfigResult {
  success: boolean;
  provider: string;
  model: string;
}

/** POST /api/copilot/validate-route response. */
export interface ValidateRouteResult {
  success: boolean;
  valid: boolean;
  message?: string;
  errors?: string[];
}

/** POST /api/copilot/apply-route response. */
export interface ApplyRouteResult {
  success: boolean;
  message: string;
  routeId?: string;
  routeName?: string;
}

/** POST /api/ai/analyze/timerange body. Timestamps in epoch millis. */
export interface AiTimeRangeAnalysisRequest {
  provider: string;
  startTime: number;
  endTime: number;
  language?: string;
}

/** POST /api/ai/analyze/timerange envelope data. */
export interface AiTimeRangeAnalysisResult {
  result: string;
}
